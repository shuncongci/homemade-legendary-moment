package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ExternalMetadataTransferTest {
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("external-metadata-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun segment(marker:Int,p:ByteArray)=ByteArrayOutputStream().also{DataOutputStream(it).apply{writeByte(255);writeByte(marker);writeShort(p.size+2);write(p)}}.toByteArray()
    private fun source(privateBytes:ByteArray="private-capture".toByteArray(),trailer:ByteArray=byteArrayOf()):ByteArray {val j=CaptureMetadataFixtures.jpeg;return j.copyOfRange(0,2)+segment(0xe1,"Exif\u0000\u0000".toByteArray()+CaptureMetadataFixtures.tiff())+segment(0xe7,privateBytes)+segment(0xfe,"capture-comment".toByteArray())+j.copyOfRange(2,j.size)+trailer}
    private fun checkExport(encoded:ByteArray,badge:Boolean=true,trailer:ByteArray=byteArrayOf())=work{d->
        val raw=source(trailer=trailer);val original=File(d,"source").apply{writeBytes(raw)};val plain=File(d,"encoded").apply{writeBytes(encoded)};val out=File(d,"out")
        val p=ExternalMetadataTransfer.prepare(original,16,12,badge,Cancel());assertFalse(p.verified);assertFalse(p.deletionReady)
        ExternalMetadataTransfer.write(plain,out,p,16,12,Cancel());assertTrue(p.verified);assertEquals(trailer.isEmpty(),p.deletionReady)
        val actual=CaptureExif(CaptureMetadataContainer.readExif(out).exif!!);assertEquals(if(badge)1 else 0,actual.value(true,0x88b0)!!.single().toInt() and 1)
        val bundle=PreservedMetadata.read(original,Cancel());assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()))
        val restored=PreservedMetadata.unarchive(PreservedMetadata.readArchive(out,Cancel()));assertTrue(restored.any{it.kind=="J:e7" && String(it.bytes)=="private-capture"})
        assertArrayEquals(raw,original.readBytes());assertArrayEquals(encoded,plain.readBytes())
        val before=CaptureExif(bundle.exif!!);for(tag in listOf(0x927c,0xc123,0xa434))assertArrayEquals(before.value(true,tag),actual.value(true,tag))
        assertArrayEquals(before.exif().single{it.tag==0x927c}.raw,actual.exif().single{it.tag==0x927c}.raw)
    }
    @Test fun jpegKeepsPrivateMetadataAndBadge(){checkExport(CaptureMetadataFixtures.jpeg)}
    @Test fun pngKeepsPrivateMetadataAndBadge(){checkExport(CaptureMetadataFixtures.png)}
    @Test fun webpKeepsPrivateMetadataAndBadge(){checkExport(CaptureMetadataFixtures.webp)}
    @Test fun losslessKeepsPrivateMetadataAndBadge(){checkExport(CaptureMetadataFixtures.losslessWebp)}
    @Test fun hdrKeepsPrivateMetadataAndBadge(){checkExport(ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray())}
    @Test fun disabledBadgeIsReadBack(){checkExport(CaptureMetadataFixtures.jpeg,false)}
    @Test fun opaqueTrailerStillPreventsDeletion(){checkExport(CaptureMetadataFixtures.jpeg,trailer="opaque-video".toByteArray())}
    @Test fun supportedPrivatePacketsNoLongerImplyLoss()=work{d->val src=File(d,"source").apply{writeBytes(source())};assertTrue(CaptureMetadataContainer.readExif(src).otherMetadata);val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());assertTrue(p.complete);assertFalse(p.deletionReady)}
    @Test fun sourceWithNoExifStillGetsBadge()=work{d->val src=File(d,"source").apply{writeBytes(CaptureMetadataFixtures.jpeg)};val out=File(d,"out");val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());ExternalMetadataTransfer.write(src,out,p,16,12,Cancel());assertTrue(p.deletionReady);assertEquals(1,CaptureExif(CaptureMetadataContainer.readExif(out).exif!!).value(true,0x88b0)!![0].toInt())}
    @Test fun incompletePlatformFallbackNeverAllowsDeletion()=work{d->val src=File(d,"source").apply{writeBytes(CaptureMetadataFixtures.jpeg)};val e=CaptureExif.empty();val p=ExternalMetadataTransfer.fallback(e.normalized(16,12,XiaomiLeicaBadge.patches(e,true)),true);ExternalMetadataTransfer.write(src,File(d,"out"),p,16,12,Cancel());assertTrue(p.verified);assertFalse(p.deletionReady)}
    @Test fun cancelledWriteCannotEarnDeletion()=work{d->val src=File(d,"source").apply{writeBytes(source())};val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());val out=File(d,"out");assertThrows(CancellationException::class.java){ExternalMetadataTransfer.write(src,out,p,16,12,Cancel().also{it.cancelled=true})};assertFalse(p.deletionReady);assertFalse(out.exists())}
    @Test fun missingEncodedFileCannotEarnDeletion()=work{d->val src=File(d,"source").apply{writeBytes(source())};val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());val out=File(d,"out");assertThrows(FileNotFoundException::class.java){ExternalMetadataTransfer.write(File(d,"missing"),out,p,16,12,Cancel())};assertFalse(p.deletionReady);assertFalse(out.exists())}
    @Test fun existingTargetIsNeverDeletedOrOverwritten()=work{d->val src=File(d,"source").apply{writeBytes(source())};val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());val out=File(d,"out").apply{writeText("user data")};assertThrows(IllegalStateException::class.java){ExternalMetadataTransfer.write(src,out,p,16,12,Cancel())};assertEquals("user data",out.readText());assertFalse(p.deletionReady)}
    @Test fun malformedMetadataFailsClosed():Unit=work{d->val src=File(d,"source").apply{writeBytes(source().copyOf(30))};assertThrows(Exception::class.java){ExternalMetadataTransfer.prepare(src,16,12,true,Cancel())}}
    @Test fun unknownSourceDoesNotClaimCompleteness():Unit=work{d->val src=File(d,"source").apply{writeText("unknown format")};assertThrows(Exception::class.java){ExternalMetadataTransfer.prepare(src,16,12,true,Cancel())}}
    @Test fun verifiedMetadataStillRequiresPublishedOutput(){var removed=false;val result=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,false,Cancel(),{true},{true},{true},{removed=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(removed);assertEquals(OriginalAction.KEPT,result.action)}
    @Test fun verifiedMetadataStillRequiresOriginalFingerprint(){var removed=false;val result=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,true,Cancel(),{true},{false},{true},{removed=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(removed);assertEquals(OriginalAction.CHANGED,result.action)}
    @Test fun verifiedMetadataStillRequiresOutputFingerprint(){var removed=false;val result=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,true,Cancel(),{true},{true},{false},{removed=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(removed);assertEquals(OriginalAction.FAILED,result.action)}
    @Test fun defaultKeepStillWins(){var removed=false;ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.KEEP,true,Cancel(),{true},{true},{true},{removed=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(removed)}
    @Test fun onlyVerifiedCompleteMetadataCanReachSimulatedRemoval()=work{d->val src=File(d,"source").apply{writeBytes(source())};val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());ExternalMetadataTransfer.write(src,File(d,"out"),p,16,12,Cancel());var calls=0;check(p.deletionReady);val result=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,true,Cancel(),{true},{true},{true},{calls++;OriginalOutcome(OriginalAction.DELETED,"synthetic callback only")});assertEquals(1,calls);assertEquals(OriginalAction.DELETED,result.action);assertTrue(src.exists())}
}
