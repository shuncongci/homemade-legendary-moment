package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class CompositionActionMotionTest {
    private fun state()=CompositionActionMotion.State().also{it.bind(false,true)}
    @Test fun firstOrdinaryLayoutSettles(){val s=CompositionActionMotion.State();assertEquals(CompositionActionMotion.Change.SETTLE,s.bind(false,true));assertEquals(0f,s.fraction,0f)}
    @Test fun restoredCompositionDoesNotFlashOrdinaryActions(){val s=CompositionActionMotion.State();assertEquals(CompositionActionMotion.Change.SETTLE,s.bind(true,true));assertEquals(1f,s.fraction,0f)}
    @Test fun entryAnimates(){assertEquals(CompositionActionMotion.Change.ANIMATE,state().bind(true,true))}
    @Test fun exitAnimates(){val s=state();s.bind(true,false);assertEquals(CompositionActionMotion.Change.ANIMATE,s.bind(false,true))}
    @Test fun repeatedRefreshDoesNotRestartEntry(){val s=state();s.bind(true,true);s.advance(.3f);repeat(40){assertEquals(CompositionActionMotion.Change.UNCHANGED,s.bind(true,true));assertEquals(.3f,s.fraction,0f)}}
    @Test fun repeatedRefreshDoesNotRestartExit(){val s=state();s.bind(true,false);s.bind(false,true);s.advance(.6f);assertEquals(CompositionActionMotion.Change.UNCHANGED,s.bind(false,true));assertEquals(.6f,s.fraction,0f)}
    @Test fun reverseEntryKeepsCurrentFrame(){val s=state();s.bind(true,true);s.advance(.37f);assertEquals(CompositionActionMotion.Change.ANIMATE,s.bind(false,true));assertEquals(.37f,s.fraction,0f);assertEquals(0f,s.target,0f)}
    @Test fun reverseExitKeepsCurrentFrame(){val s=state();s.bind(true,false);s.bind(false,true);s.advance(.72f);assertEquals(CompositionActionMotion.Change.ANIMATE,s.bind(true,true));assertEquals(.72f,s.fraction,0f)}
    @Test fun rapidTogglesSettleAtLatestTarget(){val s=state();repeat(101){s.bind(it%2==0,true);s.advance(.47f)};s.settle();assertTrue(s.composing);assertEquals(1f,s.fraction,0f)}
    @Test fun reducedMotionEntrySettles(){val s=state();assertEquals(CompositionActionMotion.Change.SETTLE,s.bind(true,false));assertEquals(1f,s.fraction,0f)}
    @Test fun reducedMotionExitSettles(){val s=state();s.bind(true,false);assertEquals(CompositionActionMotion.Change.SETTLE,s.bind(false,false));assertEquals(0f,s.fraction,0f)}
    @Test fun lifecycleSettleUsesEntryTarget(){val s=state();s.bind(true,true);s.advance(.2f);s.settle();assertEquals(1f,s.fraction,0f)}
    @Test fun lifecycleSettleUsesExitTarget(){val s=state();s.bind(true,false);s.bind(false,true);s.advance(.8f);s.settle();assertEquals(0f,s.fraction,0f)}
    @Test fun normalEndpointIsFullyVisible(){val f=CompositionActionMotion.frame(0f);assertEquals(1f,f.normalAlpha,0f);assertEquals(0f,f.compositionAlpha,0f);assertEquals(0f,f.normalOffset,0f)}
    @Test fun compositionEndpointIsFullyVisible(){val f=CompositionActionMotion.frame(1f);assertEquals(0f,f.normalAlpha,0f);assertEquals(1f,f.compositionAlpha,0f);assertEquals(0f,f.compositionOffset,0f)}
    @Test fun contentNeverScalesOrOverlaps(){for(i in 0..1000){val f=CompositionActionMotion.frame(i/1000f);assertTrue(f.normalAlpha in 0f..1f);assertTrue(f.compositionAlpha in 0f..1f);assertTrue(f.normalAlpha==0f || f.compositionAlpha==0f)}}
    @Test fun normalMovesOnlyUpByTwelveDp(){for(i in 0..100){assertTrue(CompositionActionMotion.frame(i/100f).normalOffset in -12f..0f)}}
    @Test fun compositionMovesOnlyUpFromTwelveDp(){for(i in 0..100){assertTrue(CompositionActionMotion.frame(i/100f).compositionOffset in 0f..12f)}}
    @Test fun heightEndpointsUseMeasuredRows(){assertEquals(142,CompositionActionMotion.height(142,52,0f));assertEquals(52,CompositionActionMotion.height(142,52,1f))}
    @Test fun heightShrinksMonotonically(){val h=(0..100).map{CompositionActionMotion.height(142,52,it/100f)};assertTrue(h.zipWithNext().all{it.first>=it.second})}
    @Test fun heightCanGrowForLargeFontStack(){val h=(0..100).map{CompositionActionMotion.height(142,220,it/100f)};assertTrue(h.zipWithNext().all{it.first<=it.second});assertEquals(220,h.last())}
    @Test fun equalHeightsStayFixed(){repeat(100){assertEquals(96,CompositionActionMotion.height(96,96,it/99f))}}
    @Test fun invalidSamplesCannotPoisonState(){val s=state();s.bind(true,true);s.advance(Float.NaN);assertEquals(1f,s.fraction,0f);s.advance(-1f);assertEquals(0f,s.fraction,0f);s.advance(2f);assertEquals(1f,s.fraction,0f)}
    @Test fun reverseDurationUsesRemainingDistance(){assertEquals(300L,CompositionActionMotion.duration(0f,1f));assertEquals(90L,CompositionActionMotion.duration(.3f,0f));assertEquals(80L,CompositionActionMotion.duration(.02f,0f))}
    @Test fun zeroHeightNeverBecomesNegative(){repeat(100){assertTrue(CompositionActionMotion.height(0,0,it/99f)>=0)}}
}
