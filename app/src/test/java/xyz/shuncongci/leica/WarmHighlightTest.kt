package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*
import java.util.concurrent.CancellationException

class WarmHighlightTest {
    private fun lamp(r:Int=130,g:Int=95,b:Int=15,w:Int=161,h:Int=121)=Planes(w,h).apply{for(y in h/2-2..h/2+2)for(x in w/2-2..w/2+2){val i=y*w+x;p0[i]=r.toByte();p1[i]=g.toByte();p2[i]=b.toByte()}}
    private fun lin(v:Float)=if(v<=.04045f)v/12.92f else ((v+.055f)/1.055f).pow(2.4f)
    @Test fun amberBelowOld136GateNowHasOpticalEnergy(){val p=lamp();assertTrue(p.p0.all{(it.toInt() and 255)<=136});val (_,hi)=LegendaryMath.split(p,Cancel());assertTrue(hi.data.take(hi.size).sum()>.1f);assertTrue(hi.data.slice(hi.size until 2*hi.size).sum()>0f)}
    @Test fun warmerOrangeAndDimYellowAreDetected(){for(rgb in listOf(listOf(120,85,15),listOf(135,105,20),listOf(130,70,10))){val (_,hi)=LegendaryMath.split(lamp(rgb[0],rgb[1],rgb[2]),Cancel());assertTrue(hi.data.any{it>0f})}}
    @Test fun hueDoesNotGateEqualPeakLights(){for(rgb in listOf(listOf(130,30,20),listOf(30,130,20),listOf(20,30,130),listOf(130,100,20))){assertTrue(LegendaryMath.split(lamp(rgb[0],rgb[1],rgb[2]),Cancel()).second.data.any{it>0})}}
    @Test fun flatWarmSurfaceDoesNotBecomeAnOpticalLamp(){val p=Planes(31,23,ByteArray(713){130.toByte()},ByteArray(713){95},ByteArray(713){15});val caps=HighlightExtraction.caps(p,Cancel());assertTrue(caps.all{it==.25f});assertTrue(LegendaryMath.split(p,Cancel()).second.data.all{it==0f})}
    @Test fun darkSceneRetainsZeroHighlights(){assertTrue(LegendaryMath.split(lamp(60,40,20),Cancel()).second.data.all{it==0f})}
    @Test fun splitConservesEachLinearChannel(){val p=lamp();val (base,hi)=LegendaryMath.split(p,Cancel());for(c in 0..2)for(i in 0 until p.size){val original=lin((p.plane(c)[i].toInt() and 255)/255f);assertEquals(original,lin(base.data[c*p.size+i])+hi.data[c*p.size+i],.000001f)}}
    @Test fun colorRatiosAreRetainedWithinByteQuantization(){val p=lamp();val (_,hi)=LegendaryMath.split(p,Cancel());val i=p.height/2*p.width+p.width/2;val expected=lin(130/255f)/lin(95/255f);assertEquals(expected,hi.data[i]/hi.data[p.size+i],.05f);assertTrue(hi.data[i]>hi.data[p.size+i])}
    @Test fun warmLightActuallyEntersProductionScatter(){val p=lamp();val c=Frame(p.width,p.height,1,FloatArray(p.size){4f});var calls=0;val out=LegendaryMath.renderCoc(p,c,.95f,Cancel(),highlightRenderer={hi,coc,ap,token->calls++;HighlightScatter.optical(hi,coc,ap,token)}){image,_->image};assertEquals(1,calls);assertTrue((out.p0[p.height/2*p.width+p.width/2+8].toInt() and 255)>0)}
    @Test fun focusedYellowLightIsByteExact(){val p=lamp();val out=LegendaryMath.renderCoc(p,Frame(p.width,p.height,1,FloatArray(p.size)),.95f,Cancel(),highlightRenderer={_,_,_,_->error("focus")}){_,_->error("focus")};assertSame(p,out)}
    @Test fun fullyProtectedYellowIsByteExact(){val p=lamp();val alpha=Frame(p.width,p.height,1,FloatArray(p.size){1f});val out=LegendaryMath.render(p,Frame(p.width,p.height,1,FloatArray(p.size){.8f}),.2f,.95f,Cancel(),subjectMask=alpha){_,_->error("protected")};for(c in 0..2)assertArrayEquals(p.plane(c),out.plane(c))}
    @Test fun extractionNeverMutatesPhoto(){val p=lamp();val before=(0..2).map{p.plane(it).copyOf()};LegendaryMath.split(p,Cancel());for(c in 0..2)assertArrayEquals(before[c],p.plane(c))}
    @Test fun bordersAndTinyFramesStayFinite(){for(w in listOf(1,2,7,31))for(h in listOf(1,2,19)){val p=Planes(w,h,ByteArray(w*h){120},ByteArray(w*h){80},ByteArray(w*h){30});val caps=HighlightExtraction.caps(p,Cancel());assertTrue(caps.all{it.isFinite() && it in 0.0599f..0.2501f})}}
    @Test(expected=CancellationException::class) fun extractionHonorsCancellation(){HighlightExtraction.caps(lamp(),Cancel().apply{cancelled=true})}
}
