package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class ExternalOriginalGuardTest {
    private class Probe {
        var source=true;var output=true;var active=true;var published=true
        val cancel=Cancel();val calls=mutableListOf<String>()
        var sourceAction:()->Unit={};var outputAction:()->Unit={};var deleteAction:()->Unit={}
        fun run(policy:ExternalOriginalPolicy=ExternalOriginalPolicy.DELETE_AFTER_SAVE)=ExternalOriginalGuard.afterPublished(policy,published,cancel,
            {calls+="active";active},{calls+="source";sourceAction();source},{calls+="output";outputAction();output},
            {calls+="remove";deleteAction();OriginalOutcome(OriginalAction.DELETED,"removed synthetic input")})
    }
    private val identity=ExternalPhoto("fixture-key","fixture://original/1","new.jpg",1024,2000,created=1000)
    @Test fun sameContentUriIdentityIsAdmitted(){assertTrue(ExternalOriginalGuard.sameIdentity(identity,identity.copy()))}
    @Test fun replacedCreationTimeIsNotTheOldOriginal(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(created=1001)))}
    @Test fun missingIdentityIsNotAdmitted(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,null))}
    @Test fun unknownCreationTimeNeverAllowsAutomaticRemoval(){val p=identity.copy(created=0);assertFalse(ExternalOriginalGuard.sameIdentity(p,p))}
    @Test fun changedUriIsNotTheOriginal(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(uri="fixture://original/2")))}
    @Test fun changedKeyIsNotTheOriginal(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(key="other")))}
    @Test fun renamedOriginalIsRetained(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(name="renamed.jpg")))}
    @Test fun pendingOriginalIsRetained(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(pending=true)))}
    @Test fun changedSourceMetadataIsRetained(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(size=1025)));assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity.copy(modified=2001)))}
    @Test fun missingFileKeyPreventsUnlink(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity,true,"",""))}
    @Test fun changedInodePreventsUnlinkOfReplacement(){assertFalse(ExternalOriginalGuard.sameIdentity(identity,identity,true,"dev1/ino1","dev1/ino2"))}
    @Test fun sameFileObjectIsAdmitted(){assertTrue(ExternalOriginalGuard.sameIdentity(identity,identity,true,"dev1/ino1","dev1/ino1"))}
    @Test fun defaultIsKeep(){assertEquals(ExternalOriginalPolicy.KEEP,ExternalOriginalPolicy.fromId(null))}
    @Test fun unknownSettingNeverEnablesRemoval(){assertEquals(ExternalOriginalPolicy.KEEP,ExternalOriginalPolicy.fromId("true"))}
    @Test fun storedChoiceIsStable(){ExternalOriginalPolicy.entries.forEach{assertEquals(it,ExternalOriginalPolicy.fromId(it.id))}}
    @Test fun keepNeverReadsOrRemovesAnything(){val p=Probe();assertEquals(OriginalAction.KEPT,p.run(ExternalOriginalPolicy.KEEP).action);assertTrue(p.calls.isEmpty())}
    @Test fun keepRemainsSafeWhenCancelled(){val p=Probe();p.cancel.cancelled=true;assertEquals(OriginalAction.KEPT,p.run(ExternalOriginalPolicy.KEEP).action);assertTrue(p.calls.isEmpty())}
    @Test fun unpublishedNeverReadsOrDeletes(){val p=Probe();p.published=false;assertEquals(OriginalAction.KEPT,p.run().action);assertTrue(p.calls.isEmpty())}
    @Test fun failedMetadataCannotReachSourceRemoval(){val p=Probe();p.published=false;p.output=false;assertEquals(OriginalAction.KEPT,p.run().action);assertTrue(p.calls.isEmpty())}
    @Test fun oldRunIsNeverDeleted(){val p=Probe();p.active=false;assertEquals(OriginalAction.EXPIRED,p.run().action);assertEquals(listOf("active"),p.calls)}
    @Test fun changedSourceRetainsOriginal(){val p=Probe();p.source=false;assertEquals(OriginalAction.CHANGED,p.run().action);assertFalse(p.calls.contains("remove"));assertFalse(p.calls.contains("output"))}
    @Test fun missingPublishedFilePreventsDeletion(){val p=Probe();p.output=false;assertEquals(OriginalAction.FAILED,p.run().action);assertFalse(p.calls.contains("remove"))}
    @Test fun sourceIsCheckedBeforeOutputAndDelete(){val p=Probe();assertEquals(OriginalAction.DELETED,p.run().action);assertEquals(listOf("active","source","output","active","remove"),p.calls)}
    @Test fun cancellationBeforeSourceIsNotIgnored(){val p=Probe();p.cancel.cancelled=true;assertThrows(CancellationException::class.java){p.run()};assertTrue(p.calls.isEmpty())}
    @Test fun cancellationDuringSourcePreventsOutputAndDelete(){val p=Probe();p.sourceAction={p.cancel.cancelled=true};assertThrows(CancellationException::class.java){p.run()};assertEquals(listOf("active","source"),p.calls)}
    @Test fun cancellationDuringOutputPreventsDelete(){val p=Probe();p.outputAction={p.cancel.cancelled=true};assertThrows(CancellationException::class.java){p.run()};assertFalse(p.calls.contains("remove"))}
    @Test fun stopDuringOutputPreventsDelete(){val p=Probe();p.outputAction={p.active=false};assertEquals(OriginalAction.EXPIRED,p.run().action);assertFalse(p.calls.contains("remove"))}
    @Test fun stopDuringSourcePreventsDelete(){val p=Probe();p.sourceAction={p.active=false};assertEquals(OriginalAction.EXPIRED,p.run().action);assertFalse(p.calls.contains("remove"))}
    @Test fun sourceIoFailureDoesNotDelete(){val p=Probe();p.sourceAction={throw IOException("read failed")};assertThrows(IOException::class.java){p.run()};assertFalse(p.calls.contains("remove"))}
    @Test fun outputIoFailureDoesNotDelete(){val p=Probe();p.outputAction={throw IOException("read failed")};assertThrows(IOException::class.java){p.run()};assertFalse(p.calls.contains("remove"))}
    @Test fun rejectedSystemPermissionIsNotReportedAsDeletion(){val p=Probe();p.deleteAction={throw SecurityException("user permission required")};assertThrows(SecurityException::class.java){p.run()}}
    @Test fun cancellationAfterSuccessfulRemovalDoesNotHideIt(){val p=Probe();p.deleteAction={p.cancel.cancelled=true};assertEquals(OriginalAction.DELETED,p.run().action)}
    @Test fun digestMatchesGoldenSha256(){assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",ExternalOriginalGuard.digest("abc".byteInputStream()))}
    @Test fun digestMatchesEmptySha256(){assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",ExternalOriginalGuard.digest(ByteArrayInputStream(byteArrayOf())))}
    @Test fun digestReadsInBoundedChunksAndCloses(){
        var largest=0;var closed=false
        val input=object:ByteArrayInputStream(ByteArray(180000){(it%256).toByte()}){
            override fun read(b:ByteArray,off:Int,len:Int):Int{largest=maxOf(largest,len);return super.read(b,off,len)}
            override fun close(){closed=true;super.close()}
        }
        assertEquals(64,ExternalOriginalGuard.digest(input).length);assertTrue(largest<=65536);assertTrue(closed)
    }
    @Test fun digestCancellationClosesWithoutCompleting(){
        var closed=false;var checks=0
        val input=object:ByteArrayInputStream(ByteArray(180000)){override fun close(){closed=true}}
        assertThrows(CancellationException::class.java){ExternalOriginalGuard.digest(input){if(++checks==2)throw CancellationException()}}
        assertTrue(closed)
    }
    @Test fun storedCopyMatchesPinnedPrivateFile(){val b="synthetic encoded output".toByteArray();ExternalOriginalGuard.verifyCopy(ExternalOriginalGuard.digest(b.inputStream()),b.inputStream())}
    @Test fun storedCopyRejectsChangedByte(){val b=byteArrayOf(1,2,3);assertThrows(IllegalArgumentException::class.java){ExternalOriginalGuard.verifyCopy(ExternalOriginalGuard.digest(b.inputStream()),byteArrayOf(1,2,4).inputStream())}}
    @Test fun storedCopyRejectsTruncation(){val b=byteArrayOf(1,2,3);assertThrows(IllegalArgumentException::class.java){ExternalOriginalGuard.verifyCopy(ExternalOriginalGuard.digest(b.inputStream()),byteArrayOf(1,2).inputStream())}}
    @Test fun storedCopyRejectsAppendedData(){val b=byteArrayOf(1,2,3);assertThrows(IllegalArgumentException::class.java){ExternalOriginalGuard.verifyCopy(ExternalOriginalGuard.digest(b.inputStream()),byteArrayOf(1,2,3,4).inputStream())}}
    @Test fun storedCopyRejectsMissingPinnedDigest(){assertThrows(IllegalArgumentException::class.java){ExternalOriginalGuard.verifyCopy("","bytes".byteInputStream())}}
    @Test fun storedCopyMismatchClosesStream(){var closed=false;val input=object:ByteArrayInputStream(byteArrayOf(1)){override fun close(){closed=true}};assertThrows(IllegalArgumentException::class.java){ExternalOriginalGuard.verifyCopy("0".repeat(64),input)};assertTrue(closed)}
    @Test fun storedCopyCancellationRollsBackBeforePublication(){
        val cancel=Cancel();var published=false;var rolledBack=false
        assertThrows(CancellationException::class.java){ExportCommit.run(cancel,create={Unit},write={ExternalOriginalGuard.verifyCopy("0".repeat(64),ByteArray(180000).inputStream()){throw CancellationException()}},publish={published=true},rollback={rolledBack=true})}
        assertFalse(published);assertTrue(rolledBack)
    }
    @Test fun storedCopyCorruptionRollsBackBeforePublication(){
        var published=false;var rolledBack=false
        assertThrows(IllegalArgumentException::class.java){ExportCommit.run(Cancel(),create={Unit},write={ExternalOriginalGuard.verifyCopy("0".repeat(64),"corrupted".byteInputStream())},publish={published=true},rollback={rolledBack=true})}
        assertFalse(published);assertTrue(rolledBack)
    }
    @Test fun digestReadFailureIsNotAValidFingerprint(){
        var closed=false
        val input=object:java.io.InputStream(){override fun read():Int=throw IOException("io");override fun close(){closed=true}}
        assertThrows(IOException::class.java){ExternalOriginalGuard.digest(input)};assertTrue(closed)
    }
}
