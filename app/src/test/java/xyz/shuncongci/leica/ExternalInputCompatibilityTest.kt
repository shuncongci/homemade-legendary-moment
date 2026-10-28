package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.SyncFailedException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Synthetic framing/IO regressions, not Android decoder, OEM provider or camera acceptance. */
class ExternalInputCompatibilityTest {
    private val jpeg=CaptureMetadataFixtures.jpeg
    private fun b(vararg v:Int)=ByteArray(v.size){v[it].toByte()}
    private val soi=b(255,216)
    private val eoi=b(255,217)
    private val sof=b(255,192,0,11,8,0,1,0,1,1,1,17,0)
    private val sos=b(255,218,0,8,1,1,0,0,63,0)
    private val framed get()=soi+sof+sos+b(17,34,255,0,255,208,51)+eoi
    private fun <T> temporary(body:(File)->T):T {val dir=Files.createTempDirectory("external-compatible").toFile();return try{body(dir)}finally{dir.deleteRecursively()}}
    private fun end(bytes:ByteArray):Long?=temporary{d->File(d,"camera.jpg").let{it.writeBytes(bytes);JpegCompletion.primaryEnd(it)}}
    private fun p(size:Int=jpeg.size,name:String="NEW.jpg")=ExternalPhoto("new-key","file:///new",name,size.toLong(),10,created=20)
    private fun failure(body:()->Unit):IllegalStateException {try{body();fail("Must reject")}catch(e:IllegalStateException){return e};error("unreachable")}
    private fun encode(out:OutputStream,c:Cancel=Cancel(),read:(NativeImageMath.Box)->IntArray={box->IntArray(box.w*box.h){0xff2468ac.toInt()}})=CompositionExport.writeSized(out,16,12,CompositionGeometry.FULL,16,12,c,read)
    private class Borrowed:OutputStream(){val bytes=ByteArrayOutputStream();var closes=0;var flushes=0;var failWrite=false;var failFlush=false
        override fun write(v:Int){if(failWrite)throw IOException("write failed");bytes.write(v)}
        override fun write(v:ByteArray,off:Int,len:Int){if(failWrite)throw IOException("write failed");bytes.write(v,off,len)}
        override fun close(){closes++}
        override fun flush(){flushes++;if(failFlush)throw IOException("flush failed")}
    }

