package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class DepthVisualTest {
    @Test fun grayAndHeatShareDepthOrientationAndNeverMutateInput(){
        val d=LegendaryMath.Frame(101,21,1,FloatArray(2121){(it%101)/100f});val old=d.data.copyOf();val v=DepthVisualMath.create(d,Cancel())
        assertEquals(0xff000000.toInt(),v.gray[0]);assertEquals(0xffffffff.toInt(),v.gray[100]);assertEquals(DepthVisualMath.heat(0f),v.thermal[0]);assertEquals(DepthVisualMath.heat(1f),v.thermal[100]);assertArrayEquals(old,d.data,0f)
    }
    @Test fun visualizationSizeIsUiOnlyBounded(){
        val d=LegendaryMath.Frame(1200,1600,1,FloatArray(1200*1600){.5f});val v=DepthVisualMath.create(d,Cancel());assertEquals(960,max(v.w,v.h));assertEquals(1200,d.w);assertEquals(1600,d.h);assertEquals(24,max(v.tiles.cols,v.tiles.rows))
    }
    @Test fun reliefTilesUseMeanOfModelPixelsNotRandomDepth(){
        val d=LegendaryMath.Frame(96,96,1,FloatArray(9216){if(it%96<48).2f else .8f});val v=DepthVisualMath.create(d,Cancel())
        for(y in 0 until v.tiles.rows)for(x in 0 until v.tiles.cols)assertEquals(if(x<12).2f else .8f,v.tiles.depth[y*24+x],1e-6f)
    }
    @Test fun loadingHasBothDirectionsAndNonsequentialPhases(){
        val z=(0..143).map{DepthVisualMath.loadingZ(it,.13f)};assertTrue(z.any{it>.3f});assertTrue(z.any{it<-.3f});assertTrue(z.all{abs(it)<=.46f});assertTrue(z.zipWithNext().any{it.first>it.second});assertTrue(z.zipWithNext().any{it.first<it.second})
    }
    @Test fun loadingCyclesContinuous(){for(i in 0..143)assertEquals(DepthVisualMath.loadingZ(i,0f),DepthVisualMath.loadingZ(i,1f),1e-6f)}
    @Test fun perspectiveMovesDepthAlongSideView(){
        val a=FloatArray(3);val b=FloatArray(3);DepthVisualMath.project(0f,0f,-.4f,.38f,-.14f,a);DepthVisualMath.project(0f,0f,.4f,.38f,-.14f,b)
        assertTrue(a[0]<b[0]);assertTrue(a[2]<b[2]);assertTrue(a.all{it.isFinite()} && b.all{it.isFinite()})
    }
    @Test fun fullCameraInteractionRangeFinite(){
        val p=FloatArray(3);for(yaw in listOf(-.85f,0f,.85f))for(pitch in listOf(-.55f,0f,.55f))for(x in listOf(-.5f,.5f))for(y in listOf(-.5f,.5f))for(z in listOf(-.475f,.475f)){DepthVisualMath.project(x,y,z,yaw,pitch,p);assertTrue(p.all{it.isFinite() && abs(it)<2})}
    }
    @Test fun gridPreservesPortraitLandscapeAndTinyImages(){
        assertEquals(18 to 24,DepthVisualMath.grid(3000,4000,24));assertEquals(24 to 18,DepthVisualMath.grid(4000,3000,24))
        for((w,h) in listOf(1 to 1,1 to 23,23 to 1)){val d=LegendaryMath.Frame(w,h,1,FloatArray(w*h){.4f});assertTrue(DepthVisualMath.create(d,Cancel()).tiles.depth.all{abs(it-.4f)<1e-6f})}
    }
    @Test fun invalidOrCancelledDepthNeverPublished(){
        try{DepthVisualMath.create(LegendaryMath.Frame(1,1,1,floatArrayOf(Float.NaN)),Cancel());fail()}catch(_:IllegalArgumentException){}
        try{DepthVisualMath.create(LegendaryMath.Frame(1,1,1,floatArrayOf(.5f)),Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}
    }
    @Test fun paletteIsOpaqueAndContinuous(){var prev=DepthVisualMath.heat(0f);for(i in 1..10000){val c=DepthVisualMath.heat(i/10000f);assertEquals(255,c ushr 24);for(shift in listOf(0,8,16))assertTrue(abs(((c shr shift) and 255)-((prev shr shift) and 255))<=1);prev=c}}
}
