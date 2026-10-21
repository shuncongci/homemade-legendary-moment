package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** 2.1.18: clipped-light recovery, clean background plate, render-resolution edge tightening.
 * Synthetic scenes only; not a replay of the user's photos. */
class CleanPlateBokehTest {
    private fun v(p:Planes,i:Int,ch:Int=0)=p.plane(ch)[i].toInt() and 255
    private fun flat(w:Int,h:Int,value:Int)=Planes(w,h).also{p->for(c in 0..2)p.plane(c).fill(value.toByte())}
    private fun lamp(w:Int,h:Int,value:Int,cx:Int,cy:Int,p:Planes=flat(w,h,25)):Planes{for(y in cy-1..cy+1)for(x in cx-1..cx+1)for(c in 0..2)p.plane(c)[y*w+x]=value.toByte();return p}
    private fun render(p:Planes,coc:Float,recover:Boolean)=LegendaryMath.renderCoc(p,Frame(p.width,p.height,1,FloatArray(p.size){coc}),.95f,Cancel(),clippedRecovery=recover){rgb,_->rgb}

    @Test fun clippedDefocusedLampBecomesBrightDiscAndFarPixelsStayExact(){
        val w=121;val p=lamp(w,w,255,60,60)
        val old=render(p,8f,false);val next=render(p,8f,true)
        val near=60*w+72;val center=60*w+60
        println("clipped lamp disc r=12: old=${v(old,near)} new=${v(next,near)} center old=${v(old,center)} new=${v(next,center)}")
        assertTrue(v(next,near)>=v(old,near)+40)
        assertTrue(v(next,center)<255)
        for(y in 0 until w)for(x in 0 until w)if(hypot(x-60.0,y-60.0)>40)for(c in 0..2)assertEquals(v(old,y*w+x,c),v(next,y*w+x,c))
    }
    @Test fun unclippedLightAndInFocusLightAreBitIdentical(){
        val w=121;val dim=lamp(w,w,230,60,60)
        for(c in 0..2)assertArrayEquals(render(dim,8f,false).plane(c),render(dim,8f,true).plane(c))
        val clipped=lamp(w,w,255,60,60)
        for(c in 0..2)assertArrayEquals(render(clipped,.6f,false).plane(c),render(clipped,.6f,true).plane(c))
    }
    @Test fun denseClippedFieldGetsLessGainThanIsolatedLamp(){
        val w=121;val single=lamp(w,w,255,60,60);val dense=flat(w,w,25)
        for(cy in 12 until 110 step 8)for(cx in 12 until 110 step 8)lamp(w,w,255,cx,cy,dense)
        fun gain(p:Planes):Float{val (_,high)=LegendaryMath.split(p,Cancel());val coc=Frame(w,w,1,FloatArray(w*w){8f});return checkNotNull(ClippedHighlights.gains(p,high,coc,Cancel()))[60*w+60]}
        val g1=gain(single);val g2=gain(dense);println("gain isolated=$g1 dense=$g2")
        assertTrue(g1>8f);assertTrue(g2<g1/2)
        val out=render(dense,8f,true);val white=(0 until w*w).count{v(out,it)==255}
        assertTrue("dense field must not become a white sheet: $white",white<w*w/20)
    }
    @Test fun customRendererDefaultsToNoRecovery(){
        val w=61;val p=lamp(w,w,255,30,30);val (_,high)=LegendaryMath.split(p,Cancel())
        LegendaryMath.renderCoc(p,Frame(w,w,1,FloatArray(w*w){6f}),.95f,Cancel(),highlightRenderer={light,_,_,_->assertArrayEquals(high.data,light.data,0f);Frame(w,w,3,FloatArray(w*w*3))}){rgb,_->rgb}
    }
    @Test fun plateKeepsBackgroundBitExactAndFillsSubject(){
        val w=40;val h=30;val n=w*h;val a=Frame(w,h,1,FloatArray(n){if(it%w>=20)1f else 0f})
        val f=Frame(w,h,3,FloatArray(3*n){i->if(i%n%w>=20).9f else .3f+.001f*(i/n)})
        val before=f.data.copyOf()
        assertTrue(BackgroundPlate.fillInPlace(a,Cancel(),f))
        for(c in 0..2)for(i in 0 until n){val at=c*n+i;if(a.data[i]==0f)assertEquals(before[at],f.data[at],0f) else assertEquals(.3f+.001f*c,f.data[at],1e-4f)}
        assertFalse(BackgroundPlate.fillInPlace(Frame(w,h,1,FloatArray(n)),Cancel(),f))
        assertFalse(BackgroundPlate.fillInPlace(Frame(w,h,1,FloatArray(n){1f}),Cancel(),f))
    }
    private fun boxInfer(rgb:Frame,@Suppress("UNUSED_PARAMETER") d:Frame):Frame{val out=FloatArray(rgb.data.size);val n=rgb.size
        for(c in 0..2)for(y in 0 until rgb.h)for(x in 0 until rgb.w){var s=0f;var k=0;for(dx in -4..4){val xx=x+dx;if(xx in 0 until rgb.w){s+=rgb.data[c*n+y*rgb.w+xx];k++}};out[c*n+y*rgb.w+x]=s/k}
        return Frame(rgb.w,rgb.h,3,out)}
    @Test fun subjectColourNoLongerHalosIntoNearbyBackground(){
        val w=81;val h=41;val n=w*h;val p=flat(w,h,40);val a=Frame(w,h,1,FloatArray(n){if(it%w>=41)1f else 0f})
        for(i in 0 until n)if(a.data[i]==1f){p.p0[i]=220.toByte();p.p1[i]=60;p.p2[i]=60}
        val coc=Frame(w,h,1,FloatArray(n){6f*(1-a.data[it])})
        val old=SubjectProtectionMath.composite(p,LegendaryMath.renderCoc(p,coc,.95f,Cancel(),infer=::boxInfer),a,Cancel())
        val next=SubjectProtectionMath.composite(p,LegendaryMath.renderCoc(p,coc,.95f,Cancel(),subjectAlpha=a,infer=::boxInfer),a,Cancel())
        val i=20*w+38;println("halo 3px outside subject: old=${v(old,i)} new=${v(next,i)} truth=40")
        assertTrue(v(old,i)>=55);assertTrue(abs(v(next,i)-40)<=2)
        for(j in 0 until n)if(a.data[j]==1f)for(c in 0..2)assertEquals(v(p,j,c),v(next,j,c))
    }
    @Test fun platePreviewShowsSubjectAndLeavesProductionPixelsExact(){
        val w=81;val h=41;val n=w*h;val p=flat(w,h,40);val a=Frame(w,h,1,FloatArray(n){if(it%w>=41)1f else 0f})
        for(i in 0 until n)if(a.data[i]==1f)p.p0[i]=200.toByte()
        val coc=Frame(w,h,1,FloatArray(n){6f*(1-a.data[it])});var seen=0
        fun run(cb:((Frame)->Unit)?)=LegendaryMath.renderCoc(p,coc,.95f,Cancel(),globalScales=floatArrayOf(1f,2f),onPass=cb,subjectAlpha=a,infer=::boxInfer)
        val plain=run(null);val shown=run{f->seen++;assertEquals(200/255f,f.data[20*w+60],1e-6f)}
        assertEquals(2,seen);for(c in 0..2)assertArrayEquals(plain.plane(c),shown.plane(c))
    }
    @Test fun enlargedAlphaRampIsTightenedToThePhotoEdge(){
        val w=200;val h=40;val sharp=flat(w,h,40);for(y in 0 until h)for(x in 0 until 100)for(c in 0..2)sharp.plane(c)[y*w+x]=220.toByte()
        val small=Frame(50,10,1,FloatArray(500){if(it%50<25)1f else 0f})
        val up=LegendaryMath.resize(small,w,h);val tight=SharpSubjectEdges.renderAlpha(sharp,small,Cancel())
        val mixedOld=up.data.count{it>.02f && it<.98f};val mixedNew=tight.data.count{it>.02f && it<.98f}
        println("mixed alpha pixels bilinear=$mixedOld tightened=$mixedNew")
        assertTrue(mixedOld>0);assertEquals(0,mixedNew)
        for(y in 0 until h)for(x in 0 until w)assertEquals(if(x<100)1f else 0f,tight.data[y*w+x],1e-4f)
    }
    @Test fun texturedOrSameSizeAlphaFallsBack(){
        val w=200;val h=40;val r=java.util.Random(9);val sharp=Planes(w,h);for(c in 0..2)for(i in 0 until w*h)sharp.plane(c)[i]=r.nextInt(256).toByte()
        val small=Frame(50,10,1,FloatArray(500){if(it%50<25)1f else 0f})
        val up=LegendaryMath.resize(small,w,h);val tight=SharpSubjectEdges.renderAlpha(sharp,small,Cancel())
        assertArrayEquals(up.data,tight.data,0f)
        val same=Frame(w,h,1,FloatArray(w*h){.5f});assertSame(same,SharpSubjectEdges.renderAlpha(sharp,same,Cancel()))
        for(i in 0 until w*h)if(up.data[i]==0f || up.data[i]==1f)assertEquals(up.data[i],tight.data[i],0f)
    }
    @Test(expected=java.util.concurrent.CancellationException::class) fun plateHonorsCancellation(){
        val a=Frame(9,9,1,FloatArray(81){if(it%9>4)1f else 0f});BackgroundPlate.fillInPlace(a,Cancel().apply{cancelled=true},Frame(9,9,3,FloatArray(243)))
    }
}
