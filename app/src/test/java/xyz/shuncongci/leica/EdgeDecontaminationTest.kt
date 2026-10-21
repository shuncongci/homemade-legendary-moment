package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Mixed-band rim (sharp background / sharpening halo) around a thin dark subject.
 * Synthetic scenes modelled on the measured mirror edge; not a replay of the user's photo. */
class EdgeDecontaminationTest {
    private val w=60;private val h=40
    private val fg=intArrayOf(29,40,37);private val bg=intArrayOf(45,67,21)
    private val band=floatArrayOf(.8f,.6f,.4f,.2f)
    private fun alphaAt(x:Int)=when{x<20->1f;x<24->band[x-20];else->0f}
    private fun alpha()=Frame(w,h,1,FloatArray(w*h){alphaAt(it%w)})
    private fun v(p:Planes,i:Int,c:Int)=p.plane(c)[i].toInt() and 255
    private fun scene(halo:Int,texture:Boolean=false)=Planes(w,h).also{p->
        for(y in 0 until h)for(x in 0 until w){val a=alphaAt(x);val i=y*w+x
            for(c in 0..2){val b=if(texture && x>=20)(if((x+y)%2==0)0 else 255) else bg[c]
                val mixed=a*fg[c]+(1-a)*b+if(a>0f && a<1f)halo else 0
                p.plane(c)[i]=Math.rint(mixed.toDouble()).toInt().coerceIn(0,255).toByte()}}}
    private fun flatBlur(w:Int,h:Int)=Planes(w,h).also{p->for(c in 0..2)p.plane(c).fill(bg[c].toByte())}
    /** composite() with a chosen pre-clean: identity = 2.1.18, EdgeV1Probe = 2.1.19. */
    private fun compositeWith(sharp:Planes,blur:Planes,a:Frame,clean:(Planes)->Planes):Planes{
        val s=clean(sharp);val out=Planes(s.width,s.height)
        for(i in 0 until s.size){val t=a.data[i];for(c in 0..2)out.plane(c)[i]=Math.rint((s.plane(c)[i].toInt() and 255)*t+(blur.plane(c)[i].toInt() and 255)*(1.0-t)).toInt().toByte()}
        return GradientSubjectEdges.refine(s,blur,a,NaturalSubjectEdges.refine(s,blur,a,out,Cancel()),Cancel())
    }
    /** Summed |out - (aF+(1-a)B_blur)| over the mixed band: the visible rim. */
    private fun rim(p:Planes):Double{var s=0.0
        for(y in 0 until h)for(x in 20 until 24){val a=alphaAt(x);val i=y*w+x
            for(c in 0..2)s+=abs(v(p,i,c)-(a*fg[c]+(1-a)*bg[c]))}
        return s}

    @Test fun brightRimInMixedBandIsReducedAndEndpointsStayExact(){
        val sharp=scene(16);val blur=flatBlur(w,h);val a=alpha()
        val old=compositeWith(sharp,blur,a){it};val next=SubjectProtectionMath.composite(sharp,blur,a,Cancel())
        println("mirror rim energy: old=${rim(old)} new=${rim(next)}")
        assertTrue("rim must drop by at least 40%",rim(next)<=.6*rim(old))
        for(y in 0 until h)for(x in 0 until w)if(x<20 || x>=24)for(c in 0..2)assertEquals(v(old,y*w+x,c),v(next,y*w+x,c))
        // No band pixel overshoots subject and blurred background more than 2.1.18 did; columns with
        // a<=.6 are strictly better. The a=.8 column touches the subject: its known background is a
        // thin sliver (slope/offset ambiguous) and by design it keeps a^2 of the residual anyway.
        for(y in 0 until h)for(x in 20 until 24)for(c in 0..2){val i=y*w+x
            val o=v(next,i,c)-max(fg[c],bg[c]);val was=v(old,i,c)-max(fg[c],bg[c])
            assertTrue("x=$x c=$c overshoot $o was $was",o<=was)
            if(alphaAt(x)<=.6f && was>0)assertTrue("x=$x c=$c overshoot $o was $was",o<was)}
    }

