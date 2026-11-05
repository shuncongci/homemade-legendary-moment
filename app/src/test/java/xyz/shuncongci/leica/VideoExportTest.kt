package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SyncFailedException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Pure publication callbacks and reported track formats, NOT MediaStore/codec device tests. */
class VideoExportTest {
    private fun track(index:Int,id:Int=1,mime:String=if(index==0)"video/dolby-vision"else "video/hevc")=
        VideoTracks.Track(index,id,mime,3840,2160,8206100,-1)
    private fun inv(vararg tracks:VideoTracks.Track)=VideoTracks.Inventory(tracks.toList(),null)
    @Test fun reportedXiaomiAliasIsCandidateNotTwoIndependentPictures(){
        val i=inv(track(0),track(1),VideoTracks.Track(2,2,"audio/mp4a-latm",0,0,8208300))
        assertEquals(setOf(1),i.dolbyAliasIds);assertTrue(i.summary.contains("疑似"));assertTrue(i.summary.contains("同ID"))
        assertTrue(i.processingBlockReason!!.contains("应用能力限制"));assertTrue(i.processingBlockReason!!.contains("尚未尝试设备编码"))
    }
    @Test fun candidateDoesNotPermitAutomaticHevcFallback(){val i=inv(track(0),track(1));for(n in listOf(null,0,1))assertThrows(IllegalArgumentException::class.java){i.choose(n)}}
    @Test fun differentIdsNotAlias(){assertTrue(inv(track(0),track(1,id=2)).dolbyAliasIds.isEmpty())}
    @Test fun missingIdsNotAlias(){assertTrue(inv(track(0,id=-1),track(1,id=-1)).dolbyAliasIds.isEmpty())}
    @Test fun differentSizeNotAlias(){assertTrue(inv(track(0),track(1).copy(width=1920)).dolbyAliasIds.isEmpty())}
    @Test fun differentTimeNotAlias(){assertTrue(inv(track(0),track(1).copy(durationUs=8206000)).dolbyAliasIds.isEmpty())}
    @Test fun zeroSizeNotAlias(){assertTrue(inv(track(0).copy(width=0),track(1).copy(width=0)).dolbyAliasIds.isEmpty())}
    @Test fun tripleViewsNotAliasProof(){assertTrue(inv(track(0),track(1),track(2)).dolbyAliasIds.isEmpty())}
    @Test fun ordinaryHevcNotBlocked(){assertNull(inv(track(0,mime="video/hevc")).processingBlockReason)}
    @Test fun dolbySingleStillUnsupported(){assertNotNull(inv(track(0)).processingBlockReason)}
    @Test fun duplicateHevcNotDolbyAlias(){assertTrue(inv(track(0,mime="video/hevc"),track(1)).dolbyAliasIds.isEmpty())}
    @Test fun playPauseAreDistinctVectors(){assertNotEquals(WorkspaceIcons.spec("PLAY"),WorkspaceIcons.spec("PAUSE"));assertTrue(WorkspaceIcons.spec("PLAY").outline.endsWith("Z"));assertTrue(WorkspaceIcons.spec("PAUSE").outline.endsWith("Z"))}
    @Test fun galleryPathIsVideoNotPictures(){assertEquals("Movies/传奇一瞬/",VideoExportCommit.FOLDER)}
    private class Store {
        val events=mutableListOf<String>();var data=byteArrayOf();var published=false;var deleted=false
        fun save(file:File,c:Cancel=Cancel(),write:()->Unit={data=file.readBytes()},
                 read:()->InputStream={data.inputStream()},publish:()->Unit={published=true},rollback:()->Unit={deleted=true})=
            VideoExportCommit.save(file,c,create={events+="create";7},writeAndSync={assertEquals(7,it);events+="write-sync";write()},
                read={events+="read";read()},publish={events+="publish";publish()},rollback={events+="rollback";rollback()})
    }
    private fun fixture(action:(File)->Unit){val d=Files.createTempDirectory("video-export-test").toFile();try{val f=File(d,"complete.mp4");f.writeBytes(ByteArray(600000){(it%251).toByte()});action(f)}finally{d.deleteRecursively()}}
    @Test fun correctBytesPublishAfterReadback()=fixture{f->val s=Store();assertEquals(7,s.save(f));assertEquals(listOf("create","write-sync","read","publish"),s.events);assertTrue(s.published);assertFalse(s.deleted)}
    @Test fun incompleteBytesDoNotPublish()=fixture{f->val s=Store();assertThrows(IllegalStateException::class.java){s.save(f,write={s.data=f.readBytes().copyOf(10)})};assertFalse(s.published);assertTrue(s.deleted)}
    @Test fun equalLengthCorruptionDoNotPublish()=fixture{f->val s=Store();assertThrows(IllegalStateException::class.java){s.save(f,write={s.data=f.readBytes();s.data[0]=99})};assertFalse(s.published);assertTrue(s.deleted)}
    @Test fun syncFailureRollsBack()=fixture{f->val s=Store();assertThrows(SyncFailedException::class.java){s.save(f,write={throw SyncFailedException("sync")})};assertTrue(s.deleted);assertFalse(s.published)}
    @Test fun readFailureRollsBack()=fixture{f->val s=Store();assertThrows(IOException::class.java){s.save(f,read={throw IOException("read")})};assertTrue(s.deleted);assertFalse(s.published)}
    @Test fun publishFailureRollsBack()=fixture{f->val s=Store();assertThrows(IOException::class.java){s.save(f,publish={throw IOException("publish")})};assertTrue(s.deleted);assertFalse(s.published)}
    @Test fun cancelBeforeCreateTouchesNothing()=fixture{f->val s=Store();assertThrows(CancellationException::class.java){s.save(f,Cancel().apply{cancelled=true})};assertTrue(s.events.isEmpty())}
    @Test fun cancelDuringWriteRollsBack()=fixture{f->val s=Store();val c=Cancel();assertThrows(CancellationException::class.java){s.save(f,c,write={s.data=f.readBytes();c.cancelled=true})};assertTrue(s.deleted);assertFalse(s.published)}
    @Test fun cancelAtReadbackRollsBack()=fixture{f->val s=Store();val c=Cancel();assertThrows(CancellationException::class.java){s.save(f,c,read={c.cancelled=true;s.data.inputStream()})};assertTrue(s.deleted);assertFalse(s.published)}
    @Test fun afterPublishCancellationDoesNotDeleteSuccessfulResult()=fixture{f->val s=Store();val c=Cancel();s.save(f,c,publish={s.published=true;c.cancelled=true});assertTrue(s.published);assertFalse(s.deleted)}
    @Test fun cleanupFailureKeepsOriginalAndSuppressedError()=fixture{f->val s=Store();val e=assertThrows(IOException::class.java){s.save(f,write={throw IOException("write")},rollback={throw IOException("cleanup")})};assertEquals("write",e.message);assertEquals("cleanup",e.suppressed.single().message)}
    @Test fun emptySourceDoesNotCreate()=fixture{f->f.writeBytes(byteArrayOf());val s=Store();assertThrows(IllegalArgumentException::class.java){s.save(f)};assertTrue(s.events.isEmpty())}
    @Test fun missingSourceDoesNotCreate()=fixture{f->val s=Store();assertThrows(IllegalArgumentException::class.java){s.save(File(f.parentFile,"missing.mp4"))};assertTrue(s.events.isEmpty())}
    @Test fun copyPreservesAllBytesAndSource()=fixture{f->val before=f.readBytes();val out=ByteArrayOutputStream();VideoExportCommit.copy(f,out,Cancel());assertArrayEquals(before,out.toByteArray());assertArrayEquals(before,f.readBytes())}
    @Test fun exportFailureKeepsSource()=fixture{f->val before=f.readBytes();assertThrows(IOException::class.java){Store().save(f,write={throw IOException("write")})};assertArrayEquals(before,f.readBytes())}
    @Test fun digestClosesStreamOnCancel(){var closed=false;val input=object:InputStream(){override fun read()=0;override fun close(){closed=true}};assertThrows(CancellationException::class.java){VideoExportCommit.digest(input,Cancel().apply{cancelled=true})};assertTrue(closed)}
}
