package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Regression against the frozen, pre-repack 2.8.6 formula and sequential pair writer.
 * All fixtures are synthetic. The reference object is test-only, never packaged in the APK. */
class HdrColorReuseTest {
    private fun disk(body:(File)->Unit){val d=Files.createTempDirectory("hdr-color-reuse").toFile();try{body(d)}finally{check(d.deleteRecursively())}}
    private fun base(w:Int=19,h:Int=35,kind:Int=0)=Planes(w,h).also{p->
        for(i in 0 until p.size)for(c in 0..2){val z=i*1664525+1013904223;p.plane(c)[i]=(when(kind){1->8+(z ushr 16 and 31);2->64+(z ushr 16 and 127);else->16+((z ushr(c*7))and 223)}).toByte()}
    }
    private fun raw(p:Planes)=HdrPixels.of(Frame(p.width,p.height,3,FloatArray(p.size*3){at->
        val c=at/p.size;val i=at%p.size;HdrMath.linearByte(p.plane(c)[i].toInt()and 255)*(1.1f+(i%37)*.045f)
    }))
    private fun enhanced(p:Planes)=HdrOutputColor.apply(p,HdrOutputBrightness.apply(raw(p)),Cancel())
    private class Count(private val input:HdrPixels):HdrPixels{
        override val w=input.w;override val h=input.h;override val displayCapacity=input.displayCapacity
        var samples=0L;var calls=0;var maxRows=0
        override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){samples+=rows.toLong()*w;calls++;maxRows=max(maxRows,rows);input.read(channel,y,rows,values,cancel)}
    }
    // Frozen pre-repack writer, independent of the production writer being tested.
    private fun legacyWrite(file:File,sdr:Planes,hdr:HdrPixels,cancel:Cancel){
        require(sdr.width==hdr.w && sdr.height==hdr.h);HdrPixels.dimensions(hdr.w,hdr.h)
        val capacity=hdr.displayCapacity?.let{HdrColor286Reference.checkedCapacity(it)}
        HdrFloatFile.requireSpace(file,16L+15L*sdr.size+16*HdrMemory.MIB)
        try{DataOutputStream(BufferedOutputStream(file.outputStream(),65536)).use{out->
            cancel.check();out.writeInt(if(capacity==null)0x48445231 else 0x48445232);out.writeInt(hdr.w);out.writeInt(hdr.h)
            if(capacity!=null)out.writeFloat(capacity)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            val block=minOf(16,hdr.h);val values=FloatArray(hdr.w*block)
            for(c in 0..2)for(y in 0 until hdr.h step block){
                val rows=minOf(block,hdr.h-y);val count=rows*hdr.w;hdr.read(c,y,rows,values,cancel)
                for(i in 0 until count)HdrPixels.valid(values[i])
                FloatBlocks.write(out,if(count==values.size)values else values.copyOf(count),cancel)
            };cancel.check()
        }}catch(t:Throwable){file.delete();throw t}
    }
    private fun fixed(p:Planes,capacity:Float=4f,read:(Int,Int,Int,FloatArray,Cancel)->Unit):HdrPixels=object:HdrPixels{
        override val w=p.width;override val h=p.height;override val displayCapacity=capacity
        override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){HdrPixels.bounds(this,channel,y,rows,values);cancel.check();read(channel,y,rows,values,cancel)}
    }
    private fun map(p:Planes,hdr:HdrPixels):Pair<HdrMath.Map,IntArray>{val pixels=IntArray(p.size);val m=HdrMath.encodePixelRows(hdr,Cancel(),{y,r,a->p.toArgbRows(y,r,a)},{y,r,a->a.copyInto(pixels,y*p.width,0,r*p.width)});return m to pixels}
    private fun maps(a:Pair<HdrMath.Map,IntArray>,b:Pair<HdrMath.Map,IntArray>){assertArrayEquals(a.second,b.second);assertEquals(a.first.minimum.toRawBits(),b.first.minimum.toRawBits());assertEquals(a.first.maximum.toRawBits(),b.first.maximum.toRawBits());assertEquals(a.first.display.toRawBits(),b.first.display.toRawBits())}
    private fun scalar(s:DoubleArray,h:DoubleArray,capacity:Double){val a=DoubleArray(3);val b=DoubleArray(3);HdrColor286Reference.transform(s,h,capacity,a,DoubleArray(12));HdrOutputColor.transform(s,h,capacity,b,DoubleArray(12));for(c in 0..2)assertEquals("channel $c",a[c].toRawBits(),b[c].toRawBits())}

    @Test fun scalarRandomAndBoundariesMatchFrozen286Bits(){
        val rng=java.util.Random(2868401);val caps=doubleArrayOf(1.0001,1.01,1.1,1.5,2.0,3.5,4.0,8.0,32.0,65504.0)
        repeat(120000){i->val cap=caps[i%caps.size];val s=DoubleArray(3);val h=DoubleArray(3)
            for(c in 0..2){s[c]=when(i%8){0->0.0;1->.01;2->.12;3->.6;4->1.0;else->rng.nextDouble()};h[c]=when(i%13){0->0.0;1->-0.0;2->cap;3->65504.0;else->rng.nextDouble()*min(65504.0,cap*1.5)}}
            scalar(s,h,cap)
        }
    }
    @Test fun allFrozenStudyInputsMatchOldDoubleBits(){var count=0;javaClass.getResourceAsStream("/hdr-output-color.csv")!!.bufferedReader().useLines{ls->ls.filter{it.isNotBlank()&&!it.startsWith("#")}.forEach{line->val a=line.split(',').map{it.toDouble()};scalar(a.take(3).toDoubleArray(),a.drop(3).take(3).toDoubleArray(),a[6]);count++}};assertEquals(784,count)}
    @Test fun hdr2WholeFilesMatchFrozenFormulaAndWriter()=disk{d->
        for(kind in 0..2)for((w,h) in listOf(1 to 1,7 to 33,19 to 35,127 to 65,1025 to 17,17001 to 1)){
            val p=base(w,h,kind);val a=HdrColor286Reference.apply(p,HdrOutputBrightness.apply(raw(p)),Cancel());val b=enhanced(p)
            val old=File(d,"old");val fresh=File(d,"fresh");legacyWrite(old,p,a,Cancel());HdrResultStore.write(fresh,p,b,Cancel())
            assertArrayEquals("$w x $h kind=$kind",old.readBytes(),fresh.readBytes());assertEquals(16L+15L*p.size,fresh.length())
        }
    }
    @Test fun gainPixelsAndCapacityMatchFrozenReadThroughChain()=disk{d->
        for(kind in 0..2)for((w,h) in listOf(1 to 1,7 to 33,19 to 35,127 to 65)){
            val p=base(w,h,kind);val expected=map(p,HdrColor286Reference.apply(p,HdrOutputBrightness.apply(raw(p)),Cancel()))
            HdrResultStore.withWrittenRows(File(d,"pair"),p,enhanced(p),Cancel()){rows->maps(expected,map(p,rows))}
        }
    }
    @Test fun capacityScanAndEnhancementReadSourceOnlyTwice()=disk{d->
        val p=base(37,83);val count=Count(HdrOutputBrightness.apply(raw(p)));val h=HdrOutputColor.apply(p,count,Cancel())
        HdrResultStore.withWrittenRows(File(d,"pair"),p,h,Cancel()){rows->val before=count.samples;map(p,rows);assertEquals(before,count.samples);assertEquals(h.displayCapacity,rows.displayCapacity)}
        assertEquals(6L*p.size,count.samples);assertTrue(count.maxRows<=16)
    }
    @Test fun hdr2WriterConsumesRgbBeforeAdvancingAndKeepsRowsBounded()=disk{d->
        val p=base(7,2003);val visits=ArrayList<Pair<Int,Int>>();val h=fixed(p){c,y,r,a,_->assertTrue(r<=16);visits.add(y to c);a.fill((c+1).toFloat(),0,r*p.width)}
        HdrResultStore.write(File(d,"pair"),p,h,Cancel());assertEquals((0 until p.height step 16).flatMap{y->(0..2).map{y to it}},visits)
    }
    @Test fun legacyHdr1WriterAndReadbackStayByteExact()=disk{d->
        val p=base();val old=File(d,"old");legacyWrite(old,p,raw(p),Cancel());val fresh=File(d,"fresh")
        HdrResultStore.withWrittenRows(fresh,p,raw(p),Cancel()){rows->assertNull(rows.displayCapacity);maps(map(p,raw(p)),map(p,rows))};assertArrayEquals(old.readBytes(),fresh.readBytes())
    }
    @Test fun writeReuseDoesNotAllocateOrReturnNewSdrRepresentation()=disk{d->
        val p=base();val before=Array(3){p.plane(it).copyOf()};val f=File(d,"pair")
        val result=HdrResultStore.withWrittenRows(f,p,enhanced(p),Cancel()){rows->assertTrue(rows is FileHdrPixels);"complete"}
        assertEquals("complete",result);assertTrue(f.isFile);for(c in 0..2)assertArrayEquals(before[c],p.plane(c))
    }
    @Test fun readbackReaderClosesAfterSuccess()=disk{d->
        val p=base();var escaped:HdrPixels?=null;HdrResultStore.withWrittenRows(File(d,"pair"),p,enhanced(p),Cancel()){escaped=it}
        assertThrows(IllegalStateException::class.java){escaped!!.read(0,0,1,FloatArray(p.width),Cancel())}
    }
    @Test fun readbackReaderClosesBeforeDownstreamFailureCleanup()=disk{d->
        val p=base();val file=File(d,"pair");val marker=IOException("injected encoder IO");var escaped:HdrPixels?=null
        val actual=assertThrows(IOException::class.java){HdrResultStore.withWrittenRows(file,p,enhanced(p),Cancel()){escaped=it;throw marker}}
        assertSame(marker,actual);assertFalse(file.exists());assertThrows(IllegalStateException::class.java){escaped!!.read(0,0,1,FloatArray(p.width),Cancel())}
    }
    @Test fun cancelledBeforeWriteNeverCallsConsumer()=disk{d->
        val p=base();val file=File(d,"pair");var called=false
        assertThrows(CancellationException::class.java){HdrResultStore.withWrittenRows(file,p,enhanced(p),Cancel().apply{cancelled=true}){called=true}}
        assertFalse(called);assertFalse(file.exists())
    }
    @Test fun cancellationDuringHdr2WriteDeletesPartial()=disk{d->
        val p=base();val file=File(d,"pair");val cancel=Cancel();var calls=0;var called=false
        val h=fixed(p){_,_,r,a,_->a.fill(1f,0,r*p.width);if(++calls==2)cancel.cancelled=true}
        assertThrows(CancellationException::class.java){HdrResultStore.withWrittenRows(file,p,h,cancel){called=true}}
        assertFalse(called);assertFalse(file.exists())
    }
    @Test fun cancellationDuringReadbackDeletesPairAndPropagates()=disk{d->
        val p=base();val file=File(d,"pair");val cancel=Cancel()
        assertThrows(CancellationException::class.java){HdrResultStore.withWrittenRows(file,p,enhanced(p),cancel){rows->cancel.cancelled=true;rows.read(0,0,1,FloatArray(p.width),cancel)}}
        assertFalse(file.exists())
    }
    @Test fun cancellationAtConsumerReturnDoesNotEscapeAsSuccess()=disk{d->
        val p=base();val file=File(d,"pair");val cancel=Cancel()
        assertThrows(CancellationException::class.java){HdrResultStore.withWrittenRows(file,p,enhanced(p),cancel){cancel.cancelled=true;123}}
        assertFalse(file.exists())
    }
    @Test fun sourceIoFailureIsNotFallbackAndCleansPair()=disk{d->
        val p=base();val file=File(d,"pair");val marker=IOException("injected source IO");val h=fixed(p){_,_,_,_,_->throw marker}
        val actual=assertThrows(IOException::class.java){HdrResultStore.withWrittenRows(file,p,h,Cancel()){error("must not encode")}}
        assertSame(marker,actual);assertFalse(file.exists());assertFalse(HdrAutoPolicy.canFallback(actual))
    }
    @Test fun invalidHdrValuesDeletePartialWithoutClipping()=disk{d->
        val p=base();for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,65505f)){
            val file=File(d,"pair");val h=fixed(p){_,_,r,a,_->a.fill(v,0,r*p.width)}
            assertThrows(IllegalArgumentException::class.java){HdrResultStore.withWrittenRows(file,p,h,Cancel()){error("must not encode")}};assertFalse(file.exists())
        }
    }
    @Test fun invalidFrozenBudgetRejectedBeforeTouchingDestination()=disk{d->
        val p=base();val file=File(d,"sentinel").apply{writeText("preserve")}
        for(v in listOf(1f,0f,Float.NaN,Float.POSITIVE_INFINITY)){
            assertThrows(IllegalArgumentException::class.java){HdrResultStore.withWrittenRows(file,p,fixed(p,v){_,_,_,_,_->error("read")},Cancel()){error("encode")}}
            assertEquals("preserve",file.readText())
        }
    }
    @Test fun finishedPairCapacityAndIdentityAreNotEnhancedAgain()=disk{d->
        val p=base();HdrResultStore.withWrittenRows(File(d,"pair"),p,enhanced(p),Cancel()){rows->
            assertSame(rows,HdrOutputColor.apply(rows,Cancel()){_,_,_->error("should not read JPEG again")});assertSame(rows,HdrOutputBrightness.apply(rows))
        }
    }
    @Test fun repeatedSaveFromStoredBitsIsByteExact()=disk{d->
        val p=base();val f=File(d,"pair");HdrResultStore.withWrittenRows(f,p,enhanced(p),Cancel()){rows->val out=File(d,"copy");HdrResultStore.write(out,p,rows,Cancel());assertArrayEquals(f.readBytes(),out.readBytes())}
    }
    @Test fun invalidDimensionsNeverStartConsumer()=disk{d->
        val p=base();val f=File(d,"pair");assertThrows(IllegalArgumentException::class.java){HdrResultStore.withWrittenRows(f,p,raw(base(7,3)),Cancel()){error("encode")}};assertFalse(f.exists())
    }
}
