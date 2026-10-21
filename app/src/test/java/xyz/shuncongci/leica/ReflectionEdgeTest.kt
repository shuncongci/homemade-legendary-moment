package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Analytic reflections/mattes, NOT a replay of the user's already rendered PNG. */
class ReflectionEdgeTest {
    private fun v(p:Planes,i:Int,ch:Int=0)=p.plane(ch)[i].toInt() and 255
    private fun flat(w:Int,h:Int,value:Int)=Planes(w,h).also{p->for(c in 0..2)p.plane(c).fill(value.toByte())}
    @Test fun naturalDefaultDoesNotInventSourceEnergy(){
        for(c in listOf(-54f,-2f,-.8f,0f,.8f,2f,54f))assertEquals("natural default must not multiply a reflection",1f,BokehRadiance.gain(c),0f)
    }
    @Test fun partiallyProtectedReflectionIsNotRestoredAsItsOwnForegroundLight(){
        val w=129;val h=97;val p=flat(w,h,25)
        for(y in 45..51)for(x in 61..67){val i=y*w+x;p.p0[i]=235.toByte();p.p1[i]=190.toByte();p.p2[i]=105}
        val depth=Frame(w,h,1,FloatArray(w*h){1f});val mask=Frame(w,h,1,FloatArray(w*h){.8f})
        val coc=SubjectProtectionMath.protectedCoc(FullFrameOptics.coc(depth,0f,.95f,w,h,Cancel()),mask,Cancel())
        val raw=LegendaryMath.renderCoc(p,coc,.95f,Cancel(),highlightBoost=1f,onForeground={},infer={rgb,_->rgb})
        val baseline=SubjectProtectionMath.composite(p,raw,mask,Cancel())
        val out=LegendaryMath.render(p,depth,0f,.95f,Cancel(),subjectMask=mask,highlightBoost=1f,infer={rgb,_->rgb})
        val i=48*w+64
        for(c in 0..2)assertEquals("self reflection counted twice channel $c",v(baseline,i,c),v(out,i,c))
    }
    internal fun gradientError(reverse:Boolean,h:Int=43,dir:java.io.File?=null):Double {
        val w=51;val s=flat(w,h,0);val b=flat(w,h,0);val expected=flat(w,h,0)
        val mask=Frame(w,h,1,FloatArray(w*h))
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x;val xx=if(reverse)w-1-x else x
            val a=when{xx<21->1f;xx>27->0f;else->(28-xx)/8f};mask.data[i]=a
            val old=35+4*xx;val next=220-3*xx;val f=60
            for(c in 0..2){s.plane(c)[i]=round(a*f+(1-a)*old).toInt().toByte();b.plane(c)[i]=next.toByte();expected.plane(c)[i]=round(a*f+(1-a)*next).toInt().toByte()}
        }
        val out=SubjectProtectionMath.composite(s,b,mask,Cancel());var error=0.0;var count=0
        if(dir!=null){
            dir.mkdirs();val legacy=Planes(w,h)
            for(i in 0 until w*h)for(c in 0..2)legacy.plane(c)[i]=Math.rint(v(s,i,c)*mask.data[i].toDouble()+v(b,i,c)*(1-mask.data[i].toDouble())).toInt().toByte()
            NaturalSubjectEdges.refine(s,b,mask,legacy,Cancel())
            fun ppm(name:String,p:Planes){java.io.File(dir,"$name.ppm").outputStream().use{f->f.write("P6\n$w $h\n255\n".toByteArray());for(i in 0 until w*h)for(c in 0..2)f.write(v(p,i,c))}}
            for((name,p)in listOf("input" to s,"rendered" to b,"expected" to expected,"old" to legacy,"new" to out))ppm(name,p)
            var oldError=0.0;var newError=0.0;var points=0
            for(i in 0 until w*h)if(mask.data[i] in .2f.. .8f){oldError+=abs(v(legacy,i)-v(expected,i));newError+=abs(v(out,i)-v(expected,i));points++}
            java.io.File(dir,"metrics.json").writeText("{\"width\":$w,\"height\":$h,\"mixedPixels\":$points,\"oldEdgeMAE\":${oldError/points},\"newEdgeMAE\":${newError/points}}")
        }
        for(i in 0 until w*h)if(mask.data[i] in .2f.. .8f){error+=abs(v(out,i)-v(expected,i));count++}
        return error/count
    }
    @Test fun slopingBackgroundDoesNotLeaveDarkContour(){assertTrue("gradient edge MAE ${gradientError(false)}",gradientError(false)<=1.0)}
    @Test fun slopingBackgroundCorrectionIsMirrorSymmetric(){assertTrue("mirrored gradient edge MAE ${gradientError(true)}",gradientError(true)<=1.0)}
    @Test fun longGradientHasNoStripSeams(){assertTrue(gradientError(false,211)<=1.0);assertTrue(gradientError(true,211)<=1.0)}
    @Test fun naturalBoostIsByteExactSourceEnergy(){
        val high=Frame(9,7,3,FloatArray(189){(it%17)/17f});val coc=Frame(9,7,1,FloatArray(63){it-31f})
        assertArrayEquals(high.data,BokehRadiance.boost(high,coc,Cancel()).data,0f)
    }
    @Test fun unprotectedProductionRouteUsesUnitSourceGain(){
        val p=flat(129,97,25);for(y in 45..51)for(x in 61..67)for(c in 0..2)p.plane(c)[y*129+x]=245.toByte()
        val original=LegendaryMath.split(p,Cancel()).second;assertTrue(original.data.sum()>0)
        var calls=0
        LegendaryMath.render(p,Frame(129,97,1,FloatArray(p.size){1f}),0f,.95f,Cancel(),highlightRenderer={high,_,_,_->calls++;assertArrayEquals(original.data,high.data,0f);Frame(129,97,3,FloatArray(p.size*3))},infer={rgb,_->rgb})
        assertEquals(1,calls)
    }
    private fun f(ch:Int=3,value:Float=0f)=Frame(13,11,ch,FloatArray(13*11*ch){value})
    private fun compare(a:Planes,b:Planes){for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c))}
    @Test fun localReflectionWithoutSurplusGetsNoSecondLight(){
        val s=flat(13,11,170);val b=flat(13,11,90);val a=f(1,.6f)
        compare(SubjectProtectionMath.composite(s,b,a,Cancel()),BokehRadiance.protect(s,b,a,f(value=.1f),Cancel(),f(value=.2f),f(1,1f)))
    }
    @Test fun externalForegroundSurplusStillVeilsSubject(){
        val s=flat(13,11,80);val b=flat(13,11,90);val a=f(1,.6f)
        val expected=BokehRadiance.protect(s,b,a,f(value=.03f),Cancel())
        val out=BokehRadiance.protect(s,b,a,f(value=.08f),Cancel(),f(value=.05f),f(1,1f))
        compare(expected,out);assertTrue(v(out,0)>v(SubjectProtectionMath.composite(s,b,a,Cancel()),0))
    }
    @Test fun farSourceDoesNotSubtractNearIncomingLight(){
        val s=flat(13,11,80);val a=f(1,.6f)
        compare(BokehRadiance.protect(s,s,a,f(value=.03f),Cancel()),BokehRadiance.protect(s,s,a,f(value=.03f),Cancel(),f(value=.2f),f(1,-1f)))
    }
    @Test fun sourceReferenceDoesNotChangeInputs(){
        val s=flat(13,11,80);val a=f(1,.6f);val high=f(value=.2f);val coc=f(1,1f);val fg=f(value=.25f)
        BokehRadiance.protect(s,s,a,fg,Cancel(),high,coc)
        compare(flat(13,11,80),s);assertTrue(a.data.all{it==.6f});assertTrue(high.data.all{it==.2f});assertTrue(coc.data.all{it==1f});assertTrue(fg.data.all{it==.25f})
    }
    @Test(expected=IllegalArgumentException::class) fun missingSourceCocIsRejected(){val s=flat(13,11,80);BokehRadiance.protect(s,s,f(1,.6f),f(),Cancel(),f())}
    @Test(expected=IllegalArgumentException::class) fun nonFiniteSourceIsRejected(){val s=flat(13,11,80);BokehRadiance.protect(s,s,f(1,.6f),f(),Cancel(),f(value=Float.NaN),f(1,1f))}
    @Test fun customRendererDoesNotEmitFalseSourceCallback(){
        val p=flat(129,97,25);for(y in 45..51)for(x in 61..67)for(c in 0..2)p.plane(c)[y*129+x]=245.toByte()
        var sources=0;var foregrounds=0
        LegendaryMath.renderCoc(p,Frame(129,97,1,FloatArray(p.size){2f}),.95f,Cancel(),highlightRenderer={high,_,_,_->high},onForeground={foregrounds++},onHighlightSource={sources++},infer={rgb,_->rgb})
        assertEquals(0,sources);assertEquals(0,foregrounds)
    }
    @Test fun allSharpPixelsRemainExact(){val p=flat(13,11,170);compare(p,LegendaryMath.renderCoc(p,f(1),.95f,Cancel(),infer={_,_->throw AssertionError("should not infer")}))}
    @Test fun degenerateOneColumnBackgroundFallsBack(){val p=flat(1,9,160);val b=flat(1,9,40);val a=Frame(1,9,1,FloatArray(9){if(it<4).5f else 0f});val out=Planes(1,9);out.p0.fill(17);out.p1.fill(17);out.p2.fill(17);GradientSubjectEdges.refine(p,b,a,out,Cancel());assertTrue(out.p0.all{it.toInt()==17})}
    @Test(expected=java.util.concurrent.CancellationException::class) fun gradientPassHonorsCancellation(){val p=flat(13,11,80);GradientSubjectEdges.refine(p,p,f(1,.5f),Planes(13,11),Cancel().apply{cancelled=true})}
}
