package xyz.shuncongci.leica

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoImportCommitTest {
    private fun fixture(test:(File,EditSession,File)->Unit){
        val dir=Files.createTempDirectory("photo-import-").toFile()
        try{
            val old=File(dir,"old.image").apply{writeBytes(byteArrayOf(1,2,3,4))}
            val next=File(dir,"new.image").apply{writeBytes(byteArrayOf(9,8,7,6))}
            val s=EditSession(File(dir,"edit"));s.importSource(old);s.ui(mapOf("mode" to "old-mode"));s.job("render")
            s.store.update(blobs=mapOf("result" to {it.writeText("old-result")}))
            test(dir,s,next)
        }finally{dir.deleteRecursively()}
    }
    private fun unchanged(dir:File,before:ByteArray){
        val store=File(dir,"edit");assertArrayEquals(before,File(store,"latest.properties").readBytes())
        val reopened=EditSession(store);assertArrayEquals(byteArrayOf(1,2,3,4),reopened.source()!!.readBytes())
        assertEquals("old-mode",reopened.ui()["mode"]);assertEquals("render",reopened.store.get("job.kind"));assertEquals("old-result",reopened.store.file("result")!!.readText())
    }
    @Test fun dimensionFailureCannotReplacePreviousEdit()=fixture{dir,s,next->
        val before=File(dir,"edit/latest.properties").readBytes()
        assertThrows(IllegalArgumentException::class.java){s.importSource(next){DngDimensions.Primary(4000,3000,setOf(4000 to 3000)).requireFull(640,480)}}
        unchanged(dir,before)
    }
    @Test fun previewFailureCannotReplacePreviousEdit()=fixture{dir,s,next->
        val before=File(dir,"edit/latest.properties").readBytes()
        assertThrows(IllegalArgumentException::class.java){s.importSource(next){throw IllegalArgumentException("decoder failed")}}
        unchanged(dir,before)
    }
    @Test fun cancellationImmediatelyBeforeCommitKeepsPrevious()=fixture{dir,s,next->
        val before=File(dir,"edit/latest.properties").readBytes()
        assertThrows(CancellationException::class.java){s.importSource(next){throw CancellationException("cancel")}}
        unchanged(dir,before)
    }
    @Test fun sourceCopyFailureKeepsPrevious()=fixture{dir,s,_->
        val before=File(dir,"edit/latest.properties").readBytes()
        assertThrows(java.io.IOException::class.java){s.importSource(File(dir,"missing.image"))}
        unchanged(dir,before)
    }
    @Test fun successfulImportCommitsOnceAndPreservesSourceBytes()=fixture{dir,s,next->
        var called=0;s.importSource(next){called++;assertArrayEquals(byteArrayOf(1,2,3,4),s.source()!!.readBytes())}
        assertEquals(1,called);assertArrayEquals(next.readBytes(),EditSession(File(dir,"edit")).source()!!.readBytes())
        assertArrayEquals(byteArrayOf(9,8,7,6),next.readBytes());assertNull(s.store.file("result"));assertTrue(s.ui().isEmpty());assertEquals("",s.store.get("job.kind"))
    }
    @Test fun fatalDecodeFailureIsNotConvertedToSuccessfulImport()=fixture{dir,s,next->
        val before=File(dir,"edit/latest.properties").readBytes()
        assertThrows(OutOfMemoryError::class.java){s.importSource(next){throw OutOfMemoryError("synthetic, no allocation")}}
        unchanged(dir,before)
    }
}
