package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PhotoWorkflowTest {
    private fun ready(mode:PhotoWorkflow)=PhotoFlow().apply{photoLoaded();selectProcessingMode(mode.processing?:ProcessingMode.COLOR_ONLY)}
    @Test fun allSevenExplicitChoicesAreUnique(){assertEquals(7,PhotoWorkflow.entries.size);assertEquals(7,PhotoWorkflow.entries.map{it.label}.toSet().size)}
    @Test fun onlyCompositionDoesNotRequestAnEngineMode(){assertNull(PhotoWorkflow.COMPOSITION.processing);assertFalse(PhotoWorkflow.COMPOSITION.composeAfter)}
    @Test fun onlyCompositionNeverNeedsFocus(){val f=ready(PhotoWorkflow.COMPOSITION);assertNull(f.focus);assertTrue(PhotoWorkflow.COMPOSITION.canStart(f))}
    @Test fun noWorkflowStartsWithoutPhoto(){PhotoWorkflow.entries.forEach{assertFalse(it.canStart(PhotoFlow()))}}
    @Test fun everyBusyOperationBlocksEveryWorkflow(){PhotoWorkflow.entries.forEach{w->PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE}.forEach{op->val f=ready(w);f.begin(op);assertFalse(w.canStart(f))}}}
    @Test fun allBlurWorkflowsRequireExplicitFocus(){PhotoWorkflow.entries.filter{it.processing?.blurEnabled==true}.forEach{w->val f=ready(w);assertFalse(w.canStart(f));f.picked(LegendaryMath.Point(.3f,.7f));assertTrue(w.canStart(f))}}
    @Test fun colorCompositionNeedsNoDepthFocus(){val f=ready(PhotoWorkflow.COLOR_COMPOSITION);assertTrue(PhotoWorkflow.COLOR_COMPOSITION.canStart(f));assertFalse(f.blur);assertTrue(f.colorEnabled)}
    @Test fun combinedMapsToBothUnchangedRenderBranches(){assertEquals(ProcessingMode.COLOR_AND_BLUR,PhotoWorkflow.ALL.processing);assertTrue(PhotoWorkflow.ALL.composeAfter)}
    @Test fun cancelledOrFailedNeverAutoNavigates(){PhotoWorkflow.entries.forEach{w->assertFalse(w.shouldOpenComposition(false,false));assertFalse(w.shouldOpenComposition(false,true));assertFalse(w.shouldOpenComposition(true,true))}}
    @Test fun onlyCompletedCompositionChainsNavigate(){PhotoWorkflow.entries.forEach{assertEquals(it.composeAfter,it.shouldOpenComposition(true,false))}}
    @Test fun switchingWorkflowPreservesManualFocusAndOldResult(){val f=ready(PhotoWorkflow.ALL);f.picked(LegendaryMath.Point(.1f,.2f));f.generated();f.selectProcessingMode(ProcessingMode.COLOR_ONLY);assertEquals(LegendaryMath.Point(.1f,.2f),f.focus);assertTrue(f.hasResult);assertTrue(f.canSave);assertTrue(f.resultStale)}
    @Test fun cancellationRestoresAbilityToRetry(){val f=ready(PhotoWorkflow.ALL);f.picked(LegendaryMath.Point(.5f,.5f));f.begin(PhotoFlow.Operation.GENERATE);f.cancel();f.finish();assertTrue(PhotoWorkflow.ALL.canStart(f))}
    @Test fun interpolationEndpointsAreExact(){val a=CompositionGeometry.FULL;val b=CompositionGeometry.Crop(.1,.2,.8,.9);assertSame(a,CompositionMotion.mix(a,b,0f));assertSame(b,CompositionMotion.mix(a,b,1f));assertSame(a,CompositionMotion.mix(a,b,-1f));assertSame(b,CompositionMotion.mix(a,b,2f))}
    @Test fun easeIsMonotonicBoundedAndSymmetric(){var previous=0.0;for(i in 0..1000){val t=i/1000f;val e=CompositionMotion.ease(t);assertTrue(e in 0.0..1.0);assertTrue(e>=previous);assertEquals(1.0,e+CompositionMotion.ease(1-t),0.000001);previous=e}}
    @Test fun everyAnimatedCropIsValidAndWithinEndpointBounds(){val random=Random(22);repeat(500){val a=CompositionGeometry.placed(.05+random.nextDouble()*.95,.05+random.nextDouble()*.95,random.nextDouble(),random.nextDouble());val b=CompositionGeometry.placed(.05+random.nextDouble()*.95,.05+random.nextDouble()*.95,random.nextDouble(),random.nextDouble());for(i in 0..20){val c=CompositionMotion.mix(a,b,i/20f);assertTrue(c.area>0);assertTrue(c.l in minOf(a.l,b.l)..maxOf(a.l,b.l));assertTrue(c.b in minOf(a.b,b.b)..maxOf(a.b,b.b))}}}
    @Test fun animationCannotChangeExportPlan(){val crop=CompositionGeometry.Crop(.125,.125,.875,.875);val before=CompositionOutput.plan(4000,3000,crop,BokehOutput.SIZE_2048);repeat(101){CompositionMotion.mix(CompositionGeometry.FULL,crop,it/100f)};val after=CompositionOutput.plan(4000,3000,crop,BokehOutput.SIZE_2048);assertEquals(before,after);assertEquals(2048,maxOf(after.width,after.height))}
    @Test(expected=IllegalArgumentException::class) fun nonFiniteAnimationRejected(){CompositionMotion.ease(Float.NaN)}
}
