package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.util.concurrent.CancellationException
import kotlin.math.*

class SoftBokehEdgesTest {
    private fun srgb(x:Float)=if(x<=.0031308f)12.92f*x else 1.055f*x.pow(1/2.4f)-.055f
    private fun point(w:Int,h:Int,v:Float=1f):Frame{val f=Frame(w,h,3,FloatArray(w*h*3));val i=h/2*w+w/2;for(c in 0..2)f.data[c*w*h+i]=v;return f}
    private fun row(f:Frame)=FloatArray(f.w){f.data[f.h/2*f.w+it]}
    private fun crossing(r:FloatArray,center:Int):Int{val half=r[center]*.5f;for(x in center until r.size)if(r[x]<half)return x;return r.size}
    private fun maxStep(r:FloatArray)=(1 until r.size).maxOf{abs(r[it]-r[it-1])}

    @Test fun nearFocusPixelsAreBitIdentical(){
        val r=java.util.Random(3);val f=Frame(41,33,3,FloatArray(41*33*3){r.nextFloat()*2})
        val c=Frame(41,33,1,FloatArray(41*33){r.nextFloat()*4-2}) // half-width <= .48 px
        assertArrayEquals(f.data,SoftBokehEdges.soften(f,c,Cancel()).data,0f)
    }
    @Test fun constantLightWithChangingDepthStaysConstant(){
        val r=java.util.Random(9);val f=Frame(37,29,3,FloatArray(37*29*3){.37f})
        val c=Frame(37,29,1,FloatArray(37*29){r.nextFloat()*24-12})
        assertTrue(SoftBokehEdges.soften(f,c,Cancel()).data.all{abs(it-.37f)<2e-6f})
    }
    @Test fun discRimIsSoftButDiscSizeAndEnergyAreKept(){
        val w=161;val high=point(w,w,5f);val coc=Frame(w,w,1,FloatArray(w*w){12f})
        val hard=HighlightScatter.optical(high,coc,.95f,Cancel());val soft=SoftBokehEdges.soften(hard,coc,Cancel())
        val a=row(hard);val b=row(soft)
        assertTrue("disc size ${crossing(a,w/2)} vs ${crossing(b,w/2)}",abs(crossing(a,w/2)-crossing(b,w/2))<=1)
        assertTrue("rim ${maxStep(b)} vs ${maxStep(a)}",maxStep(b)<.5f*maxStep(a))
        assertEquals(1f,b[w/2]/a[w/2],.02f) // plateau kept (field PSF is not exactly flat)
        val ea=hard.data.sumOf{it.toDouble()};val eb=soft.data.sumOf{it.toDouble()}
        assertEquals(1.0,eb/ea,1e-4)
    }
    @Test fun sharpLightNeverBleedsIntoDefocusedNeighbour(){
        val w=48;val h=20;val n=w*h;val f=Frame(w,h,3,FloatArray(n*3));val c=Frame(w,h,1,FloatArray(n))
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x;if(x<=20){if(x>=18)for(ch in 0..2)f.data[ch*n+i]=4f}else c.data[i]=10f}
        val out=SoftBokehEdges.soften(f,c,Cancel())
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x;for(ch in 0..2)assertEquals(f.data[ch*n+i],out.data[ch*n+i],0f)}
    }
    @Test fun softenNeverMutatesInputs(){
        val r=java.util.Random(5);val f=Frame(23,19,3,FloatArray(23*19*3){r.nextFloat()});val c=Frame(23,19,1,FloatArray(23*19){r.nextFloat()*16-8})
        val a=f.data.copyOf();val b=c.data.copyOf();SoftBokehEdges.soften(f,c,Cancel());assertArrayEquals(a,f.data,0f);assertArrayEquals(b,c.data,0f)
    }
    @Test fun softenCancels(){val f=point(9,9);try{SoftBokehEdges.soften(f,Frame(9,9,1,FloatArray(81){8f}),Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun shoulderIsExactBelowKneeMonotoneAndNeverHardClips(){
        for(b in listOf(0f,.3f,.79f,.9f,1f,1.2f))assertEquals(b,SoftBokehEdges.shoulder(b,0f),0f)
        assertEquals(.7f,SoftBokehEdges.shoulder(.5f,.2f),0f)
        for(b in listOf(0f,.4f,.85f,.99f)){var last=-1f;var l=0f
            while(l<20f){val v=SoftBokehEdges.shoulder(b,l);assertTrue(v>=last);if(b<1f)assertTrue("b=$b l=$l v=$v",v<=1f);last=v;l+=.01f}}
        // C1 at the knee: slope just above equals 1.
        val e=1e-3f;assertEquals(1f,(SoftBokehEdges.shoulder(.5f,.3f+e)-SoftBokehEdges.shoulder(.5f,.3f))/e,.01f)
        assertTrue(SoftBokehEdges.shoulder(.6f,.5f)<.99f)
    }
    private fun render(base:Float,high:Frame,coc:Frame,natural:Boolean):Planes{
        // One bright source byte so renderCoc enters the highlight path; splitter is overridden.
        val w=high.w;val h=high.h;val p=Planes(w,h,ByteArray(w*h){60},ByteArray(w*h){60},ByteArray(w*h){60});p.p0[0]=255.toByte()
        val b=Frame(w,h,3,FloatArray(w*h*3){srgb(base)})
        return LegendaryMath.renderCoc(p,coc,.95f,Cancel(),highlightSplitter={_,_->b to high},
            highlightRenderer=if(natural)null else {hi,c,ap,t->HighlightScatter.optical(hi,c,ap,t)}){rgb,_->rgb}
    }
    @Test fun denseBrightGapsNoLongerClipToFlatWhite(){
        val w=121;val n=w*w;val high=Frame(w,w,3,FloatArray(n*3))
        for(y in 4 until w-4 step 4)for(x in 4 until w-4 step 4)for(dy in 0..2)for(dx in 0..2){val i=(y+dy)*w+x+dx;for(c in 0..2)high.data[c*n+i]=.94f}
        val coc=Frame(w,w,1,FloatArray(n){6f})
        val old=render(.6f,high,coc,false);val now=render(.6f,high,coc,true)
        fun white(p:Planes)=(0 until n).count{(p.p0[it].toInt() and 255)==255}
        println("dense gaps white pixels old=${white(old)} new=${white(now)}")
        assertTrue(white(old)>n/4);assertEquals(0,white(now))
    }
    @Test fun isolatedLightRimIsSofterInProductionComposite(){
        val w=161;val n=w*w;val high=point(w,w,40f);val coc=Frame(w,w,1,FloatArray(n){12f})
        val old=render(0f,high,coc,false);val now=render(0f,high,coc,true)
        fun line(p:Planes)=FloatArray(w){(p.p1[w/2*w+it].toInt() and 255).toFloat()}
        val a=line(old);val b=line(now)
        println("isolated rim max byte step old=${maxStep(a)} new=${maxStep(b)}")
        assertTrue(maxStep(b)<.6f*maxStep(a));assertTrue(abs(crossing(a,w/2)-crossing(b,w/2))<=2)
    }
}
