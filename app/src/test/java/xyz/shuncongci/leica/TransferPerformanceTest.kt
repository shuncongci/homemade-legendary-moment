package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.Random
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

class TransferPerformanceTest {
    private fun pixels(w:Int,h:Int,seed:Long=71)=Planes(w,h).also{p->val r=Random(seed);for(c in 0..2)r.nextBytes(p.plane(c))}
    private fun copy(p:Planes)=Planes(p.width,p.height,p.p0.copyOf(),p.p1.copyOf(),p.p2.copyOf())
    private fun bytes(a:Planes,b:Planes){for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c))}
    private fun bits(a:FloatArray,b:FloatArray){assertEquals(a.size,b.size);for(i in a.indices)assertEquals("float bits at $i",a[i].toRawBits(),b[i].toRawBits())}
    private fun rejected(f:()->Unit){try{f();fail("expected rejection")}catch(_:IllegalArgumentException){}}
    private fun cancelled(f:(Cancel)->Unit){try{f(Cancel().apply{cancelled=true});fail("expected cancellation")}catch(_:CancellationException){}}
    private fun fusion(w:Int,h:Int){val p=pixels(w,h);val g=pixels(w,h,83);val expected=BaselineFusion.fuse(copy(p),copy(g),Cancel());val actual=Fusion.fuse(copy(p),copy(g),Cancel());bytes(expected,actual)}
    @Test fun fusionSinglePixel(){fusion(1,1)}
    @Test fun fusionSingleRow(){fusion(137,1)}
    @Test fun fusionSingleColumn(){fusion(1,157)}
    @Test fun fusionReflectKernelLargerThanImage(){fusion(19,13)}
    @Test fun fusionUnalignedStripeAndRing(){fusion(263,237)}
    @Test fun fusionManyStripesAndLongImage(){fusion(529,401)}
    @Test fun fusionCancellation(){cancelled{Fusion.fuse(pixels(7,9),pixels(7,9),it)}}
    @Test fun all16777216LabCodesInverseExactlyMatchesBaseline(){
        for(l in 0..255){val p=Planes(256,256)
            for(i in 0 until p.size){p.p0[i]=l.toByte();p.p1[i]=(i ushr 8).toByte();p.p2[i]=i.toByte()}
            val ref=copy(p);BaselineLab.labToRgbInPlace(ref,Cancel());Lab.labToRgbInPlace(p,Cancel());bytes(ref,p)
        }
    }
    @Test fun byteTransferTableIsExhaustiveExact(){for(v in 0..255)assertEquals(BaselineHdrMath.linear(v/255f).toRawBits(),HdrMath.linearByte(v).toRawBits())}
    @Test fun colorLinearAndInPlaceRemainExact(){val p=pixels(269,179);val target=pixels(269,179,13);val s=BaselineHdrMath.linear(p);val h=Frame(p.width,p.height,3,FloatArray(s.data.size){(it%101)/7f});bits(s.data,HdrMath.linear(p).data)
        val ref=BaselineHdrMath.color(s,h,target,Cancel());val result=HdrMath.colorInPlace(s,Frame(h.w,h.h,3,h.data.copyOf()),target,Cancel());bits(ref.data,result.data)}
    @Test fun gainMapAllPixelsAndMetadataExact(){for(seed in listOf(0L,9L,318L)){
        val p=pixels(277,137,seed);val h=Frame(p.width,p.height,3,FloatArray(p.size*3){((it*17L%130003)/313f)})
        val ref=BaselineHdrMath.encode(p,h,Cancel());val a=HdrMath.encode(p,h,Cancel());assertArrayEquals(ref.pixels,a.pixels)
        assertEquals(ref.minimum.toRawBits(),a.minimum.toRawBits());assertEquals(ref.maximum.toRawBits(),a.maximum.toRawBits());assertEquals(ref.display.toRawBits(),a.display.toRawBits())}}
    @Test fun streamedGainRowsBoundedAndIdentical(){val p=pixels(251,95);val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]*=if(i%11==0).2f else 7f
        val ref=BaselineHdrMath.encode(p,h,Cancel());val actual=IntArray(p.size);var done=0
        val meta=HdrMath.encodeRows(p,h,Cancel()){y,rows,b->assertEquals(done,y);assertTrue(b.size<=p.width*16);System.arraycopy(b,0,actual,y*p.width,rows*p.width);done+=rows}
        assertEquals(p.height,done);assertEquals(0,meta.pixels.size);assertArrayEquals(ref.pixels,actual);assertEquals(ref.maximum,meta.maximum,0f)}
    @Test fun gainCancellationNeverDelivers(){cancelled{HdrMath.encodeRows(pixels(19,17),Frame(19,17,3,FloatArray(969)),it){_,_,_->fail()}}}
    @Test fun gainInvalidRadianceRejected(){rejected{HdrMath.encodeRows(pixels(1,1),Frame(1,1,3,floatArrayOf(0f,Float.NaN,1f)),Cancel()){_,_,_->fail()}}}
    private fun input(w:Int,h:Int,gw:Int,gh:Int,p3:Boolean,mono:Boolean=false):HdrReconstruction.Input {
        val raw=pixels(w,h,42);val gain=pixels(gw,gh,56)
        return HdrReconstruction.Input(w,h,gw,gh,{x,y,c->raw.plane(c)[y*w+x].toInt()and 255},{x,y,c->gain.plane(if(mono)0 else c)[y*gw+x].toInt()and 255},
            FloatArray(256){BaselineHdrMath.linear(it/255f)},floatArrayOf(.25f,.5f,1f),floatArrayOf(4f,8f,16f),floatArrayOf(.5f,1f,2f),floatArrayOf(.015625f,0f,.003f),floatArrayOf(.015625f,.002f,0f),p3)
    }
    private fun source(p3:Boolean,mono:Boolean,scale:Boolean){
        val input=input(73,51,17,13,p3,mono)
        for(exif in 1..8){val ow=if(exif>=5)51 else 73;val oh=if(exif>=5)73 else 51;val w=if(scale)31 else ow;val h=if(scale)23 else oh
            val ref=BaselineHdrReconstruction.render(input,exif,w,h,Cancel());val p=pixels(w,h)
            val before=copy(p);val both=HdrReconstruction.render(input,exif,w,h,null,true,Cancel());bits(ref.first.data,both.sdr!!.data);bits(ref.second.data,both.hdr.data)
            val expected=BaselineHdrMath.color(ref.first,ref.second,p,Cancel());val fused=HdrReconstruction.render(input,exif,w,h,p,false,Cancel());assertNull(fused.sdr);bits(expected.data,fused.hdr.data);bytes(before,p)
            bits(ref.second.data,HdrReconstruction.render(input,exif,w,h,null,false,Cancel()).hdr.data)
        }
    }
    @Test fun nativeSrgbEveryExifExact(){source(false,false,false)}
    @Test fun nativeP3EveryExifExact(){source(true,false,false)}
    @Test fun reducedSrgbEveryExifExact(){source(false,false,true)}
    @Test fun reducedP3EveryExifExact(){source(true,false,true)}
    @Test fun monoGainSrgbEveryExifExact(){source(false,true,true)}
    @Test fun monoGainP3EveryExifExact(){source(true,true,true)}
    @Test fun hdrSourceCancellation(){cancelled{HdrReconstruction.render(input(9,7,1,1,false),1,9,7,null,false,it)}}
    @Test fun hdrSourceNoUpscale(){rejected{HdrReconstruction.render(input(9,7,1,1,false),1,10,7,null,false,Cancel())}}
    @Test fun hdrSourceWrongColorShape(){rejected{HdrReconstruction.render(input(9,7,1,1,false),1,9,7,pixels(7,9),false,Cancel())}}
    @Test fun hdrSourceOnePixelAndOneGainPixel(){val i=input(1,1,1,1,true);val ref=BaselineHdrReconstruction.render(i,8,1,1,Cancel());bits(ref.second.data,HdrReconstruction.render(i,8,1,1,null,false,Cancel()).hdr.data)}
    @Test fun floatBlocksRetainRawBitsAndLegacyFormat(){val values=FloatArray(70003){if(it==1)-0f else (it-32768)*.000173f};val old=ByteArrayOutputStream();DataOutputStream(old).use{out->values.forEach{out.writeInt(it.toRawBits())}}
        val made=ByteArrayOutputStream();FloatBlocks.write(made,values,Cancel());assertArrayEquals(old.toByteArray(),made.toByteArray());val result=FloatArray(values.size);FloatBlocks.read(DataInputStream(ByteArrayInputStream(old.toByteArray())),result,Cancel());bits(values,result)}
    @Test fun floatBlockCancellation(){cancelled{FloatBlocks.write(ByteArrayOutputStream(),FloatArray(100),it)}}
    @Test fun floatBlocksRejectNonFinite(){rejected{FloatBlocks.write(ByteArrayOutputStream(),floatArrayOf(Float.NaN),Cancel())}}
    @Test fun floatBlocksRejectTruncation(){try{FloatBlocks.read(DataInputStream(ByteArrayInputStream(ByteArray(3))),FloatArray(1),Cancel());fail()}catch(_:EOFException){}}
    private fun optics(w:Int,h:Int,mask:Boolean){
        val high=Frame(w,h,3,FloatArray(w*h*3){if(it%17==0)(it%39)/7f else 0f})
        val coc=Frame(w,h,1,FloatArray(w*h){((it/w)%9-4)*.49f});val oldHigh=high.data.copyOf();val oldCoc=coc.data.copyOf()
        if(mask){val a=BaselineHighlightScatter.opticalLayers(high,coc,2.8f,Cancel());val b=HighlightScatter.opticalLayers(high,coc,2.8f,Cancel());bits(a.color.data,b.color.data);bits(a.foreground.data,b.foreground.data)}
        else bits(BaselineHighlightScatter.optical(high,coc,2.8f,Cancel()).data,HighlightScatter.optical(high,coc,2.8f,Cancel()).data)
        bits(oldHigh,high.data);bits(oldCoc,coc.data)
    }
    @Test fun scatterMultipleBandsAndHaloExact(){optics(37,401,false)}
    @Test fun scatterForegroundMultipleBandsExact(){optics(29,277,true)}
    @Test fun scatterTinyReflectionsExact(){optics(3,5,false)}
    @Test fun scatterRingWideHaloExact(){val w=19;val h=331;val high=Frame(w,h,3,FloatArray(w*h*3){if(it%101==0)1f else 0f});val coc=Frame(w,h,1,FloatArray(w*h){if(it%2==0)-8.3f else 9.5f});bits(BaselineHighlightScatter.scatter(high,coc,5.6f,Cancel()).data,HighlightScatter.scatter(high,coc,5.6f,Cancel()).data)}
    @Test fun softenedEdgesExactEveryFloat(){val w=47;val h=173;val light=Frame(w,h,3,FloatArray(w*h*3){((it*7)%47)/3f});val coc=Frame(w,h,1,FloatArray(w*h){((it*17)%29-14)*.29f});bits(BaselineSoftBokehEdges.soften(light,coc,Cancel()).data,SoftBokehEdges.soften(light,coc,Cancel()).data)}
    @Test fun softenerNoBlurKeepsIdentity(){val light=Frame(9,7,3,FloatArray(189){it/23f});val coc=Frame(9,7,1,FloatArray(63));assertSame(light,SoftBokehEdges.softenInPlace(light,coc,Cancel()))}
}
