package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Byte-copy/settling tests use synthetic marker bytes, not a claim about Android camera codecs. */
class ExternalPhotoInputTest {
    private val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),4,5,6,0xff.toByte(),0xd9.toByte())
    private fun p(bytes:ByteArray)=ExternalPhoto("new-key","file:///new","NEW.jpg",bytes.size.toLong(),10)
    private fun <T> temporary(body:(File)->T):T {val dir=Files.createTempDirectory("external-input-test").toFile();return try{body(dir)}finally{dir.deleteRecursively()}}
    @Test fun validatesCompleteJpegMarkers()=temporary{d->val f=File(d,"test");f.writeBytes(jpeg);assertTrue(ExternalPhotoInput.completeJpeg(f))}
    @Test fun rejectsMissingJpegTail()=temporary{d->val f=File(d,"test");f.writeBytes(jpeg.dropLast(2).toByteArray());assertFalse(ExternalPhotoInput.completeJpeg(f))}
    @Test fun rejectsNonJpegHead()=temporary{d->val f=File(d,"test");f.writeBytes(byteArrayOf(1,2,0xff.toByte(),0xd9.toByte()));assertFalse(ExternalPhotoInput.completeJpeg(f))}
    @Test fun exactPrivateCopyDoesNotModifyInput()=temporary{d->val input=jpeg.clone();val photo=p(input);val out=ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{ByteArrayInputStream(input)},waitStable={error("Stable input must not wait")});assertArrayEquals(jpeg,out.readBytes());assertArrayEquals(jpeg,input)}
    @Test fun retriesOnlySameNewPhotoWhenMetadataChangesDuringCopy()=temporary{d->
        val later=jpeg.copyOfRange(0,3)+byteArrayOf(7,8,9)+jpeg.copyOfRange(3,jpeg.size)
        var current=p(jpeg);var bytes=jpeg;var opens=0;var waits=0
        val out=ExternalPhotoInput.stage(d,current,Cancel(),{current},{
            val saved=bytes;opens++;if(opens==1){bytes=later;current=p(later).copy(modified=20)};ByteArrayInputStream(saved)
        },waitStable={waits++})
        assertArrayEquals(later,out.readBytes());assertEquals(2,opens);assertEquals(1,waits);assertEquals(1,d.listFiles()!!.size)
    }
    @Test fun incompleteJpegDoesNotReachProcessingAndRetriesAreBounded()=temporary{d->
        val partial=jpeg.dropLast(2).toByteArray();val photo=p(partial);var opens=0
        try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{opens++;ByteArrayInputStream(partial)},waitStable={});fail()}catch(_:IllegalStateException){}
        assertEquals(5,opens);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun permissionOrIoFailureDoesNotRetryOrSucceed()=temporary{d->var opens=0;val photo=p(jpeg)
        try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{opens++;throw IOException("disk failure")},waitStable={});fail()}catch(_:IOException){}
        assertEquals(1,opens);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun cancellationDuringCopyLeavesNoPartialSource()=temporary{d->val c=Cancel();val photo=p(jpeg)
        try{ExternalPhotoInput.stage(d,photo,c,{photo},{c.cancelled=true;ByteArrayInputStream(jpeg)},waitStable={});fail()}catch(_:CancellationException){}
        assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun alreadyCancelledNeverOpensSource()=temporary{d->val c=Cancel().apply{cancelled=true};val photo=p(jpeg)
        try{ExternalPhotoInput.stage(d,photo,c,{error("must not observe")},{error("must not open")},waitStable={});fail()}catch(_:CancellationException){}
        assertEquals(0,d.listFiles()?.size?:0)
    }
    @Test fun cannotSwitchToAnotherSourceKey()=temporary{d->val photo=p(jpeg)
        try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo.copy(key="old-library-photo")},{error("must not open old photo")},waitStable={});fail()}catch(_:IllegalArgumentException){}
        assertEquals(0,d.listFiles()?.size?:0)
    }
    @Test fun removedNewPhotoDoesNotTriggerLibrarySearch()=temporary{d->
        try{ExternalPhotoInput.stage(d,p(jpeg),Cancel(),{null},{error("must not open")},waitStable={});fail()}catch(_:java.io.FileNotFoundException){}
        assertEquals(0,d.listFiles()?.size?:0)
    }
}