    /** Textured subject, jittered matte edge, mildly textured sharp background, varying halo:
     *  closer to a real phone edge than the flat scene. */
    private val tw=80;private val th=120
    private fun tAlpha(x:Int,y:Int):Float{val x0=28+1.2*sin(y*.37);return ((x0+4-x)/5).coerceIn(0.0,1.0).toFloat()}
    private fun texturedScene():Triple<Planes,Frame,Array<DoubleArray>>{
        val p=Planes(tw,th);val a=Frame(tw,th,1,FloatArray(tw*th){tAlpha(it%tw,it/tw)})
        val ideal=Array(3){DoubleArray(tw*th)}
        for(y in 0 until th)for(x in 0 until tw){val i=y*tw+x;val t=a.data[i].toDouble()
            val tex=((x*37+y*91)%23-11).toDouble();val leaf=((x*53+y*29)%13-6).toDouble()
            val halo=if(t>0 && t<1)14+4*sin(y*.9) else 0.0
            for(c in 0..2){val f=fg[c]+tex;val s=t*f+(1-t)*(bg[c]+leaf)+halo
                p.plane(c)[i]=Math.rint(s).toInt().coerceIn(0,255).toByte();ideal[c][i]=t*f+(1-t)*bg[c]}}
        return Triple(p,a,ideal)
    }
    private data class Band(val rim:Double,val rough:Double)
    private fun band(out:Planes,a:Frame,ideal:Array<DoubleArray>):Band{var rim=0.0;var rough=0.0
        fun err(c:Int,i:Int)=(out.plane(c)[i].toInt() and 255)-ideal[c][i]
        for(y in 1 until th-1)for(x in 0 until tw){val i=y*tw+x;val t=a.data[i];if(!(t>0f && t<1f))continue
            for(c in 0..2){rim+=abs(err(c,i))
                // Along-edge alternation of the error: patchy flatten/keep reads as a rough layer.
                val up=i-tw;val dn=i+tw
                if(a.data[up]>0f && a.data[up]<1f && a.data[dn]>0f && a.data[dn]<1f)rough+=abs(err(c,i)-(err(c,up)+err(c,dn))/2)}}
        return Band(rim,rough)
    }
    @Test fun texturedEdgeHasNoRoughLayer(){
        val (sharp,a,ideal)=texturedScene();val blur=flatBlur(tw,th)
        val old=band(compositeWith(sharp,blur,a){it},a,ideal)
        val next=band(SubjectProtectionMath.composite(sharp,blur,a,Cancel()),a,ideal)
        println("textured edge 2.1.18 rim=${old.rim} rough=${old.rough} | new rim=${next.rim} rough=${next.rough}")
        // Measured once with a verbatim copy of the 2.1.19 pass on this scene: rim=14680 rough=3014
        // (its rough layer). The copy was removed after measurement; this bound keeps us well below it.
        assertTrue("rim ${next.rim} vs 2.1.18 ${old.rim}",next.rim<=.6*old.rim)
        assertTrue("rough ${next.rough} vs 2.1.18 ${old.rough}",next.rough<=.55*old.rough)
        assertTrue("rough ${next.rough} vs measured 2.1.19 3014",next.rough<=.6*3014.1421017477405)
    }

    @Test fun cleanEdgeIsUnchanged(){
        val sharp=scene(0);val a=alpha();val out=EdgeDecontamination.clean(sharp,a,Cancel())
        for(i in 0 until w*h)for(c in 0..2)assertTrue(abs(v(out,i,c)-v(sharp,i,c))<=1)
    }
    @Test fun texturedBackgroundFallsBack(){
        val sharp=scene(16,texture=true);val a=alpha();val out=EdgeDecontamination.clean(sharp,a,Cancel())
        for(c in 0..2)assertArrayEquals(sharp.plane(c),out.plane(c))
    }
    @Test fun binaryAlphaIsIdentityAndInputsAreNotMutated(){
        val sharp=scene(16);val copy=(0..2).map{sharp.plane(it).copyOf()}
        val hard=Frame(w,h,1,FloatArray(w*h){if(it%w<22)1f else 0f})
        assertSame(sharp,EdgeDecontamination.clean(sharp,hard,Cancel()))
        val a=alpha();val alphaCopy=a.data.copyOf();EdgeDecontamination.clean(sharp,a,Cancel())
        for(c in 0..2)assertArrayEquals(copy[c],sharp.plane(c));assertArrayEquals(alphaCopy,a.data,0f)
    }
    @Test fun tooFewSamplesFallsBack(){
        // Isolated mixed pixel with no opaque subject nearby: nothing to separate against.
        val sharp=scene(0);val a=Frame(w,h,1,FloatArray(w*h)).also{it.data[20*w+40]=.5f}
        for(c in 0..2)sharp.plane(c)[20*w+40]=200.toByte()
        val out=EdgeDecontamination.clean(sharp,a,Cancel())
        for(c in 0..2)assertArrayEquals(sharp.plane(c),out.plane(c))
    }
    @Test(expected=CancellationException::class) fun cancellationPropagates(){
        EdgeDecontamination.clean(scene(16),alpha(),Cancel().apply{cancelled=true})
    }
}
