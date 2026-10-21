package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Synthetic premultiplied matte / light confidence regressions, not emitter semantics. */
class NaturalBokehTest {
    private fun patch(v:Int,bg:Int=20,w:Int=161,h:Int=121,elongated:Boolean=false)=Planes(w,h).apply {
        for(ch in 0..2)plane(ch).fill(bg.toByte())
        for(y in 59..61)for(x in if(elongated)76..84 else 79..81)for(ch in 0..2)plane(ch)[y*w+x]=v.toByte()
    }
    private fun high(p:Planes)=LegendaryMath.split(p,Cancel()).second.data.sum()
    @Test fun modestNeutralPatchStaysInOrdinaryBlur(){assertEquals(0f,high(patch(180)),0f)}
    @Test fun weakNeutralSpecksDoNotMakeManyDiscs(){val p=patch(20);for(y in 10..110 step 12)for(x in 10..150 step 12)for(ch in 0..2)p.plane(ch)[y*161+x]=170.toByte();assertEquals(0f,high(p),0f)}
    @Test fun smallReflectionOnLitSurfaceIsRejected(){assertEquals(0f,high(patch(220,170)),0f)}
    @Test fun shortNarrowReflectionIsRejected(){assertEquals(0f,high(patch(245,20,elongated=true)),0f)}
    @Test fun clearWhiteLampStillSurvives(){assertTrue(high(patch(245))>1f)}
    @Test fun moderateWhiteLampStillSurvivesWithLessEnergy(){assertTrue(high(patch(220))>0f);assertTrue(high(patch(220))<high(patch(245)))}
    @Test fun isolatedWarmLampStillSurvives(){val p=patch(20);for(y in 59..61)for(x in 79..81){val i=y*161+x;p.p0[i]=130.toByte();p.p1[i]=95;p.p2[i]=15};assertTrue(high(p)>0f)}
    @Test fun rejectedPatchBaseRemainsByteExact(){val p=patch(180);val b=LegendaryMath.planes(LegendaryMath.split(p,Cancel()).first);for(ch in 0..2)assertArrayEquals(p.plane(ch),b.plane(ch))}
    private data class Edge(val sharp:Planes,val rendered:Planes,val alpha:Frame,val expected:IntArray)
    private fun edge(fg:Int=200,old:Int=40,next:Int=100,w:Int=41,h:Int=21):Edge {
        val a=Frame(w,h,1,FloatArray(w*h){i->when(i%w){in 0..18->0f;19->.25f;20->.5f;21->.75f;else->1f}})
        val s=Planes(w,h);val b=Planes(w,h);val expected=IntArray(w*h)
        for(i in 0 until s.size){val t=a.data[i];val mixed=(fg*t+old*(1-t)).roundToInt();expected[i]=(fg*t+next*(1-t)).roundToInt();for(ch in 0..2){s.plane(ch)[i]=mixed.toByte();b.plane(ch)[i]=(if(t==0f)next else mixed).toByte()}}
        return Edge(s,b,a,expected)
    }
    private fun assertEdge(e:Edge){val out=SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel());for(i in 0 until e.sharp.size)for(ch in 0..2)assertTrue("edge at $i alpha=${e.alpha.data[i]} expected=${e.expected[i]} actual=${out.plane(ch)[i].toInt() and 255}",abs(e.expected[i]-(out.plane(ch)[i].toInt() and 255))<=1)}
    @Test fun edgeReplacesOldBackgroundInsteadOfSquaringAlpha(){assertEdge(edge())}
    @Test fun darkHairOnLightBackgroundHasNoBrightFringe(){assertEdge(edge(fg=30,old=220,next=90))}
    @Test fun brightHairOnDarkBackgroundHasNoDarkFringe(){assertEdge(edge(fg=230,old=20,next=160))}
    @Test fun unchangedBackgroundPreservesEdgeTexture(){assertEdge(edge(fg=200,old=40,next=40))}
    @Test fun opaqueAndBackgroundRemainExact(){val e=edge();val out=SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel());for(i in e.alpha.data.indices)for(ch in 0..2){if(e.alpha.data[i]==1f)assertEquals(e.sharp.plane(ch)[i],out.plane(ch)[i]);if(e.alpha.data[i]==0f)assertEquals(e.rendered.plane(ch)[i],out.plane(ch)[i])}}
    @Test fun noBackgroundEvidenceUsesConservativeBlend(){val p=Planes(3,3,ByteArray(9){100},ByteArray(9){100},ByteArray(9){100});val a=Frame(3,3,1,FloatArray(9){.5f});assertTrue(SubjectProtectionMath.composite(p,Planes(3,3),a,Cancel()).p0.all{it.toInt()==50})}
    @Test fun zeroForegroundUsesSameImprovedEdgeBlend(){val e=edge();val out=BokehRadiance.protect(e.sharp,e.rendered,e.alpha,Frame(41,21,3,FloatArray(41*21*3)),Cancel());for(i in e.expected.indices)assertTrue(abs(e.expected[i]-(out.p0[i].toInt() and 255))<=1)}
    @Test fun edgeDoesNotMutateInputs(){val e=edge();val s=e.sharp.p0.copyOf();val b=e.rendered.p0.copyOf();val a=e.alpha.data.copyOf();SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel());assertArrayEquals(s,e.sharp.p0);assertArrayEquals(b,e.rendered.p0);assertArrayEquals(a,e.alpha.data,0f)}
    @Test(expected=CancellationException::class) fun edgeHonorsCancellation(){val e=edge();SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel().apply{cancelled=true})}
    @Test fun rescaledMaskKeepsOutputShape(){val e=edge();val a=LegendaryMath.resize(e.alpha,21,11);val o=SubjectProtectionMath.composite(e.sharp,e.rendered,a,Cancel());assertEquals(41,o.width);assertEquals(21,o.height)}
    @Test fun ordinaryTwoHundredGrayDoesNotBecomeALamp(){assertEquals(0f,high(patch(200)),0f)}
    @Test fun rowStripBoundariesDoNotProduceSeams(){assertEdge(edge(w=41,h=211))}
    @Test fun oppositeSideBackgroundIsSymmetric(){val e=edge();fun flip(p:Planes)=Planes(41,21).also{q->for(y in 0..20)for(x in 0..40)for(ch in 0..2)q.plane(ch)[y*41+x]=p.plane(ch)[y*41+40-x]}
        val a=Frame(41,21,1,FloatArray(861){i->e.alpha.data[i/41*41+40-i%41]});val o=SubjectProtectionMath.composite(flip(e.sharp),flip(e.rendered),a,Cancel());for(i in 0 until 861)assertTrue(abs(e.expected[i/41*41+40-i%41]-(o.p0[i].toInt() and 255))<=1)}
    @Test fun colorChannelsRecomposeWithoutNeutralTint(){val r=edge(200,40,100);val g=edge(30,220,90);val b=edge(230,20,160);val s=Planes(41,21,r.sharp.p0,g.sharp.p0,b.sharp.p0);val v=Planes(41,21,r.rendered.p0,g.rendered.p0,b.rendered.p0);val o=SubjectProtectionMath.composite(s,v,r.alpha,Cancel());for(ch in 0..2)for(i in 0 until 861)assertTrue(abs(listOf(r,g,b)[ch].expected[i]-(o.plane(ch)[i].toInt() and 255))<=1)}
    @Test fun impossibleForegroundEstimateFallsBack(){val e=edge();for(ch in 0..2)for(y in 0..20)e.sharp.plane(ch)[y*41+20]=0;val o=SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel());assertEquals(60,o.p0[20].toInt() and 255)}
    @Test fun highVarianceBackgroundDoesNotInventAnEdgeColor(){val e=edge();for(y in 0..20)for(x in 0..18)for(ch in 0..2)e.sharp.plane(ch)[y*41+x]=(if((x+y)%2==0)0 else 255).toByte();val o=SubjectProtectionMath.composite(e.sharp,e.rendered,e.alpha,Cancel());assertEquals(120,o.p0[20].toInt() and 255)}
}