    @Test fun realFixturePrimaryEndsAtEof(){assertEquals(jpeg.size.toLong(),end(jpeg))}
    @Test fun motionPayloadDoesNotInvalidatePrimary(){assertEquals(jpeg.size.toLong(),end(jpeg+"motion-video".toByteArray()))}
    @Test fun gainJpegDoesNotReplacePrimaryEnd(){assertEquals(jpeg.size.toLong(),end(jpeg+jpeg))}
    @Test fun embeddedThumbnailEndCannotCompleteTruncatedPrimary(){assertNull(end(soi+b(255,225,0,8,255,216,1,2,255,217)+sof+sos+b(17)))}
    @Test fun appPayloadEndIgnoredInCompletePrimary(){val data=soi+b(255,225,0,6,255,217,255,216)+sof+sos+b(17)+eoi;assertEquals(data.size.toLong(),end(data))}
    @Test fun outOfBoundsSegmentCannotBeCompletedByTail(){assertNull(end(soi+b(255,225,127,255)+sof+sos+b(17)+eoi))}
    @Test fun missingFrameRejected(){assertNull(end(soi+sos+b(17)+eoi))}
    @Test fun missingScanRejected(){assertNull(end(soi+sof+eoi))}
    @Test fun markersAloneAreNotAnImage(){assertNull(end(soi+eoi))}
    @Test fun truncatedEntropyRejected(){assertNull(end(framed.dropLast(2).toByteArray()))}
    @Test fun stuffedFfAndRestartRemainInScan(){assertEquals(framed.size.toLong(),end(framed))}
    @Test fun fillFfAcceptedBeforeEnd(){val data=framed.dropLast(2).toByteArray()+b(255,255,217);assertEquals(data.size.toLong(),end(data))}
    @Test fun multipleScansFramedWithoutTailAssumption(){val progressiveSof=sof.clone().also{it[1]=194.toByte()};val data=soi+progressiveSof+sos+b(17)+sos+b(34)+eoi;assertEquals(data.size.toLong(),end(data))}
    @Test fun emptyScanRejected(){assertNull(end(soi+sof+sos+eoi))}
    @Test fun invalidFrameLengthRejected(){assertNull(end(soi+sof.clone().also{it[3]=10}+sos+b(17)+eoi))}
    @Test fun invalidScanLengthRejected(){assertNull(end(soi+sof+sos.clone().also{it[3]=7}+b(17)+eoi))}
    @Test fun framingCancellationPropagates()=temporary{d->val f=File(d,"camera");f.writeBytes(jpeg);try{JpegCompletion.primaryEnd(f,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}

    @Test fun trailerRequiresTwoIdenticalCopiesAndKeepsEveryByte()=temporary{d->val bytes=jpeg+"motion".toByteArray();val photo=p(bytes.size);var reads=0;var waits=0
        val out=ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;ByteArrayInputStream(bytes)},waitStable={waits++})
        assertArrayEquals(bytes,out.readBytes());assertEquals(2,reads);assertEquals(1,waits);assertEquals(1,d.listFiles()!!.size)
    }
    @Test fun staleDeclaredSizeRequiresTwoFullIdenticalCopies()=temporary{d->val photo=p(jpeg.size-3);var reads=0;var waits=0
        val out=ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;ByteArrayInputStream(jpeg)},waitStable={waits++})
        assertArrayEquals(jpeg,out.readBytes());assertEquals(2,reads);assertEquals(1,waits)
    }
    @Test fun fewerBytesThanDeclaredNeverPassDespiteCompleteMain()=temporary{d->val photo=p(jpeg.size+1);var reads=0
        val error=failure{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;ByteArrayInputStream(jpeg)},waitStable={})}
        assertEquals(5,reads);assertTrue(error.message!!.contains("小于提供器声明"));assertTrue(error.message!!.contains("原片保留"));assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun sameSizeChangingTrailerCannotPassFingerprint()=temporary{d->val photo=p(jpeg.size+1);var reads=0
        failure{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{ByteArrayInputStream(jpeg+b(++reads))},waitStable={})}
        assertEquals(5,reads);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun trailerMaySettleButNeedsConsecutiveEqualCopies()=temporary{d->val photo=p(jpeg.size+1);var reads=0;var waits=0
        val out=ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;ByteArrayInputStream(jpeg+b(if(reads==1)1 else 2))},waitStable={waits++})
        assertArrayEquals(jpeg+b(2),out.readBytes());assertEquals(3,reads);assertEquals(2,waits)
    }
    @Test fun metadataChangeInvalidatesFirstFingerprint()=temporary{d->val bytes=jpeg+b(1);var current=p(bytes.size);var reads=0;var waits=0
        val out=ExternalPhotoInput.stage(d,current,Cancel(),{current},{reads++;ByteArrayInputStream(bytes)},waitStable={waits++;if(waits==1)current=current.copy(modified=11)})
        assertArrayEquals(bytes,out.readBytes());assertEquals(3,reads);assertEquals(3,waits)
    }
    @Test fun pendingNeverOpensAndWaitRemainsBounded()=temporary{d->val photo=p().copy(pending=true);var waits=0
        failure{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{error("must not open")},waitStable={waits++})}
        assertEquals(5,waits);assertEquals(0,d.listFiles()?.size?:0)
    }
    @Test fun nonJpegMismatchNotRelaxed()=temporary{d->val photo=p(jpeg.size-1,"NEW.png");var reads=0
        failure{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;ByteArrayInputStream(jpeg)},waitStable={})}
        assertEquals(5,reads);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun cancellationDuringFingerprintWaitCleansFirstCopy()=temporary{d->val bytes=jpeg+b(1);val photo=p(bytes.size);val c=Cancel();var reads=0
        try{ExternalPhotoInput.stage(d,photo,c,{photo},{reads++;ByteArrayInputStream(bytes)},waitStable={c.cancelled=true});fail()}catch(_:CancellationException){}
        assertEquals(1,reads);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun secondReadIoFailureIsNotSwallowedOrRetried()=temporary{d->val photo=p(jpeg.size-1);var reads=0
        try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{if(++reads==2)throw IOException("provider disconnected");ByteArrayInputStream(jpeg)},waitStable={});fail()}catch(e:IOException){assertEquals("provider disconnected",e.message)}
        assertEquals(2,reads);assertEquals(0,d.listFiles()!!.size)
    }
    @Test fun changedUriCannotBecomeInput()=temporary{d->val photo=p();try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo.copy(uri="file:///old")},{error("must not open")},waitStable={});fail()}catch(_:IllegalArgumentException){}}
    @Test fun changedNameCannotBecomeInput()=temporary{d->val photo=p();try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo.copy(name="OLD.jpg")},{error("must not open")},waitStable={});fail()}catch(_:IllegalArgumentException){}}
    @Test fun changedCreationIdentityRemainsRejected()=temporary{d->val photo=p();try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo.copy(created=19)},{error("must not open")},waitStable={});fail()}catch(_:IllegalArgumentException){}}
    @Test fun sourceSecurityFailureNeverBecomesSuccess()=temporary{d->val photo=p();var reads=0;try{ExternalPhotoInput.stage(d,photo,Cancel(),{photo},{reads++;throw SecurityException("revoked")},waitStable={});fail()}catch(_:SecurityException){};assertEquals(1,reads);assertEquals(0,d.listFiles()!!.size)}

    @Test fun pngLeavesRealDescriptorOpenForSync()=temporary{d->val file=File(d,"encoded.png");FileOutputStream(file).use{out->encode(out);assertTrue(out.fd.valid());out.fd.sync()};assertTrue(file.length()>32)}
    @Test fun successfulPngFlushesButDoesNotClose(){val out=Borrowed();encode(out);assertEquals(0,out.closes);assertEquals(1,out.flushes);assertArrayEquals(b(0,0,0,0,73,69,78,68,174,66,96,130),out.bytes.toByteArray().takeLast(12).toByteArray())}
    @Test fun pngWriteFailurePropagatesWithoutClosingCaller(){val out=Borrowed().apply{failWrite=true};try{encode(out);fail()}catch(e:IOException){assertEquals("write failed",e.message)};assertEquals(0,out.closes)}
    @Test fun pngFlushFailurePropagatesWithoutClosingCaller(){val out=Borrowed().apply{failFlush=true};try{encode(out);fail()}catch(e:IOException){assertEquals("flush failed",e.message)};assertEquals(0,out.closes)}
    @Test fun pngReaderFailureDoesNotFinishOrClose(){val out=Borrowed();var reads=0;try{encode(out){box->if(++reads==2)throw IOException("pixels unavailable");IntArray(box.w*box.h){-1}};fail()}catch(_:IOException){};assertEquals(0,out.closes);assertEquals(0,out.flushes);assertEquals(0,out.bytes.size())}
    @Test fun cancelledPngDoesNotCloseBorrowedStream(){val out=Borrowed();val c=Cancel();var reads=0;try{encode(out,c){box->if(++reads==2)c.cancelled=true;IntArray(box.w*box.h){-1}};fail()}catch(_:CancellationException){};assertEquals(0,out.closes);assertEquals(0,out.flushes)}
    @Test fun plainJpegStillEligibleForMetadataCompleteness()=temporary{d->val f=File(d,"source.image");f.writeBytes(framed);assertTrue(ExternalCaptureMetadata.prepare(f,File(d,"metadata"),1,1,false,Cancel()).complete)}
    @Test fun opaqueTrailerWithoutXmpAlwaysPreventsAutoDeletion()=temporary{d->val f=File(d,"source.image");val bytes=framed+"opaque-vendor-data".toByteArray();f.writeBytes(bytes);assertFalse(ExternalCaptureMetadata.prepare(f,File(d,"metadata"),1,1,false,Cancel()).complete);assertArrayEquals(bytes,f.readBytes())}
    @Test fun realSyncFailureStillRollsBack(){var published=false;var rolledBack=false;try{ExportCommit.run(Cancel(),create={"pending"},write={throw SyncFailedException("genuine sync failure")},publish={published=true},rollback={rolledBack=true});fail()}catch(_:SyncFailedException){};assertFalse(published);assertTrue(rolledBack)}
}
