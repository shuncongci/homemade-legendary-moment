package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame

class HdrResultStoreTest {
    private fun sample():Pair<Planes,Frame>{val p=Planes(7,5);for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=(c*53+i).toByte();return p to Frame(7,5,3,FloatArray(105){it/7f})}
    @Test fun roundTripExact(){val dir=Files.createTempDirectory("hdr-pair").toFile();try{val(p,h)=sample();val f=java.io.File(dir,"pair");HdrResultStore.write(f,p,h,Cancel());val(a,b)=HdrResultStore.read(f,7,5,Cancel());for(c in 0..2)assertArrayEquals(p.plane(c),a.plane(c));assertArrayEquals(h.data,b.data,0f)}finally{dir.deleteRecursively()}}
    @Test fun snapshotDoesNotAlias(){val dir=Files.createTempDirectory("hdr-owned").toFile();try{val(p,h)=sample();val expected=h.data.copyOf();val f=java.io.File(dir,"pair");HdrResultStore.write(f,p,h,Cancel());h.data.fill(0f);assertArrayEquals(expected,HdrResultStore.read(f,7,5,Cancel()).second.data,0f)}finally{dir.deleteRecursively()}}
    @Test fun wrongDimensionsRejected(){val dir=Files.createTempDirectory("hdr-shape").toFile();try{val(p,h)=sample();val f=java.io.File(dir,"pair");HdrResultStore.write(f,p,h,Cancel());try{HdrResultStore.read(f,5,7,Cancel());fail()}catch(_:IllegalStateException){}}finally{dir.deleteRecursively()}}
    @Test fun truncatedRejected(){val dir=Files.createTempDirectory("hdr-short").toFile();try{val f=java.io.File(dir,"pair");f.writeBytes(byteArrayOf(1,2));try{HdrResultStore.read(f,7,5,Cancel());fail()}catch(_:IllegalStateException){}}finally{dir.deleteRecursively()}}
    @Test fun cancelWriteCleansPartial(){val dir=Files.createTempDirectory("hdr-cancel").toFile();try{val(p,h)=sample();val f=java.io.File(dir,"pair");try{HdrResultStore.write(f,p,h,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){};assertFalse(f.exists())}finally{dir.deleteRecursively()}}
    @Test fun resultSidecarsIndependent(){val a=java.io.File("a.png");val b=java.io.File("b.png");assertNotEquals(HdrResultStore.pairFile(a),HdrResultStore.pairFile(b));assertNotEquals(HdrResultStore.pairFile(a),HdrResultStore.jpegFile(a))}
    @Test fun unsupportedFallbackButMemoryMustFail(){assertTrue(HdrAutoPolicy.canFallback(IllegalArgumentException()));assertTrue(HdrAutoPolicy.canFallback(IllegalStateException()));assertFalse(HdrAutoPolicy.canFallback(OutOfMemoryError()))}
    @Test fun cancellationAndIoNeverFallback(){assertFalse(HdrAutoPolicy.canFallback(CancellationException()));assertFalse(HdrAutoPolicy.canFallback(IOException()));assertFalse(HdrAutoPolicy.canFallback(AssertionError()))}
    @Test fun wrappedOomNeverBecomesSdr(){assertFalse(HdrAutoPolicy.canFallback(IllegalStateException("encode",OutOfMemoryError("marker only"))))}
    @Test fun deeplyWrappedOomNeverBecomesSdr(){assertFalse(HdrAutoPolicy.canFallback(IllegalArgumentException("crop",RuntimeException(IllegalStateException(OutOfMemoryError("marker only"))))))}
    @Test fun wrappedIoNeverBecomesSdr(){assertFalse(HdrAutoPolicy.canFallback(IllegalStateException("write",IOException("disk"))))}
    @Test fun wrappedCancellationNeverBecomesSdr(){assertFalse(HdrAutoPolicy.canFallback(IllegalStateException("stop",CancellationException())))}
    @Test fun cyclicCauseDoesNotHangFallbackClassification(){val a=IllegalStateException("a");val b=IllegalArgumentException("b",a);a.initCause(b);assertTrue(HdrAutoPolicy.canFallback(a))}
    @Test fun modeLabels(){assertEquals("SDR",HdrAutoPolicy.label(false));assertTrue(HdrAutoPolicy.label(false,"内存").contains("已转 SDR"));assertTrue(HdrAutoPolicy.label(true).contains("HDR"))}
}
