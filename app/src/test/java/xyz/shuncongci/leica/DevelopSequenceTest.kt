package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Display-only regressions. These do not claim Android Canvas/frame-pacing acceptance. */
class DevelopSequenceTest {
    private fun combined(at:Float=0f)=DevelopMotion().apply{startColor(true);colorCompleted();advance(at);startBokeh();bokehPassCompleted()}
    @Test fun actualRegionsAtAnyColorPositionCannotPreemptTheTail(){for(t in listOf(0f,1f,80f,900f,1800f,3000f,3299f)){
        val m=combined(t);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);assertEquals(t/3300f,m.progress,0f);assertFalse(m.showsBokeh)
        m.advance(3299f-t);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);m.advance(1f);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage)
    }}
    @Test fun manyRealPassesDoNotResetOrCompleteColor(){val m=combined(1200f);repeat(64){m.bokehPassCompleted()};assertEquals(1200f,m.elapsed,0f);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage)}
    @Test fun bokehDataDuringColorWaitCannotFabricateColorCompletion(){val m=DevelopMotion();m.startColor(true);m.startBokeh();m.bokehPassCompleted();m.advance(20000f);assertEquals(DevelopMotion.Stage.COLOR_WAIT,m.stage);m.colorCompleted();m.advance(3300f);assertTrue(m.showsBokeh)}
    @Test fun aFastFinalStillWaitsForTheWholeColorTail(){val m=combined(900f);m.resultCompleted(true);m.advance(2399f);assertFalse(m.showsBokeh);m.advance(1f);assertEquals(DevelopMotion.Stage.BOKEH_FINISH,m.stage);assertEquals(0f,m.bokehLift,0f);assertEquals(0f,m.bokehDetail,0f);m.advance(420f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun finalCannotInventASecondLiftOrDarkGrid(){val m=combined(100f);m.resultCompleted(true);m.advance(3200f);repeat(42){assertEquals(0f,m.bokehLift,0f);assertEquals(0f,m.bokehDetail,0f);m.advance(10f)}}
    @Test fun rejectedFocusDoesNotPlayFakeBokehSuccess(){val m=combined(900f);m.resultCompleted(false);m.advance(2400f);assertEquals(DevelopMotion.Stage.DONE,m.stage);assertFalse(m.showsBokeh)}
    @Test fun waitingForBokehDataHoldsTheCompletedColor(){val m=DevelopMotion();m.startColor(true);m.colorCompleted();m.advance(20000f);assertEquals(DevelopMotion.Stage.COLOR_HOLD,m.stage);assertFalse(m.needsFrames);assertFalse(m.showsBokeh)}
    @Test fun lateBokehStartsFromFlatWithoutAColorReplay(){val m=DevelopMotion();m.startColor(true);m.colorCompleted();m.advance(3300f);m.startBokeh();assertTrue(m.showsBokeh);assertEquals(0f,m.bokehLift,0f);assertEquals(0f,m.bokehDetail,0f)}
    @Test fun normalBokehEntryFadesIndicatorsContinuously(){val m=combined();m.advance(3300f);var prior=0f;repeat(55){assertTrue(m.bokehDetail>=prior);assertTrue(m.bokehDetail in 0f..1f);prior=m.bokehDetail;m.advance(10f)};assertEquals(1f,m.bokehDetail,0f);assertEquals(.78f,m.bokehLift,0f)}
    @Test fun finalDuringLiftStartsFromTheCurrentGeometryAndDetail(){for(t in listOf(0f,1f,50f,200f,549f,550f,5000f)){
        val m=DevelopMotion();m.startBokeh();m.advance(t);val lift=m.bokehLift;val detail=m.bokehDetail;m.resultCompleted(true);assertEquals(lift,m.bokehLift,0f);assertEquals(detail,m.bokehDetail,0f);m.advance(210f);assertEquals(lift*.5f,m.bokehLift,.00001f);assertEquals(detail,m.bokehDetail,0f)
    }}
    @Test fun repeatedActualPassesDoNotRestartTheLift(){val m=DevelopMotion();m.startBokeh();m.advance(250f);repeat(16){m.bokehPassCompleted()};assertEquals(250f,m.elapsed,0f)}
    @Test fun stableBokehWaitDoesNotDemandContinuousFrames(){val m=DevelopMotion();m.startBokeh();assertTrue(m.needsFrames);m.advance(550f);assertFalse(m.needsFrames);assertTrue(m.visible);assertTrue(m.showsBokeh);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage)}
    @Test fun stableBokehStillRequiresTheRealFinal(){val m=DevelopMotion();m.startBokeh();m.advance(10000f);assertFalse(m.needsFrames);m.resultCompleted(true);assertTrue(m.needsFrames);m.advance(420f);assertFalse(m.needsFrames);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun largeDroppedFrameMatchesManySmallFrames(){val a=combined(900f);val b=combined(900f);a.resultCompleted(true);b.resultCompleted(true);a.advance(2600f);repeat(260){b.advance(10f)};assertEquals(a.stage,b.stage);assertEquals(a.progress,b.progress,.00001f);assertEquals(a.bokehLift,b.bokehLift,0f)}
    @Test fun reducedMotionOnlySkipsDecorationNotUnfinishedWork(){val m=combined(900f);m.reduceMotion();assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);assertTrue(m.visible);assertFalse(m.finishing);m.advance(100000f);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage)}
    @Test fun reducedMotionWithRealFinalResolvesWithoutAReplay(){val m=combined(900f);m.resultCompleted(true);m.reduceMotion();assertEquals(DevelopMotion.Stage.DONE,m.stage);assertFalse(m.showsBokeh);assertFalse(m.needsFrames)}
    @Test fun resetWhileColorAndBokehAreBothReadyClearsBoth(){val m=combined(900f);m.reset();assertEquals(DevelopMotion.Stage.IDLE,m.stage);assertFalse(m.visible);assertFalse(m.showsBokeh);assertEquals(0f,m.bokehDetail,0f);assertNull(m.presentationLabel)}
    @Test fun newColorGenerationCannotInheritOldBokehOrFinal(){val m=combined();m.resultCompleted(true);m.startColor(false);m.colorCompleted();m.advance(3300f);assertEquals(DevelopMotion.Stage.COLOR_HOLD,m.stage);assertFalse(m.showsBokeh)}
    @Test fun hiddenLayerProgressIsRetainedWithoutAReplay(){val m=combined(900f);val layers=BokehLayerReveal(100,80,true);assertTrue(layers.accept(BokehLayerProgress(1,4,32,64)));assertEquals(8,layers.litCount(1));assertFalse(m.showsBokeh);layers.advance(2400f);m.advance(2400f);assertTrue(m.showsBokeh);assertEquals(8,layers.litCount(1));assertFalse(layers.needsFrames);assertFalse(layers.accept(BokehLayerProgress(1,4,32,64)))}
    @Test fun hiddenCompletedRegionDoesNotInventLaterRegions(){val m=combined();val layers=BokehLayerReveal(100,80,true);layers.partCompleted(1);m.advance(3300f);assertEquals(16,layers.litCount(1));for(i in 2..4)assertEquals(0,layers.litCount(i))}
    @Test fun recoveredBaseCanBePresentedWithoutFabricatedLayers(){val m=DevelopMotion();m.startBokeh();val layers=BokehLayerReveal(100,80,true);repeat(4){assertTrue(layers.partCompleted(it+1))};m.reduceMotion();assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);repeat(4){assertEquals(16,layers.litCount(it+1))};m.resultCompleted(true);m.reduceMotion();assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun presentationTextKeepsActualBackendProgress(){val m=combined(900f);val actual="2/4 · 虚化 · 32/64层 · 3.0秒";assertEquals("画面：调色收尾\n$actual",m.statusText(actual));m.advance(2400f);assertEquals(actual,m.statusText(actual));m.resultCompleted(true);assertEquals("画面：虚化收尾\n$actual",m.statusText(actual))}
    @Test fun errorsAndCancellationTextAreNotHidden(){val m=DevelopMotion();for(text in listOf("保存失败 · 空间不足","正在取消","恢复已完成融合"))assertEquals(text,m.statusText(text))}
    @Test fun noDisplayDataDoesNotCreateABokehSurface(){val m=DevelopMotion();m.resultCompleted(false);assertFalse(m.showsBokeh);assertNull(m.presentationLabel)}
    @Test fun colorOnlyStillWaitsForColorAndThenStops(){val m=DevelopMotion();m.startColor(false);m.colorCompleted();m.resultCompleted(false);m.advance(3299f);assertEquals("调色收尾",m.presentationLabel);m.advance(1f);assertEquals(DevelopMotion.Stage.DONE,m.stage);assertFalse(m.showsBokeh)}
    @Test fun cancellationIsStillAOneShotFalseNotSuccess(){val c=DevelopCompletion();var n=0;c.await{assertFalse(it);n++};c.resolve(false);c.resolve(true);assertEquals(1,n);assertFalse(c.pending)}
    @Test fun clockStartsWithoutCountingStoppedTime(){val c=DevelopFrameClock();assertEquals(0f,c.tick(120000),0f);assertEquals(16f,c.tick(120016),0f)}
    @Test fun clockDoesNotSlowDownDroppedFramesToEightyMs(){val c=DevelopFrameClock();c.tick(1000);assertEquals(640f,c.tick(1640),0f)}
    @Test fun clockResetSkipsBackgroundAndIdleTime(){val c=DevelopFrameClock();c.tick(10);c.tick(26);c.reset();assertEquals(0f,c.tick(100000),0f);assertEquals(17f,c.tick(100017),0f)}
    @Test fun backwardClockCannotCountAnIntervalTwice(){val c=DevelopFrameClock();c.tick(100);assertEquals(0f,c.tick(99),0f);assertEquals(0f,c.tick(100),0f);assertEquals(10f,c.tick(110),0f)}
    @Test fun clockSameTimestampIsAZeroStep(){val c=DevelopFrameClock();c.tick(10);assertEquals(0f,c.tick(10),0f)}
    @Test fun clockLargeUptimeRemainsFinite(){val c=DevelopFrameClock();c.tick(0);assertTrue(c.tick(Long.MAX_VALUE).isFinite())}
    @Test(expected=IllegalArgumentException::class) fun negativeClockRejected(){DevelopFrameClock().tick(-1)}
    @Test fun colorAndBokehGeometryMeetAtTheFlatBoundary(){val m=combined();m.advance(3300f);assertEquals(0f,m.bokehLift,0f);val q=FloatArray(3);DevelopMath.project(.3f,.7f,.9f,m.bokehLift,1f,q);assertEquals(-.2f,q[0],.000001f);assertEquals(.2f,q[1],.000001f);assertEquals(1f,DevelopMath.colorScale(1f),0f);assertEquals(0f,DevelopMath.colorOffset(1f),0f)}
}
