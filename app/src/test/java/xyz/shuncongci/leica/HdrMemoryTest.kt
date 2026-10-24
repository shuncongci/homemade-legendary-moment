package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.math.*
import kotlin.random.Random
import xyz.shuncongci.leica.LegendaryMath.Frame

class HdrMemoryTest {
    private fun reject(block:()->Unit):String{try{block();fail("expected rejection")}catch(e:IllegalArgumentException){return e.message?:""};error("unreachable")}
    @Test fun nativeSizeNoLongerChargedToManagedDecode(){
        val heap=HdrMemory.decodeHeap(2048,1536)
        assertEquals(24L*2048*1536+16*HdrMemory.MIB,heap)
        assertTrue(HdrMemory.decodeNative(8192,6144)>heap)
        HdrMemory.check("decode",heap,192*HdrMemory.MIB)
    }
    @Test fun oldFalseRejectAtTwelveMpWith512MiB(){
        val old=HdrMath.memoryBytes(4000,3000,2048,1536)
        assertTrue(old>512*HdrMemory.MIB*3/4)
        HdrMemory.check("decode",HdrMemory.decodeHeap(2048,1536),400*HdrMemory.MIB,32*HdrMemory.MIB)
    }
    @Test fun longStageBudgets(){assertTrue(HdrMemory.renderHeap(10000,5000)>Int.MAX_VALUE);assertTrue(HdrMemory.encodeHeap(10000,5000)>Int.MAX_VALUE)}
    @Test fun badDimensionsRejected(){reject{HdrMemory.decodeHeap(0,1)};reject{HdrMemory.decodeNative(1,-1)};reject{HdrMemory.renderHeap(Int.MAX_VALUE,2)}}
    @Test fun genuineHeapShortageStillRejected(){val message=reject{HdrMemory.check("线性HDR景深",200*HdrMemory.MIB,128*HdrMemory.MIB)};assertTrue(message.contains("线性HDR景深"));assertTrue(message.contains("200MiB"));assertTrue(message.contains("128MiB"))}
    @Test fun noReserveOverflow(){reject{HdrMemory.check("test",1,10,20)};HdrMemory.check("test",0,10,20)}
    @Test fun reserveBoundary(){HdrMemory.check("test",100,116,16);reject{HdrMemory.check("test",101,116,16)}}
    @Test fun stagesAreNotSummed(){val w=1024;val h=768;assertTrue(HdrMemory.decodeHeap(w,h)+HdrMemory.renderHeap(w,h)>HdrMemory.renderHeap(w,h));HdrMemory.check("render",HdrMemory.renderHeap(w,h),128*HdrMemory.MIB)}
    @Test fun mappedSnapshotExactBytesAndCleanup(){val dir=Files.createTempDirectory("hdr-raw-test").toFile();try{
        HdrPixelBuffer(dir,8193){b->for(i in 0 until b.capacity())b.put(i,(i*37).toByte())}.use{p->for(i in 0..8192)assertEquals((i*37).toByte(),p.get(i));assertEquals(1,dir.listFiles()!!.size)}
        assertTrue(dir.listFiles()!!.isEmpty())
    }finally{dir.deleteRecursively()}}
    @Test fun failedSnapshotCleansFile(){val dir=Files.createTempDirectory("hdr-raw-fail").toFile();try{
        try{HdrPixelBuffer(dir,4096){throw IllegalStateException("copy failed")};fail()}catch(_:IllegalStateException){}
        assertTrue(dir.listFiles()!!.isEmpty())
    }finally{dir.deleteRecursively()}}
    @Test fun closedSnapshotCannotRead(){val dir=Files.createTempDirectory("hdr-raw-closed").toFile();try{
        val p=HdrPixelBuffer(dir,4096){it.put(0,42)};p.close();p.close();try{p.get(0);fail()}catch(_:IllegalStateException){}
    }finally{dir.deleteRecursively()}}
    @Test fun mapped96MiBDoesNotRequire96MiBHeap(){val dir=Files.createTempDirectory("hdr-raw-large").toFile();try{
        val size=96*1024*1024
        HdrPixelBuffer(dir,size){b->var i=0;while(i<size){b.put(i,(i/4096).toByte());i+=4096};b.put(size-1,77)}.use{p->assertEquals(77.toByte(),p.get(size-1));assertEquals(7.toByte(),p.get(7*4096))}
        assertTrue(dir.listFiles()!!.isEmpty())
    }finally{dir.deleteRecursively()}}
    @Test fun inPlaceColorBitIdenticalToImmutable(){val w=31;val h=23;val random=Random(233)
        val p=Planes(w,h);for(c in 0..2)random.nextBytes(p.plane(c))
        val s=Frame(w,h,3,FloatArray(w*h*3){random.nextFloat()*1.5f});val z=Frame(w,h,3,FloatArray(w*h*3){random.nextFloat()*8f})
        val before=s.data.copyOf();val expected=HdrMath.color(s,z,p,Cancel());val actual=HdrMath.colorInPlace(s,z,p,Cancel())
        assertSame(z,actual);assertArrayEquals(expected.data,actual.data,0f);assertArrayEquals(before,s.data,0f)
    }
    @Test fun inPlaceRequiresOwnedIndependentHdr(){val p=Planes(1,1);val f=Frame(1,1,3,floatArrayOf(1f,2f,3f));reject{HdrMath.colorInPlace(f,f,p,Cancel())}}
    @Test fun twoPassGainMapBitIdenticalToOldAlgorithm(){val w=41;val h=29;val random=Random(481)
        val p=Planes(w,h);for(c in 0..2)random.nextBytes(p.plane(c));val z=Frame(w,h,3,FloatArray(w*h*3){random.nextFloat()*9f})
        var lo=0f;var hi=1e-6f;var peak=1.0001f;val values=FloatArray(z.data.size)
        for(c in 0..2)for(i in 0 until p.size){val at=c*p.size+i;val s=HdrMath.linear((p.plane(c)[i].toInt()and 255)/255f);val v=ln((z.data[at]+HdrMath.EPSILON)/(s+HdrMath.EPSILON));values[at]=v;lo=min(lo,v);hi=max(hi,v);peak=max(peak,z.data[at])}
        val pixels=IntArray(p.size){i->var pixel=0xff000000.toInt();for(c in 0..2){val v=((values[c*p.size+i]-lo)/(hi-lo)*255).roundToInt().coerceIn(0,255);pixel=pixel or(v shl(16-c*8))};pixel}
        val m=HdrMath.encode(p,z,Cancel());assertArrayEquals(pixels,m.pixels);assertEquals(exp(lo),m.minimum,0f);assertEquals(exp(hi),m.maximum,0f);assertEquals(max(peak,exp(hi)),m.display,0f)
    }
}
