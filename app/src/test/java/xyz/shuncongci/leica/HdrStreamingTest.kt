package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.min
import kotlin.math.roundToInt

/** Frozen baseline comparisons: all radiance bits, gain bytes, metadata and legacy cache/container bytes. */
class HdrStreamingTest {
    private fun pixels(w:Int,h:Int,seed:Long=83)=Planes(w,h).also{p->val r=Random(seed);for(c in 0..2)r.nextBytes(p.plane(c))}
    private fun light(w:Int,h:Int)=Frame(w,h,3,FloatArray(w*h*3){if(it==1)-0f else (it*17L%130003)/313f})
    private fun bits(a:FloatArray,b:FloatArray){assertEquals(a.size,b.size);for(i in a.indices)assertEquals("float bits $i",a[i].toRawBits(),b[i].toRawBits())}
    private fun bits(a:Frame,b:HdrPixels){val row=FloatArray(a.w);assertEquals(a.w,b.w);assertEquals(a.h,b.h)
        for(c in 0..2)for(y in 0 until a.h){b.read(c,y,1,row,Cancel());for(x in 0 until a.w)assertEquals("$c/$y/$x",a.data[c*a.size+y*a.w+x].toRawBits(),row[x].toRawBits())}}
    private fun <T> temp(body:(File)->T):T {val d=Files.createTempDirectory("hdr-stream-test").toFile();try{return body(d)}finally{check(d.deleteRecursively())}}
    private inline fun <reified T:Throwable> fails(body:()->Unit){try{body();fail("expected ${T::class.java.simpleName}")}catch(t:Throwable){if(t !is T)throw t}}
    private fun input(p3:Boolean,mono:Boolean):HdrReconstruction.Input {
        val raw=pixels(73,51,42);val gain=pixels(17,13,56)
        return HdrReconstruction.Input(73,51,17,13,{x,y,c->raw.plane(c)[y*73+x].toInt()and 255},{x,y,c->gain.plane(if(mono)0 else c)[y*17+x].toInt()and 255},
            FloatArray(256){BaselineHdrMath.linear(it/255f)},floatArrayOf(.25f,.5f,1f),floatArrayOf(4f,8f,16f),floatArrayOf(.5f,1f,2f),floatArrayOf(.015625f,0f,.003f),floatArrayOf(.015625f,.002f,0f),p3)
    }
    private fun source(p3:Boolean,mono:Boolean,scale:Boolean)=temp{d->
        val input=input(p3,mono)
        for(exif in 1..8){val w=if(scale)31 else if(exif>=5)51 else 73;val h=if(scale)23 else if(exif>=5)73 else 51
            val pair=BaselineHdrReconstruction.render(input,exif,w,h,Cancel());val colored=pixels(w,h)
            val expected=BaselineHdrMath.color(pair.first,pair.second,colored,Cancel())
            val f=File(d,"$exif");val rows=AtomicInteger()
            HdrFloatFile.write(f,w,h,Cancel()){write->HdrReconstruction.renderRows(input,exif,w,h,colored,false,Cancel()){y,hdr,sdr->
                assertNull(sdr);assertEquals(w*3,hdr.size);rows.incrementAndGet();write(y,hdr)
            }}
            assertEquals(h,rows.get());HdrFloatFile.open(f).use{bits(expected,it)}
            val old=File(d,"old-$exif");CheckpointPixels.frame(old,expected,Cancel());assertArrayEquals(old.readBytes(),f.readBytes())
        }
    }
    @Test fun sourceSrgbAllExifNativeBits()=source(false,false,false)
    @Test fun sourceP3AllExifNativeBits()=source(true,false,false)
    @Test fun sourceSrgbAllExifAreaBits()=source(false,false,true)
    @Test fun sourceP3AllExifAreaBits()=source(true,false,true)
    @Test fun sourceMonoSrgbAllExifAreaBits()=source(false,true,true)
    @Test fun sourceMonoP3AllExifAreaBits()=source(true,true,true)
    @Test fun rawSourceWithoutColorIsExact()=temp{d->val input=input(true,false);val expected=BaselineHdrReconstruction.render(input,6,51,73,Cancel()).second;val f=File(d,"raw")
        HdrFloatFile.write(f,51,73,Cancel()){emit->HdrReconstruction.renderRows(input,6,51,73,null,false,Cancel()){y,p,_->emit(y,p)}};HdrFloatFile.open(f).use{bits(expected,it)}}
    @Test fun retainedSdrRowsAreAlsoExact(){val input=input(true,true);val old=BaselineHdrReconstruction.render(input,8,31,23,Cancel());val a=FloatArray(31*23*3);val b=FloatArray(a.size)
        HdrReconstruction.renderRows(input,8,31,23,null,true,Cancel()){y,h,s->for(c in 0..2){System.arraycopy(h,c*31,b,c*31*23+y*31,31);System.arraycopy(checkNotNull(s),c*31,a,c*31*23+y*31,31)}}
        bits(old.first.data,a);bits(old.second.data,b)}
    @Test fun legacyCheckpointRoundTripBits()=temp{d->val p=light(263,97);val f=File(d,"new");HdrFloatFile.write(f,HdrPixels.of(p),Cancel());bits(p.data,CheckpointPixels.frame(f,Cancel()).data)
        val old=File(d,"old");CheckpointPixels.frame(old,p,Cancel());assertArrayEquals(old.readBytes(),f.readBytes());HdrFloatFile.open(old).use{bits(p,it)}}
    @Test fun unalignedReadAcross64KiBBlocks()=temp{d->val p=light(1501,59);val f=File(d,"p");HdrFloatFile.write(f,HdrPixels.of(p),Cancel());HdrFloatFile.open(f).use{r->val v=FloatArray(1501*17)
        for(c in 0..2){r.read(c,19,17,v,Cancel());bits(p.data.copyOfRange(c*p.size+19*1501,c*p.size+36*1501),v)}}}
    @Test fun onePixelRoundTrip()=temp{d->val f=File(d,"p");val p=Frame(1,1,3,floatArrayOf(-0f,65504f,0f));HdrFloatFile.write(f,HdrPixels.of(p),Cancel());HdrFloatFile.open(f).use{bits(p,it)}}
    @Test fun cancelledSnapshotLeavesNoPartial()=temp{d->val f=File(d,"p");fails<CancellationException>{HdrFloatFile.write(f,3,3,Cancel().apply{cancelled=true}){fail()}};assertFalse(f.exists())}
    @Test fun missingRowsRejectedAndCleaned()=temp{d->val f=File(d,"p");fails<IllegalStateException>{HdrFloatFile.write(f,3,3,Cancel()){it(0,FloatArray(9))}};assertFalse(f.exists())}
    @Test fun duplicateRowsRejectedAndCleaned()=temp{d->val f=File(d,"p");fails<IllegalArgumentException>{HdrFloatFile.write(f,1,1,Cancel()){it(0,FloatArray(3));it(0,FloatArray(3))}};assertFalse(f.exists())}
    @Test fun invalidRadianceRejectedAndCleaned()=temp{d->for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,65505f)){val f=File(d,"p");fails<IllegalArgumentException>{HdrFloatFile.write(f,1,1,Cancel()){it(0,floatArrayOf(0f,v,0f))}};assertFalse(f.exists())}}
    @Test fun cannotOverwriteOwnedSnapshot()=temp{d->val f=File(d,"p");f.writeText("keep");fails<IllegalArgumentException>{HdrFloatFile.write(f,1,1,Cancel()){fail()}};assertEquals("keep",f.readText())}
    @Test fun invalidHeaderAndLengthRejected()=temp{d->val f=File(d,"p");f.writeBytes(ByteArray(16));fails<IllegalArgumentException>{HdrFloatFile.open(f)}
        HdrFloatFile.write(File(d,"valid"),HdrPixels.of(light(3,5)),Cancel());val valid=File(d,"valid");RandomAccessFile(valid,"rw").use{it.setLength(valid.length()-1)};fails<IllegalArgumentException>{HdrFloatFile.open(valid)}}
    @Test fun closedReaderCannotRead()=temp{d->val f=File(d,"p");HdrFloatFile.write(f,HdrPixels.of(light(3,5)),Cancel());val p=HdrFloatFile.open(f);p.close();fails<IllegalStateException>{p.read(0,0,1,FloatArray(3),Cancel())}}
    @Test fun readCancellation()=temp{d->val f=File(d,"p");HdrFloatFile.write(f,HdrPixels.of(light(3,5)),Cancel());HdrFloatFile.open(f).use{p->fails<CancellationException>{p.read(0,0,1,FloatArray(3),Cancel().apply{cancelled=true})}}}
    @Test fun failedProducerIsJoinedBeforeCleanup()=temp{d->val f=File(d,"p");val active=AtomicInteger();val callbacks=AtomicInteger();val input=input(false,false)
        fails<IOException>{HdrFloatFile.write(f,73,51,Cancel()){emit->HdrReconstruction.renderRows(input,1,73,51,null,false,Cancel()){y,row,_->active.incrementAndGet();try{callbacks.incrementAndGet();if(y==0)throw IOException("disk marker");Thread.sleep(2);emit(y,row)}finally{active.decrementAndGet()}}}}
        assertEquals(0,active.get());val count=callbacks.get();Thread.sleep(15);assertEquals(count,callbacks.get());assertFalse(f.exists())}
    @Test fun midRenderCancellationIsJoinedAndCleaned()=temp{d->val f=File(d,"p");val cancel=Cancel();val input=input(false,false)
        fails<CancellationException>{HdrFloatFile.write(f,73,51,cancel){emit->HdrReconstruction.renderRows(input,1,73,51,null,false,cancel){y,row,_->emit(y,row);cancel.cancelled=true}}};assertFalse(f.exists())}
    @Test fun pairBytesAndRowReadbackAreLegacyExact()=temp{d->val p=pixels(251,39);val hdr=light(251,39);val a=File(d,"old");val b=File(d,"new")
        HdrResultStore.write(a,p,hdr,Cancel());HdrResultStore.write(b,p,HdrPixels.of(hdr),Cancel());assertArrayEquals(a.readBytes(),b.readBytes())
        val (base,rows)=HdrResultStore.readRows(a,251,39,Cancel());rows.use{bits(hdr,it)};for(c in 0..2)assertArrayEquals(p.plane(c),base.plane(c))}
    @Test fun streamedPairFailureDeletesPartial()=temp{d->val p=pixels(3,5);val hdr=object:HdrPixels{override val w=3;override val h=5;override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){throw IOException("disk")}}
        val f=File(d,"p");fails<IOException>{HdrResultStore.write(f,p,hdr,Cancel())};assertFalse(f.exists())}
    private fun gain(w:Int,h:Int)=temp{d->val base=pixels(w,h);val hdr=light(w,h);val old=BaselineHdrMath.encode(base,hdr,Cancel());val f=File(d,"p");HdrFloatFile.write(f,HdrPixels.of(hdr),Cancel());val actual=IntArray(w*h);var done=0
        HdrFloatFile.open(f).use{p->val made=HdrMath.encodePixelRows(p,Cancel(),readBase={y,rows,b->assertTrue(rows<=16);base.toArgbRows(y,rows,b)},write={y,rows,b->assertEquals(done,y);assertTrue(b.size<=w*16);System.arraycopy(b,0,actual,y*w,w*rows);done+=rows})
            assertEquals(0,made.pixels.size);assertEquals(old.minimum.toRawBits(),made.minimum.toRawBits());assertEquals(old.maximum.toRawBits(),made.maximum.toRawBits());assertEquals(old.display.toRawBits(),made.display.toRawBits())}
        assertEquals(h,done);assertArrayEquals(old.pixels,actual)}
    @Test fun gainRowsAndMetadataExact()=gain(277,137)
    @Test fun gainOnePixelAndMetadataExact()=gain(1,1)
    @Test fun gainUnalignedRowsExact()=gain(1031,35)
    @Test fun invalidGainInputNeverDelivers(){val hdr=HdrPixels.of(Frame(1,1,3,floatArrayOf(0f,Float.NaN,1f)));fails<IllegalArgumentException>{HdrMath.encodePixelRows(hdr,Cancel(),{_,_,b->b.fill(-1)}){_,_,_->fail()}}}
    @Test fun cancelledGainNeverDelivers(){fails<CancellationException>{HdrMath.encodePixelRows(HdrPixels.of(light(3,5)),Cancel().apply{cancelled=true},{_,_,_->fail()}){_,_,_->fail()}}}
    @Test fun cropGeometryAllBitsExact()=temp{d->val p=light(97,71);val f=File(d,"p");HdrFloatFile.write(f,HdrPixels.of(p),Cancel());HdrFloatFile.open(f).use{input->
        for(crop in listOf(CompositionGeometry.FULL,CompositionGeometry.Crop(.13,.07,.88,.93),CompositionGeometry.Crop(.001,.003,.991,.997))){val box=CompositionGeometry.pixels(crop,p.w,p.h)
            for(size in listOf(1 to 1,box.w to box.h,31 to (31.0*box.h/box.w).roundToInt())){val expected=HdrMath.geometry(p,crop,size.first,size.second,Cancel());bits(expected,HdrPixelGeometry.crop(input,crop,size.first,size.second))}}
    }}
    @Test fun cropMustKeepAspectRatio(){fails<IllegalArgumentException>{HdrPixelGeometry.crop(HdrPixels.of(light(97,71)),CompositionGeometry.Crop(.13,.07,.88,.93),31,23)}}
    @Test fun fullCropKeepsIdentity(){val p=HdrPixels.of(light(9,7));assertSame(p,HdrPixelGeometry.crop(p,CompositionGeometry.FULL,9,7))}
    @Test fun cropCannotUpscale(){fails<IllegalArgumentException>{HdrPixelGeometry.crop(HdrPixels.of(light(9,7)),CompositionGeometry.FULL,10,7)}}
    @Test fun containerFileBytesAndOffsetsIdentical()=temp{d->val base=ByteArray(140117){(it*53).toByte()};val gain=ByteArray(71123){(it*29).toByte()}
        for(b in listOf(base,gain)){b[0]=255.toByte();b[1]=216.toByte();b[b.size-2]=255.toByte();b[b.size-1]=217.toByte()}
        val a=File(d,"base").also{it.writeBytes(base)};val b=File(d,"gain").also{it.writeBytes(gain)};val map=HdrMath.Map(19,17,IntArray(0),.125f,16f,27f)
        val old=ByteArrayOutputStream();UltraHdrContainer.write(old,base,gain,map);val made=ByteArrayOutputStream();UltraHdrContainer.write(made,a,b,map,Cancel());assertArrayEquals(old.toByteArray(),made.toByteArray())}
    @Test fun invalidContainerNeverStartsWriting()=temp{d->val a=File(d,"bad").also{it.writeBytes(byteArrayOf(1,2,3,4))};val out=ByteArrayOutputStream();fails<IllegalArgumentException>{UltraHdrContainer.write(out,a,a,HdrMath.Map(1,1,IntArray(0),1f,2f,2f),Cancel())};assertEquals(0,out.size())}
    @Test fun cancelledContainerNeverStartsWriting()=temp{d->val a=File(d,"a");val out=ByteArrayOutputStream();fails<CancellationException>{UltraHdrContainer.write(out,a,a,HdrMath.Map(1,1,IntArray(0),1f,2f,2f),Cancel().apply{cancelled=true})};assertEquals(0,out.size())}
    @Test fun diskExhaustionNeverBecomesSdr()=temp{d->
        val parent=object:File(d.path){override fun getUsableSpace()=0L}
        val file=object:File(d,"unwritten"){override fun getParentFile():File=parent}
        val p=pixels(3,5);val h=light(3,5)
        for(write in listOf<()->Unit>({HdrFloatFile.write(file,3,5,Cancel()){fail()}},{HdrResultStore.write(file,p,h,Cancel())},{HdrResultStore.write(file,p,HdrPixels.of(h),Cancel())})){
            try{write();fail()}catch(t:IOException){assertFalse(HdrAutoPolicy.canFallback(t))};assertFalse(file.exists())
        }
    }
    @Test fun checkpointCopyCannotOverwriteOwnedPixels()=temp{d->
        val a=File(d,"a").also{it.writeText("source")};val b=File(d,"b").also{it.writeText("target")}
        fails<IllegalArgumentException>{HdrFloatFile.copy(a,a,Cancel())};fails<IllegalArgumentException>{HdrFloatFile.copy(a,b,Cancel())}
        assertEquals("source",a.readText());assertEquals("target",b.readText())
    }
    @Test fun fatalWorkerFailureWinsOverRacingCancellationOrIo(){
        val ref=java.util.concurrent.atomic.AtomicReference<Throwable?>(null);val io=IOException("first");val fatal=OutOfMemoryError("synthetic marker, no large allocation")
        HdrReconstruction.recordFailure(ref,io);HdrReconstruction.recordFailure(ref,fatal);HdrReconstruction.recordFailure(ref,CancellationException("later"));assertSame(fatal,ref.get());assertFalse(HdrAutoPolicy.canFallback(fatal))
    }
    @Test fun fatalProducerStillJoinsAndCleans()=temp{d->
        val f=File(d,"p");val active=AtomicInteger();val marker=AssertionError("synthetic worker marker")
        try{HdrFloatFile.write(f,73,51,Cancel()){emit->HdrReconstruction.renderRows(input(false,false),1,73,51,null,false,Cancel()){y,row,_->active.incrementAndGet();try{if(y==0)throw marker;emit(y,row)}finally{active.decrementAndGet()}}};fail()}catch(t:AssertionError){assertSame(marker,t)}
        assertEquals(0,active.get());assertFalse(f.exists())
    }
    @Test fun checkpointAtomicityStillPreservesLastComplete()=temp{d->val p=light(37,23);val checkpoint=File(d,"session");LatestCheckpoint(checkpoint).update(blobs=mapOf("render.hdr" to {f->HdrFloatFile.write(f,HdrPixels.of(p),Cancel())}))
        val broken=LatestCheckpoint(checkpoint){if(it=="before-manifest")throw IOException("crash marker")}
        fails<IOException>{broken.update(blobs=mapOf("render.hdr" to {f->HdrFloatFile.write(f,HdrPixels.of(light(19,11)),Cancel())}))}
        val reopened=LatestCheckpoint(checkpoint);HdrFloatFile.open(checkNotNull(reopened.file("render.hdr"))).use{bits(p,it)};assertEquals(2,checkpoint.listFiles()!!.size)}
}
