package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

class Release270RegressionTest {
    @Test fun farFocusOccupiesMeaningfullyWiderRelativeDepthBand(){
        val d=Frame(10001,1,1,FloatArray(10001){it/10000f})
        for(ap in LegendaryMath.apertures){
            fun count(f:Float)=FullFrameOptics.coc(d,f,ap,3000,1,Cancel()).data.count{abs(it)<=FullFrameOptics.acceptableRadius(3000,1)}
            assertTrue("far focus must not be a nearly constant normalized-depth strip: f/$ap",count(.15f)>2*count(.85f))
        }
    }
    @Test fun unconstrainedThinPositiveSeedReproducesRefinementFailure(){
        val w=33;val h=25;val p=Point(.5f,.5f);val k=SubjectSelectionMath.index(p,w,h)
        val mask=Frame(w,h,1,FloatArray(w*h){if(it%w==k%w)1f else 0f})
        val t=SubjectProtectionMath.trimap(mask,Cancel())
        assertTrue(mask.data[k]>=.5f);assertTrue(t[k]>0f && t[k]<1f)
        try{SubjectSelectionMath.finish(Frame(64,32,1,FloatArray(64*32){.1f}),w,h,t,listOf(p),Cancel());fail("unconstrained matte must still reject a missing seed")}
        catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("细化后未被覆盖"))}
    }
    @Test fun thinPositivePointSurvivesMatteWithoutPaintingNeighbours(){
        val w=33;val h=25;val p=Point(.5f,.5f);val k=SubjectSelectionMath.index(p,w,h)
        val mask=Frame(w,h,1,FloatArray(w*h){if(it%w==k%w)1f else 0f});val original=mask.data.copyOf()
        val old=SubjectProtectionMath.trimap(mask,Cancel());val t=SubjectSelectionMath.positiveTrimap(mask,listOf(p),Cancel())
        assertEquals(1f,t[k],0f);assertArrayEquals(original,mask.data,0f)
        for(i in t.indices)if(i!=k)assertEquals("only the confirmed prompt pixel can change",old[i],t[i],0f)
        val alpha=SubjectSelectionMath.finish(Frame(64,32,1,FloatArray(64*32){.1f}),w,h,t,listOf(p),Cancel())
        assertEquals(1f,alpha.data[k],0f);SubjectSelectionMath.requireCovered(alpha,listOf(p))
        for(i in t.indices)if(old[i]==0f)assertEquals(0f,alpha.data[i],0f)
    }
    @Test fun missingSamTargetStillCannotBeSeeded(){
        val mask=Frame(33,25,1,FloatArray(33*25));val before=mask.data.copyOf()
        try{SubjectSelectionMath.positiveTrimap(mask,listOf(Point(.5f,.5f)),Cancel());fail()}catch(_:IllegalArgumentException){}
        assertArrayEquals(before,mask.data,0f)
    }
    @Test fun failedSamCandidatesStillRejected(){
        try{SubjectSelectionMath.select(FloatArray(3*256*256){-1f},floatArrayOf(.99f,.9f,.5f),Point(.5f,.5f),33,25,Cancel());fail()}
        catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("未识别"))}
    }
    @Test fun cancelledConstraintDoesNotMutateMask(){
        val mask=Frame(33,25,1,FloatArray(33*25){1f});val before=mask.data.copyOf()
        try{SubjectSelectionMath.positiveTrimap(mask,listOf(Point(.5f,.5f)),Cancel().apply{cancelled=true});fail()}catch(_:java.util.concurrent.CancellationException){}
        assertArrayEquals(before,mask.data,0f)
    }
    @Test fun distanceMappingRetainsVirtualAnchorsAndNoFakeMeasuredMetres(){
        assertEquals(30000.0,FullFrameOptics.virtualDistance(0f),1e-8);assertEquals(1200.0,FullFrameOptics.virtualDistance(1f),1e-8)
        assertEquals(1/(1/30000.0+.25*(1/1200.0-1/30000.0)),FullFrameOptics.virtualDistance(.5f),1e-8)
        assertTrue(FullFrameOptics.ID.contains("quadratic"))
    }
    @Test fun signedFieldIsTransformedBeforeUpsampling(){
        val d=Frame(2,1,1,floatArrayOf(0f,1f));val native=FullFrameOptics.nativeCoc(d,.5f,2.8f,3,1,Cancel())
        val gain=FullFrameOptics.radiusScale(.5f,2.8f,3,1)
        assertEquals(-.25f*gain,native.data[0],0f);assertEquals(.75f*gain,native.data[1],0f)
        val c=FullFrameOptics.coc(d,.5f,2.8f,3,1,Cancel());assertEquals(.25f*gain,c.data[1],1e-6f)
    }
    @Test fun oldSubjectRefinementCacheIsInvalidated(){
        val p=Point(.5f,.5f);val old="2048:${p.x.toRawBits()}:${p.y.toRawBits()}"
        assertNotEquals(old,EditSession.subjectIdentity(2048,p));assertTrue(EditSession.subjectIdentity(2048,p).startsWith("positive-v2:"))
    }
}
