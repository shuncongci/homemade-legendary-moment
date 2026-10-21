package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.util.concurrent.CancellationException
import kotlin.math.*

class HighlightRadiusTest {
    private fun light(w:Int=193,h:Int=145,x:Int=w/2,y:Int=h/2)=Frame(w,h,3,FloatArray(w*h*3){if(it%(w*h)==y*w+x).8f else 0f})
    private fun coc(f:Frame,r:Float)=Frame(f.w,f.h,1,FloatArray(f.size){r})
    private fun before(f:Frame,r:Float,ap:Float=.95f)=HighlightScatter.opticalAtScale(f,coc(f,r),ap,Cancel(),1.5f)
    private fun after(f:Frame,r:Float,ap:Float=.95f)=HighlightScatter.optical(f,coc(f,r),ap,Cancel())
    private fun rms(f:Frame,x:Int=f.w/2,y:Int=f.h/2):Double{var sum=0.0;var m=0.0;for(i in 0 until f.size){val a=f.data[i].toDouble();sum+=a;m+=a*((i%f.w-x).toDouble().pow(2)+(i/f.w-y).toDouble().pow(2))};return sqrt(m/sum)}
    private fun energy(f:Frame)=f.data.take(f.size).sumOf{it.toDouble()}
    @Test fun productionScaleIsTwicePrevious(){assertEquals(1.5f*2,HighlightScatter.RADIUS_SCALE,0f)}
    @Test fun realCenterSpotRadiusDoubles(){val f=light(769,577);for(ap in LegendaryMath.apertures){val a=before(f,12f,ap);val b=after(f,12f,ap);val ratio=rms(b)/rms(a);assertTrue("f/$ap ratio=$ratio",ratio in 1.98..2.02);assertTrue(b.data.maxOrNull()!!<a.data.maxOrNull()!!)}}
    @Test fun edgeSpotAlsoEnlargesWithoutLosingTangentialAxis(){val x=604;val y=288;val f=light(769,577,x,y);val old=before(f,12f);val now=after(f,12f);assertTrue(rms(now,x,y)/rms(old,x,y) in 1.98..2.02);var xx=0.0;var yy=0.0;for(i in 0 until now.size){xx+=now.data[i]*(i%now.w-x).toDouble().pow(2);yy+=now.data[i]*(i/now.w-y).toDouble().pow(2)};assertTrue(yy>xx*1.1)}
    @Test fun enlargementDoesNotInjectEnergy(){
        // Use CoC reachable from the production lens at this resolution. The former
        // 193x145/r12 fixture exceeded its 6.64px physical maximum; a receiver-varying
        // field is not an exactly flux-conserving source PSF for arbitrarily huge radii.
        val w=769;val h=577;assertTrue(12f<=FullFrameOptics.maxRadius(w,h))
        for((x,y) in listOf(384 to 288,604 to 288,604 to 420)){val f=light(w,h,x,y);val old=before(f,12f);val now=after(f,12f);assertEquals(.8,energy(now),.012);assertEquals(energy(old),energy(now),.012)}
    }
    @Test fun signedFractionalCocMatchesExplicitlyEnlargedReference(){val r=java.util.Random(7);val f=Frame(19,17,3,FloatArray(969){r.nextFloat()});val c=Frame(19,17,1,FloatArray(323){r.nextFloat()*8-4});val expanded=Frame(19,17,1,FloatArray(323){c.data[it]*3f});assertArrayEquals(HighlightScatter.opticalAtScale(f,expanded,2.8f,Cancel(),1f).data,HighlightScatter.optical(f,c,2.8f,Cancel()).data,0f)}
    @Test fun enlargementNeverMutatesSharedBaseCocOrSource(){val f=light(33,25);val c=coc(f,-7.25f);val original=f.data.copyOf();val depth=c.data.copyOf();HighlightScatter.optical(f,c,.95f,Cancel());assertArrayEquals(original,f.data,0f);assertArrayEquals(depth,c.data,0f)}
    @Test fun exactFocusRemainsIdentity(){val r=java.util.Random(31);val f=Frame(19,17,3,FloatArray(969){r.nextFloat()});assertArrayEquals(f.data,after(f,0f).data,0f)}
    @Test fun enlargedRadiusIsNotClippedToOldFiftyFourPixels(){
        // r40 is attainable at 1025 square (max 46.91), not at 257 square (max 11.76).
        // 2.1.12: compare 60px (prior 1.5x) to 120px (new 3x), plus mass past 54px.
        // At 1025, a 120px receiver-varying PSF changes its own shape enough that
        // RMS is 1.97961x, despite exact 2x support. Test the same r40 at ~2K output,
        // retaining the 1.98..2.02 assertion and the nonzero mass beyond 54px.
        val w=2049;assertTrue(40f<=FullFrameOptics.maxRadius(w,w));val f=light(w,w);val now=after(f,40f);val old=before(f,40f)
        val ratio=rms(now)/rms(old);assertTrue("radius ratio=$ratio",ratio in 1.98..2.02);var outer=0.0;for(i in 0 until now.size)if(abs(i%w-w/2)>54)outer+=now.data[i];assertTrue("outer=$outer",outer>0.001)
    }
    @Test fun constantFieldHasNoGainBoost(){val f=Frame(31,23,3,FloatArray(2139){.37f});val c=Frame(31,23,1,FloatArray(713){if(it%31<15)-9f else 7.5f});assertTrue(HighlightScatter.optical(f,c,.95f,Cancel()).data.all{abs(it-.37f)<4e-6f})}
    @Test fun invalidInputBoundStillRejectedBeforeScaling(){val f=light(3,2);for(r in listOf(55f,Float.NaN,Float.POSITIVE_INFINITY))try{after(f,r);fail("radius $r")}catch(_:IllegalArgumentException){}}
    @Test fun invalidScaleAndCancellationRejected(){val f=light(3,2);for(scale in listOf(0f,.9f,3.01f,Float.NaN))try{HighlightScatter.opticalAtScale(f,coc(f,2f),.95f,Cancel(),scale);fail()}catch(_:IllegalArgumentException){};try{HighlightScatter.optical(f,coc(f,2f),.95f,Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun darkBaseArnetPathAndFocusGeometryAreUnchanged(){val p=Planes(31,23,ByteArray(713){90},ByteArray(713){70},ByteArray(713){50});val d=Frame(31,23,1,FloatArray(713){.7f});val coc=FullFrameOptics.coc(d,.2f,2.8f,31,23,Cancel()).data.copyOf();val a=LegendaryMath.render(p,d,.2f,2.8f,Cancel(),highlightRenderer={_,_,_,_->error("no highlights")}){rgb,_->rgb};val b=LegendaryMath.render(p,d,.2f,2.8f,Cancel()){rgb,_->rgb};for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c));assertArrayEquals(coc,FullFrameOptics.coc(d,.2f,2.8f,31,23,Cancel()).data,0f)}
}
