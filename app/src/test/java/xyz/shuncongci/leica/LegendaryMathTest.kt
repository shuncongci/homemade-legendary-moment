package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import kotlin.math.*

class LegendaryMathTest {
    private fun load(name: String): Planes {
        val stream=javaClass.classLoader!!.getResourceAsStream("legendary/$name.rgb") ?: error("Missing $name")
        return DataInputStream(stream).use { s ->
            val w=s.readInt();val h=s.readInt();val p=Planes(w,h)
            for(i in 0 until p.size) for(c in 0..2)p.plane(c)[i]=s.readByte()
            p
        }
    }
    private fun assertImage(a: Planes,b: Planes,maxAllowed: Int,meanAllowed: Double) {
        assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        var sum=0L;var largest=0
        for(c in 0..2) for(i in 0 until a.size) { val d=abs((a.plane(c)[i].toInt() and 255)-(b.plane(c)[i].toInt() and 255));sum+=d;largest=max(largest,d) }
        val mean=sum/(a.size*3.0);println("legendary image max=$largest mean=$mean")
        assertTrue("max=$largest mean=$mean",largest<=maxAllowed && mean<=meanAllowed)
    }
    private fun depth(p: Planes)=LegendaryMath.Frame(p.width,p.height,1,FloatArray(p.size){(it%p.width).toFloat()/(p.width-1)})
    @Test fun allSizesFuseOriginalDetails() {
        assertTrue(LeicaSizes.usesFusion(360,480));assertTrue(LeicaSizes.usesFusion(1024,1024))
        assertTrue(LeicaSizes.usesFusion(12000,16000))
    }
    @Test fun manualFocusUncertainDoesNotReselect() {
        val ramp=LegendaryMath.Frame(300,200,1,FloatArray(60000){(it%300)/299f})
        assertEquals(.50167f,LegendaryMath.focus(ramp,LegendaryMath.Point(.5f,.5f))!!,.005f)
        val edge=LegendaryMath.Frame(300,200,1,FloatArray(60000){if(it%300<150)0f else 1f})
        assertNull(LegendaryMath.focus(edge,LegendaryMath.Point(.5f,.5f)))
        assertNull(LegendaryMath.focus(LegendaryMath.Frame(10,10,1,FloatArray(100){.5f}),LegendaryMath.Point(.5f,.5f)))
    }
    @Test fun percentileNormalizationAndGains() {
        val a=LegendaryMath.Frame(101,1,1,FloatArray(101){it.toFloat()})
        val d=LegendaryMath.normalize(a);assertEquals(0f,d.data[0],0f);assertEquals(1f,d.data[100],0f);assertEquals(.5f,d.data[50],1e-6f)
        assertEquals(53.33333f,LegendaryMath.gain(.95f),1e-4f)
        assertEquals(15.12f,LegendaryMath.gain(2.8f),1e-5f)
    }
    // Historical V1 output is not the new compositor acceptance oracle. New V4 has independent OpenCV fixtures.
    @Test fun legacyApertureHighlightsMatchReference() {
        val p=load("input")
        for(ap in floatArrayOf(2.8f,5.6f)) {
            val out=LegacyRangeRenderer.render(p,depth(p),.5f,ap,Cancel(),highlightRenderer=LegacyHighlightScatter::scatter){image,_ -> image}
            assertImage(out,load("high-$ap"),1,.03)
        }
    }
    @Test fun wholeFrameMultiscaleMatchesReference() {
        val p=load("tile-input");var calls=0
        val out=LegacyRangeRenderer.render(p,depth(p),.5f,.95f,Cancel(),highlightRenderer=LegacyHighlightScatter::scatter){image,df ->
            calls++;assertEquals(image.w,df.w);assertTrue(df.data.all{it in -1f..1f});image
        }
        assertEquals(3,calls);assertImage(out,load("tile-expected"),1,.08)
    }
    @Test fun cancellationStopsBeforeNetwork() {
        val p=load("input");var calls=0
        try { LegacyRangeRenderer.render(p,depth(p),.5f,2.8f,Cancel().apply{cancelled=true}){im,_ -> calls++;im};fail("cancel missing") }
        catch(e: java.util.concurrent.CancellationException){assertEquals(0,calls)}
    }
    @Test fun scatterLevelsAvoidPixelBoxing() {
        assertArrayEquals(intArrayOf(-3,0,2,53),LegacyHighlightScatter.uniqueLevels(intArrayOf(2,0,2,-3,53,0,-3)))
        assertArrayEquals(IntArray(0),LegacyHighlightScatter.uniqueLevels(IntArray(0)))
        assertArrayEquals(intArrayOf(2),LegacyHighlightScatter.uniqueLevels(IntArray(1_000_000){2}))
    }
    private fun reflect(x0: Int,n: Int): Int { if(n==1)return 0;var x=x0;while(x<0 || x>=n) x=if(x<0)-x else 2*n-2-x;return x }
    /** The original direct per-tap gather; the fast scatter must reproduce it. */
    private fun referenceScatter(high: LegendaryMath.Frame,coc: LegendaryMath.Frame,aperture: Float): LegendaryMath.Frame {
        val bins=IntArray(coc.size){Math.rint(coc.data[it].toDouble()).toInt()};val levels=LegacyHighlightScatter.uniqueLevels(bins)
        val color=LegendaryMath.Frame(high.w,high.h,3,FloatArray(high.data.size));val coverage=FloatArray(high.size)
        for(level in levels) {
            val k=LegendaryMath.kernel(abs(level),aperture)
            for(y in 0 until high.h) for(x in 0 until high.w) {
                var alpha=0f;var r=0f;var g=0f;var b=0f
                for(ky in 0 until k.w) for(kx in 0 until k.w) {
                    val weight=k.data[ky*k.w+kx];if(weight==0f)continue
                    val i=reflect(y+ky-k.half,high.h)*high.w+reflect(x+kx-k.half,high.w)
                    if(bins[i]!=level)continue
                    alpha+=weight;r+=high.data[i]*weight;g+=high.data[high.size+i]*weight;b+=high.data[2*high.size+i]*weight
                }
                val i=y*high.w+x;val keep=1-alpha.coerceIn(0f,1f)
                color.data[i]=r+color.data[i]*keep;color.data[high.size+i]=g+color.data[high.size+i]*keep;color.data[2*high.size+i]=b+color.data[2*high.size+i]*keep
                coverage[i]=alpha.coerceIn(0f,1f)+coverage[i]*keep
            }
        }
        for(c in 0..2) for(i in 0 until high.size) color.data[c*high.size+i]=if(coverage[i]>1e-6f)color.data[c*high.size+i]/coverage[i] else 0f
        return color
    }
    @Test fun legacyFastScatterMatchesDirectGather() {
        val random=java.util.Random(7)
        for((w,h,maxCoc) in listOf(Triple(41,29,9f),Triple(5,7,12f),Triple(64,48,20f),Triple(1,9,4f))) for(ap in LegendaryMath.apertures) {
            val n=w*h
            val high=LegendaryMath.Frame(w,h,3,FloatArray(3*n){if(random.nextFloat()<.15f)random.nextFloat()*1.5f else 0f})
            // Smooth depth ramp plus noise: realistic contiguous CoC bands with ragged edges.
            val coc=LegendaryMath.Frame(w,h,1,FloatArray(n){((it%w).toFloat()/max(1,w-1)*2-1)*maxCoc+random.nextFloat()*2-1})
            val expected=referenceScatter(high,coc,ap);val actual=LegacyHighlightScatter.scatter(high,coc,ap,Cancel())
            for(i in expected.data.indices) assertEquals("w=$w h=$h f=$ap i=$i",expected.data[i],actual.data[i],2e-4f)
        }
    }
    @Test fun fastScatterHandles2048WideApertureInSeconds() {
        val w=2048;val h=1536;val n=w*h;val random=java.util.Random(3)
        val high=LegendaryMath.Frame(w,h,3,FloatArray(3*n){if(random.nextFloat()<.05f)random.nextFloat() else 0f})
        val coc=LegendaryMath.Frame(w,h,1,FloatArray(n){((it%w).toFloat()/(w-1)*2-1)*53.3f})
        val start=System.nanoTime();LegendaryMath.scatter(high,coc,.95f,Cancel())
        val seconds=(System.nanoTime()-start)/1e9;println("fast scatter 2048x1536 f/0.95: ${"%.2f".format(seconds)} s")
        assertTrue("too slow: $seconds s",seconds<120)
    }
}
