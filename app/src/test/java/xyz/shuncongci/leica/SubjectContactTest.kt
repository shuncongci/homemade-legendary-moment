package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point
import kotlin.math.hypot

/** Contact seams between two protected subjects must not be treated as subject/background edges. */
class SubjectContactTest {
    private fun frame(w:Int,h:Int,f:(Int,Int)->Float)=Frame(w,h,1,FloatArray(w*h){f(it%w,it/w)})
    private fun max(a:Frame,b:Frame)=Frame(a.w,a.h,1,FloatArray(a.data.size){kotlin.math.max(a.data[it],b.data[it])})
    private fun join(a:Frame,b:Frame)=SubjectSelectionMath.joinContacts(max(a,b),listOf(a,b),Cancel())
    private fun clamp(v:Float)=v.coerceIn(0f,1f)
    // Two subjects matted separately: each softens its side of the shared contact line.
    private val touchA=frame(80,40){x,_->clamp((45f-x)/10f)}
    private val touchB=frame(80,40){x,_->clamp((x-35f)/10f)}
    // Same pair with a soft outer silhouette against background below row 20.
    private fun v(y:Int)=clamp((24f-y)/4f)
    private val silA=frame(80,48){x,y->clamp((45f-x)/10f)*v(y)}
    private val silB=frame(80,48){x,y->clamp((x-35f)/10f)*v(y)}

    @Test fun radiusFollowsAnalysisScale(){
        assertEquals(11,SubjectSelectionMath.contactRadius(914,1220));assertEquals(17,SubjectSelectionMath.contactRadius(1536,2048))
        assertEquals(11,SubjectSelectionMath.contactRadius(80,40))
    }
    @Test fun touchingSubjectsBecomeOneSolidSubject(){
        assertTrue("plain max-union dips at the contact",max(touchA,touchB).data.min()<.6f)
        assertTrue(join(touchA,touchB).data.all{it==1f})
    }
    @Test fun outerSilhouetteIsBitExactWhileSeamInteriorIsSolid(){
        val u=max(silA,silB);val out=join(silA,silB)
        for(y in 17 until 48)for(x in 0 until 80)assertEquals("outer edge x=$x y=$y",u.data[y*80+x],out.data[y*80+x],0f)
        for(y in 0..12)for(x in 0 until 80)assertEquals("seam x=$x y=$y",1f,out.data[y*80+x],0f)
    }
    @Test fun realBackgroundGapBetweenSubjectsIsUntouched(){
        val a=frame(80,40){x,_->clamp((32f-x)/2f)};val b=frame(80,40){x,_->clamp((x-37f)/2f)};val u=max(a,b)
        assertSame(u,SubjectSelectionMath.joinContacts(u,listOf(a,b),Cancel()))
    }
    @Test fun hairlineContactGapIsFilled(){
        val a=frame(80,40){x,_->clamp((40f-x)/4f)};val b=frame(80,40){x,_->clamp((x-40f)/4f)}
        val before=a.data.copyOf() to b.data.copyOf()
        assertTrue(join(a,b).data.all{it==1f})
        assertArrayEquals(before.first,a.data,0f);assertArrayEquals(before.second,b.data,0f)
    }
    @Test fun duplicateOrNestedSelectionsAreUnchanged(){
        val blob=frame(41,41){x,y->clamp((12f-hypot(x-20f,y-20f))/4f)}
        val u=max(blob,blob);assertSame(u,SubjectSelectionMath.joinContacts(u,listOf(blob,blob),Cancel()))
        val big=frame(41,41){x,y->clamp((14f-hypot(x-20f,y-20f))/4f)};val small=frame(41,41){x,y->clamp((6f-hypot(x-20f,y-20f))/3f)}
        val n=max(big,small);assertSame(n,SubjectSelectionMath.joinContacts(n,listOf(big,small),Cancel()))
    }
    @Test fun singleMaskIsReturnedAsIs(){assertSame(touchA,SubjectSelectionMath.joinContacts(touchA,listOf(touchA),Cancel()))}
    @Test fun orderIndependentAndOnlyRaisesAlpha(){
        val ab=join(silA,silB);val ba=SubjectSelectionMath.joinContacts(max(silB,silA),listOf(silB,silA),Cancel())
        assertArrayEquals(ab.data,ba.data,0f);val u=max(silA,silB);assertTrue(ab.data.indices.all{ab.data[it]>=u.data[it] && ab.data[it]<=1f})
    }
    @Test(expected=CancellationException::class) fun cancellationPropagates(){
        SubjectSelectionMath.joinContacts(max(touchA,touchB),listOf(touchA,touchB),Cancel().apply{cancelled=true})
    }
    @Test fun analysisScaleSeamFilledAndOuterEdgeKept(){
        val w=2048;val h=1536;fun vy(y:Int)=clamp((1200f-y)/16f)
        val a=frame(w,h){x,y->clamp((1032f-x)/16f)*vy(y)};val b=frame(w,h){x,y->clamp((x-1016f)/16f)*vy(y)}
        val u=max(a,b);val out=SubjectSelectionMath.joinContacts(u,listOf(a,b),Cancel())
        for(x in 1010..1038)assertEquals(1f,out.data[1000*w+x],0f)
        for(y in 1188 until h)for(x in 0 until w)assertEquals(u.data[y*w+x],out.data[y*w+x],0f)
    }
    @Test fun cacheUnionClosesSeamAndSinglePointStaysRaw(){
        val pa=Point(.2f,.5f);val pb=Point(.8f,.5f);val c=SubjectPointCache()
        val x=c.resolve(true,"p",listOf(pa,pb),Cancel()){missing,commit->missing.forEach{commit(it,if(it==pa)touchA else touchB)}}!!
        assertTrue(x.data.all{it==1f});assertArrayEquals(touchA.data,c.cached("p",listOf(pa),Cancel())!!.data,0f)
    }
    @Test fun renderKeepsContactSeamExactlySharp(){
        val w=80;val h=40;val im=Planes(w,h,ByteArray(w*h){if(it%w<40)60 else 20},ByteArray(w*h){30},ByteArray(w*h){40})
        fun render(mask:Frame)=LegendaryMath.render(im,Frame(w,h,1,FloatArray(w*h){.1f}),.9f,.95f,Cancel(),subjectMask=mask){rgb,_->Frame(rgb.w,rgb.h,rgb.channels,FloatArray(rgb.data.size){.5f})}
        val fixed=render(join(touchA,touchB));for(c in 0..2)assertArrayEquals(im.plane(c),fixed.plane(c))
        val plain=render(max(touchA,touchB))
        assertTrue("old union really blurred the seam",(0..2).any{c->(36..44).any{x->plain.plane(c)[20*w+x]!=im.plane(c)[20*w+x]}})
    }
}
