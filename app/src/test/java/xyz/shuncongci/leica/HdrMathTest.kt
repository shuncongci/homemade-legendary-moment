package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class HdrMathTest {
    private fun reject(block:()->Unit){try{block();fail("expected rejection")}catch(_:IllegalArgumentException){}}
    private fun frame(w:Int=17,h:Int=13,v:Float=0f)=Frame(w,h,3,FloatArray(w*h*3){v})
    private fun planes(w:Int=17,h:Int=13,v:Int=80)=Planes(w,h).also{it.p0.fill(v.toByte());it.p1.fill(v.toByte());it.p2.fill(v.toByte())}
    private fun depth(w:Int=17,h:Int=13,v:Float=.5f)=Frame(w,h,1,FloatArray(w*h){v})
    private val identity:(Frame,Frame)->Frame={a,_->a}
    @Test fun transferRoundTripsSdr(){for(i in 0..255)assertEquals(i/255f,HdrMath.srgb(HdrMath.linear(i/255f)),1e-6f)}
    @Test fun linearWhiteIsOne(){assertEquals(1f,HdrMath.linear(1f),0f)}
    @Test fun superWhiteNeverClipped(){assertTrue(HdrMath.srgb(4f)>1f);assertEquals(4f,HdrMath.linear(HdrMath.srgb(4f)),1e-5f)}
    @Test fun gainNeutral(){assertEquals(1f,HdrMath.gain(.7f,1f,1f,2f),0f)}
    @Test fun gainEndpoints(){assertEquals(.25f,HdrMath.gain(0f,.25f,16f,2f),1e-6f);assertEquals(16f,HdrMath.gain(1f,.25f,16f,2f),1e-5f)}
    @Test fun androidGammaNotAdobeReciprocal(){assertEquals(16f.pow(.25f.pow(2)),HdrMath.gain(.25f,1f,16f,2f),1e-6f)}
    @Test fun nearBlackOffsetIsApplied(){assertEquals(.015625f,HdrMath.recover(0f,1f,1f,2f,1f,HdrMath.EPSILON,HdrMath.EPSILON),1e-7f)}
    @Test fun attenuationSupported(){assertEquals(.25f,HdrMath.recover(.5f,0f,.5f,1f,1f,0f,0f),1e-7f)}
    @Test fun invalidGainRejected(){reject{HdrMath.gain(.5f,0f,2f,1f)};reject{HdrMath.gain(.5f,2f,1f,1f)};reject{HdrMath.gain(.5f,1f,2f,0f)}}
    @Test fun nonFiniteRejected(){reject{HdrMath.checked(frame(v=Float.NaN))};reject{HdrMath.checked(frame(v=Float.POSITIVE_INFINITY))}}
    @Test fun negativeRadianceRejected(){reject{HdrMath.checked(frame(v=-.01f))}}
    @Test fun noUnsupportedClipToHalfFloat(){reject{HdrMath.checked(frame(v=65505f))}}
    @Test fun colorIdentityKeepsHdr(){val p=planes();val s=HdrMath.linear(p);val h=frame(v=3f);val result=HdrMath.color(s,h,p,Cancel());for(v in result.data)assertEquals(3f,v,1e-5f)}
    @Test fun colorUsesActualNewAppearance(){val p=planes(v=160);val s=HdrMath.linear(planes(v=80));val h=frame(v=2f);val result=HdrMath.color(s,h,p,Cancel());assertTrue(result.data.all{it>2f})}
    @Test fun neutralSourceDoesNotInventHdr(){val old=planes(v=80);val s=HdrMath.linear(old);val p=planes(v=120);val result=HdrMath.color(s,s,p,Cancel());val target=HdrMath.linear(p);for(i in result.data.indices)assertEquals(target.data[i],result.data[i],1e-6f)}
    @Test fun colorNearBlackFinite(){val result=HdrMath.color(frame(),frame(v=.2f),planes(v=0),Cancel());assertTrue(result.data.all{it.isFinite() && it>=0f})}
    @Test fun colorCancellation(){try{HdrMath.color(frame(),frame(),planes(),Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun gamutIdentityPreservesSuperWhite(){val v=HdrMath.gamut(3f,2f,1f);assertArrayEquals(floatArrayOf(3f,2f,1f),v,1e-6f)}
    @Test fun gamutMappingPreservesLuminance(){val v=HdrMath.gamut(-.2f,2f,.5f);assertTrue(v.all{it>=0});assertEquals(.2126f*(-.2f)+.7152f*2+.0722f*.5f,.2126f*v[0]+.7152f*v[1]+.0722f*v[2],1e-5f)}
    @Test fun fullGeometryAliasesImmutableInput(){val f=frame();assertSame(f,HdrMath.geometry(f,CompositionGeometry.FULL,f.w,f.h,Cancel()))}
    @Test fun cropUsesSourceCoordinates(){val f=Frame(4,4,3,FloatArray(48){i->(i%16).toFloat()});val o=HdrMath.geometry(f,CompositionGeometry.Crop(.25,.25,.75,.75),2,2,Cancel());assertArrayEquals(floatArrayOf(5f,6f,9f,10f),o.data.copyOfRange(0,4),0f)}
    @Test fun linearAreaPreservesEnergy(){val f=Frame(4,4,3,FloatArray(48){i->if(i%16==0)16f else 0f});val o=HdrMath.geometry(f,CompositionGeometry.FULL,2,2,Cancel());assertEquals(4f,o.data[0],1e-6f);assertEquals(0f,o.data[1],0f)}
    @Test fun geometryCannotUpscale(){reject{HdrMath.geometry(frame(),CompositionGeometry.FULL,18,13,Cancel())}}
    @Test fun geometryCancel(){try{HdrMath.geometry(frame(),CompositionGeometry.FULL,8,6,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun floatMapRoundTrip(){val p=planes();val h=Frame(17,13,3,FloatArray(17*13*3){i->(i%47)/10f});val map=HdrMath.encode(p,h,Cancel());val result=HdrMath.reconstruct(p,map,Cancel());assertTrue(HdrMath.error(h,result).maxRelativeToPeak<.025f)}
    @Test fun rgbMapHasIndependentChannels(){val p=planes();val h=Frame(17,13,3,FloatArray(17*13*3){i->when(i/(17*13)){0->.05f;1->1f;else->4f}});val m=HdrMath.encode(p,h,Cancel());val pixel=m.pixels[0];assertTrue(((pixel ushr 16)and 255)!=((pixel ushr 8)and 255));assertTrue((pixel and 255)!=((pixel ushr 8)and 255))}
    @Test fun zeroBlackOutputFinite(){val p=planes(v=0);val h=frame();val m=HdrMath.encode(p,h,Cancel());HdrMath.accept(HdrMath.error(h,HdrMath.reconstruct(p,m,Cancel())))}
    @Test fun sourceArraysNotMutated(){val p=planes();val h=frame(v=3f);val before=h.data.copyOf();HdrMath.encode(p,h,Cancel());assertArrayEquals(before,h.data,0f);assertEquals(80,p.p0[0].toInt()and 255)}
    @Test fun lossyErrorGuardRejectsBadHdr(){reject{HdrMath.accept(HdrMath.error(frame(v=3f),frame(v=1f)))}}
    @Test fun exactErrorAccepted(){val f=frame(v=4f);HdrMath.accept(HdrMath.error(f,f))}
    @Test fun focusedHdrBypassesModelWithoutClipping(){val sharp=frame(v=4f);var calls=0;val result=LegendaryMath.renderHdr(planes(),sharp,depth(),.5f,2.8f,Cancel()){a,_->calls++;a};assertSame(sharp,result);assertEquals(0,calls)}
    @Test fun opticalHdrUsesActualFloatLight(){val p=planes(v=0);val h=frame(v=4f);val result=LegendaryMath.renderHdr(p,h,depth(v=.7f),.5f,5.6f,Cancel(),infer=identity);assertTrue(result.data.maxOrNull()!!>1f);assertTrue(result.data.all{it.isFinite() && it>=0f})}
    @Test fun hdrPointLightSpreadsInsteadOfOldMapLocation(){val p=planes(33,25,0);val h=frame(33,25);val at=12*33+16;for(c in 0..2)h.data[c*h.size+at]=64f
        val result=LegendaryMath.renderHdr(p,h,depth(33,25,.6f),.5f,5.6f,Cancel(),infer=identity)
        assertTrue(result.data[at]<64f);assertTrue(result.data[at+1]>0f)
    }
    @Test fun opaqueProtectedSubjectKeepsSharpHdr(){val sharp=frame(v=4f);val alpha=Frame(17,13,1,FloatArray(17*13){1f});val result=LegendaryMath.renderHdr(planes(),sharp,depth(v=.9f),.5f,2.8f,Cancel(),alpha,identity);assertArrayEquals(sharp.data,result.data,1e-5f)}
    @Test fun zeroSubjectMaskMatchesUnprotected(){val p=planes(v=0);val h=frame(v=3f);val d=depth(v=.7f);val alpha=Frame(17,13,1,FloatArray(17*13));val a=LegendaryMath.renderHdr(p,h,d,.5f,5.6f,Cancel(),infer=identity);val b=LegendaryMath.renderHdr(p,h,d,.5f,5.6f,Cancel(),alpha,identity);assertArrayEquals(a.data,b.data,1e-5f)}
    @Test fun hdrRenderCancellation(){try{LegendaryMath.renderHdr(planes(),frame(),depth(),.5f,2.8f,Cancel().apply{cancelled=true},infer=identity);fail()}catch(_:CancellationException){}}
    @Test fun memoryBudgetUsesLongMath(){assertTrue(HdrMath.memoryBytes(10000,5000,10000,5000)>Int.MAX_VALUE);reject{HdrMath.memoryBytes(Int.MAX_VALUE,2,1,1)}}
    @Test fun containerContainsRecomputedMetadataAndMpf(){val out=ByteArrayOutputStream();val jpeg=byteArrayOf(-1,-40,-1,-39);UltraHdrContainer.write(out,jpeg,jpeg,HdrMath.encode(planes(),frame(v=3f),Cancel()));val bytes=out.toByteArray();val text=bytes.toString(Charsets.ISO_8859_1);assertTrue(text.contains("MPF\u0000"));assertTrue(text.contains("hdrgm:BaseRenditionIsHDR=\"False\""));assertTrue(text.contains("Item:Semantic=\"GainMap\""));assertEquals(0xff,bytes[0].toInt()and 255);assertEquals(0xd9,bytes.last().toInt()and 255)}
    @Test fun combinedHdrProducesBothOutputsWithOneScaleSequence(){
        val p=planes();val sharp=frame(v=3f);val d=depth(v=.7f)
        val expected=mutableListOf<Pair<Int,Int>>()
        val old=LegendaryMath.renderHdr(p,sharp,d,.5f,5.6f,Cancel()){a,_->expected.add(a.w to a.h);a}
        val actual=mutableListOf<Pair<Int,Int>>();var delivered=0;var sdr:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,sharp,d,.5f,5.6f,Cancel(),onSdr={delivered++;sdr=it}){a,df->
            assertTrue(a.w<=p.width && a.h<=p.height);assertEquals(a.w,df.w);assertEquals(a.h,df.h)
            actual.add(a.w to a.h);a
        }
        assertEquals(expected,actual);assertTrue(actual.isNotEmpty());assertEquals(1,delivered)
        assertEquals(p.width,sdr!!.width);assertEquals(p.height,sdr!!.height)
        assertArrayEquals(old.data,out.data,0f);assertTrue(out.data.maxOrNull()!!>1f)
    }
    @Test fun combinedFocusedHdrPublishesOriginalSdrWithoutInference(){
        val p=planes();val h=frame(v=4f);var calls=0;var base:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,h,depth(),.5f,2.8f,Cancel(),onSdr={base=it}){a,_->calls++;a}
        assertSame(h,out);assertSame(p,base);assertEquals(0,calls)
    }
    @Test fun combinedHdrOpaqueSubjectPreservesBothSharpVersions(){
        val p=planes(v=100);val h=frame(v=4f);val mask=Frame(17,13,1,FloatArray(221){1f});var base:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,h,depth(v=.9f),.5f,2.8f,Cancel(),mask,onSdr={base=it},infer=identity)
        assertArrayEquals(h.data,out.data,1e-5f)
        for(c in 0..2)assertArrayEquals(p.plane(c),base!!.plane(c))
    }
    @Test fun combinedHdrCancelledNeverPublishesSdr(){
        var delivered=false
        try{LegendaryMath.renderHdrCombined(planes(),frame(),depth(),.5f,2.8f,Cancel().apply{cancelled=true},onSdr={delivered=true},infer=identity);fail()}catch(_:CancellationException){}
        assertFalse(delivered)
    }
    @Test fun combinedHdrObserverCannotMutateProduction(){
        val p=planes();val h=frame(v=3f);val d=depth(v=.7f);var passes=0
        val a=LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),infer=identity)
        val b=LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),onPass={passes++;assertEquals(p.width,it.w);assertEquals(p.height,it.h)},infer=identity)
        assertTrue(passes>0);assertArrayEquals(a.data,b.data,0f)
    }
    @Test fun combinedHdrRespects1024And2048AndOriginalWorkingSizes(){
        // Narrow fixtures exercise actual long-edge scheduling without a multi-megapixel test allocation.
        for(w in listOf(1024,2048,4096)){
            val p=planes(w,8,0);val sharp=frame(w,8,2f);val dims=mutableListOf<Pair<Int,Int>>()
            LegendaryMath.renderHdrCombined(p,sharp,depth(w,8,.51f),.5f,5.6f,Cancel(),onSdr={assertEquals(w,it.width);assertEquals(8,it.height)}){a,df->
                assertTrue(a.w<=w);assertTrue(a.h<=8);assertEquals(a.w,df.w);assertEquals(a.h,df.h)
                dims.add(a.w to a.h);a
            }
            assertTrue(dims.isNotEmpty());assertEquals(w to 8,dims.first())
        }
    }
    private fun exposureFixture():Planes=planes(25,19).also{p->
        for(c in 0..2)for(i in 0 until p.size)p.plane(c)[i]=(25+(i*17+c*31)%210).toByte()
        for(c in 0..2)p.plane(c)[9*25+12]=255.toByte()
    }
    private fun equalBytes(a:Planes,b:Planes){assertEquals(a.width,b.width);assertEquals(a.height,b.height);for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c))}
    @Test fun hdrGainMustNotChangeSdrExposure(){
        val p=exposureFixture();val d=depth(25,19,.7f)
        val ref=LegendaryMath.render(p,d,.5f,5.6f,Cancel(),infer=identity)
        for(gain in listOf(1f,2f,8f)){
            val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]*=gain
            var sdr:Planes?=null
            LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
            equalBytes(ref,checkNotNull(sdr))
        }
    }
    @Test fun hdrSdrSubjectEdgesMatchOriginalSdrCompositor(){
        val p=exposureFixture();val d=depth(25,19,.7f);val alpha=Frame(25,19,1,FloatArray(475){((it%25-5)/12f).coerceIn(0f,1f)})
        val ref=LegendaryMath.render(p,d,.5f,5.6f,Cancel(),subjectMask=alpha,infer=identity)
        val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]*=5f
        var sdr:Planes?=null
        LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),alpha,onSdr={sdr=it},infer=identity)
        equalBytes(ref,checkNotNull(sdr))
    }
    @Test fun darkSdrDoesNotAcquireHdrHighlightEnergy(){
        val p=planes(v=20);val d=depth(v=.7f);var sdr:Planes?=null
        val h=LegendaryMath.renderHdrCombined(p,frame(v=4f),d,.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
        equalBytes(LegendaryMath.render(p,d,.5f,5.6f,Cancel(),infer=identity),checkNotNull(sdr))
        assertTrue(h.data.maxOrNull()!!>1f)
    }
    @Test fun hdrOnlyPointLightNeverLeaksIntoSdrBase(){
        val p=planes(33,25,0);val h=frame(33,25);h.data[12*33+16]=32f
        var sdr:Planes?=null;val out=LegendaryMath.renderHdrCombined(p,h,depth(33,25,.7f),.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
        equalBytes(p,checkNotNull(sdr));assertTrue(out.data.any{it>0f})
    }
    @Test fun independentHdrOpticsCanCancelAfterSdrWithoutSecondModel(){
        val token=Cancel();var ready=false;var calls=0
        try{LegendaryMath.renderHdrCombined(planes(),frame(v=4f),depth(v=.7f),.5f,5.6f,token,onSdr={ready=true},progress={if(it.startsWith("HDR高光"))token.cancelled=true}){a,_->calls++;a};fail()}catch(_:CancellationException){}
        assertTrue(ready);assertTrue(calls>0)
    }
    @Test fun independentLightKeepsAllInputsImmutable(){
        val p=exposureFixture();val original=Array(3){p.plane(it).copyOf()};val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]*=3f
        val saved=h.data.copyOf();val d=depth(25,19,.7f);val dd=d.data.copyOf()
        LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),infer=identity)
        for(c in 0..2)assertArrayEquals(original[c],p.plane(c));assertArrayEquals(saved,h.data,0f);assertArrayEquals(dd,d.data,0f)
    }
    @Test fun attenuationKeepsIndependentSdrAndHdrEnergy(){
        val p=planes(v=80);val h=frame(v=.01f);var sdr:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,h,depth(v=.7f),.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
        equalBytes(p,checkNotNull(sdr));for(v in out.data)assertEquals(.01f,v,1e-5f)
    }
    @Test fun pairedRenderMemoryRetainsSdrBudgetWithoutOverflow(){
        assertEquals(HdrMemory.renderHeap(2048,1536)+2048L*1536*48,HdrMemory.pairedRenderHeap(2048,1536))
        assertTrue(HdrMemory.pairedRenderHeap(10000,5000)>Int.MAX_VALUE)
    }
    @Test fun attenuationCannotChangeNetworkInputOrSdrWithNonIdentityModel(){
        val p=exposureFixture();val d=depth(25,19,.7f)
        val mask=Frame(25,19,1,FloatArray(p.size){((it%25-5)/12f).coerceIn(0f,1f)})
        for(alpha in listOf<Frame?>(null,mask)){
            val inputs=mutableListOf<FloatArray>()
            val net:(Frame,Frame)->Frame={a,_->Frame(a.w,a.h,3,FloatArray(a.data.size){i->(a.data[i]*.79f+.037f).coerceIn(0f,1f)})}
            val reference=LegendaryMath.render(p,d,.5f,5.6f,Cancel(),subjectMask=alpha){a,b->inputs.add(a.data.copyOf());net(a,b)}
            for(gain in listOf(0f,.25f,1f,8f)){
                val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]*=gain
                var count=0;var sdr:Planes?=null
                LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),alpha,onSdr={sdr=it}){a,b->
                    assertArrayEquals(inputs[count++],a.data,0f);net(a,b)
                }
                assertEquals(inputs.size,count);equalBytes(reference,checkNotNull(sdr))
            }
        }
    }
    @Test fun nearNeutralHdrCannotRoundDownSdrNetworkInput(){
        val p=planes(v=80);val h=HdrMath.linear(p);for(i in h.data.indices)h.data[i]=Math.nextDown(h.data[i])
        var calls=0
        LegendaryMath.renderHdrCombined(p,h,depth(v=.7f),.5f,5.6f,Cancel()){a,_->
            calls++;for(v in a.data)assertEquals(80/255f,v,0f);a
        };assertTrue(calls>0)
    }
    @Test fun signedHdrResidualMatchesIndependentOpticalReference(){
        val p=planes(33,25,50);val h=HdrMath.linear(p);val base=HdrMath.linear(p);val n=p.size
        for(c in 0..2)for(i in 0 until n)h.data[c*n+i]=if(i%33<16)base.data[c*n+i]*.2f else base.data[c*n+i]+2f
        val d=depth(33,25,.7f);val coc=FullFrameOptics.coc(d,.5f,5.6f,33,25,Cancel())
        val pos=Frame(33,25,3,FloatArray(h.data.size){max(0f,h.data[it]-base.data[it])})
        val neg=Frame(33,25,3,FloatArray(h.data.size){max(0f,base.data[it]-h.data[it])})
        val plus=SoftBokehEdges.soften(HighlightScatter.optical(pos,coc,5.6f,Cancel()),coc,Cancel())
        val minus=SoftBokehEdges.soften(HighlightScatter.optical(neg,coc,5.6f,Cancel()),coc,Cancel())
        val expected=FloatArray(h.data.size){max(0f,base.data[it]+plus.data[it]-minus.data[it])}
        var sdr:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,h,d,.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
        assertArrayEquals(expected,out.data,1e-6f);equalBytes(p,checkNotNull(sdr))
    }
    @Test fun hdrDeficitMovesWithDefocusRatherThanStayingAtSourcePixel(){
        val p=planes(33,25,60);val h=HdrMath.linear(p);val at=12*33+16;val original=h.data[at]
        for(c in 0..2)h.data[c*h.size+at]=0f
        val out=LegendaryMath.renderHdrCombined(p,h,depth(33,25,.8f),.5f,.95f,Cancel(),infer=identity)
        assertTrue(out.data[at]>0f);assertTrue(out.data[at+1]<original)
        assertTrue(out.data.all{it.isFinite() && it>=0f})
    }
    @Test fun fullyAttenuatedHdrRemainsBlackWithoutChangingSdr(){
        val p=planes(v=80);var sdr:Planes?=null
        val out=LegendaryMath.renderHdrCombined(p,frame(v=0f),depth(v=.7f),.5f,5.6f,Cancel(),onSdr={sdr=it},infer=identity)
        equalBytes(p,checkNotNull(sdr));for(v in out.data)assertEquals(0f,v,1e-6f)
    }
    @Test fun invalidJpegCannotBePackaged(){reject{UltraHdrContainer.write(ByteArrayOutputStream(),byteArrayOf(1,2),byteArrayOf(3,4),HdrMath.encode(planes(),frame(),Cancel()))}}
}
