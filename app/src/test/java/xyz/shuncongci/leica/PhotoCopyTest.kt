package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException

class PhotoCopyTest {
    private fun directory()=Files.createTempDirectory("photo-copy-test").toFile()
    @Test fun preservesBytesAndCanReadRepeatedly(){val d=directory();try{val bytes=ByteArray(180000){(it%251).toByte()};val f=PhotoCopy.stage(d,{ByteArrayInputStream(bytes)});assertArrayEquals(bytes,f.readBytes());assertArrayEquals(bytes,f.readBytes());assertEquals(1,d.listFiles()!!.size)}finally{d.deleteRecursively()}}
    @Test fun emptySourceIsRejectedWithoutPartialFile(){val d=directory();try{try{PhotoCopy.stage(d,{ByteArrayInputStream(byteArrayOf())});fail()}catch(_:IllegalArgumentException){};assertEquals(0,d.listFiles()!!.size)}finally{d.deleteRecursively()}}
    @Test fun providerFailureLeavesNoPartialFile(){val d=directory();try{try{PhotoCopy.stage(d,{throw FileNotFoundException("ENOENT")});fail()}catch(_:FileNotFoundException){};assertEquals(0,d.listFiles()!!.size)}finally{d.deleteRecursively()}}
    @Test fun interruptedCopyClosesInputAndCleansPartial(){val d=directory();var closed=false;val input=object:ByteArrayInputStream(ByteArray(200000)){override fun close(){closed=true;super.close()}};var checks=0;try{try{PhotoCopy.stage(d,{input}){if(++checks==3)throw CancellationException()};fail()}catch(_:CancellationException){};assertTrue(closed);assertEquals(0,d.listFiles()!!.size)}finally{d.deleteRecursively()}}
    @Test fun separateImportsNeverOverwrite(){val d=directory();try{val a=PhotoCopy.stage(d,{ByteArrayInputStream(byteArrayOf(1,2))});val b=PhotoCopy.stage(d,{ByteArrayInputStream(byteArrayOf(3,4))});assertNotEquals(a,b);assertArrayEquals(byteArrayOf(1,2),a.readBytes());assertArrayEquals(byteArrayOf(3,4),b.readBytes())}finally{d.deleteRecursively()}}
    @Test fun midReadFailureClosesAndCleans(){val d=directory();var closed=false;val input=object:InputStream(){override fun read():Int=throw IOException("read failed");override fun close(){closed=true}};try{try{PhotoCopy.stage(d,{input});fail()}catch(_:IOException){};assertTrue(closed);assertEquals(0,d.listFiles()!!.size)}finally{d.deleteRecursively()}}
}
