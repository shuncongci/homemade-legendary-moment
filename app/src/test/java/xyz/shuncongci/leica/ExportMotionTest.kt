package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExportMotionTest {
    private val button=ExportMotion.Rect(260.25f,740.5f,380.75f,796.5f)
    private val menu=ExportMotion.Rect(20f,216.5f,380f,796.5f)
    private fun rect(expected:ExportMotion.Rect,actual:ExportMotion.Rect){assertEquals(expected.left,actual.left,.0001f);assertEquals(expected.top,actual.top,.0001f);assertEquals(expected.right,actual.right,.0001f);assertEquals(expected.bottom,actual.bottom,.0001f)}
    @Test fun openingIsExplicitlyNonlinear(){assertTrue(ExportMotion.openEase(.5f)>.8f);assertTrue(abs(ExportMotion.openEase(.25f)-.25f)>.2f)}
    @Test fun closingHasItsOwnNonlinearCurve(){assertTrue(ExportMotion.closeEase(.5f)<.45f);assertTrue(abs(ExportMotion.closeEase(.25f)-.25f)>.1f);assertTrue(abs(ExportMotion.closeEase(.5f)-ExportMotion.openEase(.5f))>.3f)}
    @Test fun bothCurvesHaveExactEndpoints(){for(f in listOf(ExportMotion::openEase,ExportMotion::closeEase)){assertEquals(0f,f(0f),0f);assertEquals(1f,f(1f),0f);assertEquals(0f,f(-1f),0f);assertEquals(1f,f(2f),0f)}}
    @Test fun openingNeverClipsToOneEarly(){for(t in 1..990)assertTrue("plateau at $t",ExportMotion.openEase(t/1000f)<1f)}
    @Test fun bothCurvesStrictlyMonotonic(){for(f in listOf(ExportMotion::openEase,ExportMotion::closeEase)){var last=-1f;for(i in 0..100){val p=f(i/100f);assertTrue(p>last);last=p}}}
    @Test fun openingStillMovesLateInsteadOfStoppingAt21percent(){assertTrue(ExportMotion.openEase(.75f)>ExportMotion.openEase(.5f));assertTrue(ExportMotion.openEase(.99f)>ExportMotion.openEase(.9f))}
    @Test fun closingActuallyMovesInItsFirstHalf(){val run=ExportMotion.Run(1,1f,0f);assertTrue(run.value(.25f)<.95f);assertTrue(run.value(.5f)<.7f)}
    @Test fun openingAndClosingUseDifferentTimeSamples(){val a=ExportMotion.Run(1,0f,1f);val b=ExportMotion.Run(2,1f,0f);assertNotEquals(a.value(.5f),1-b.value(.5f),.01f)}
    @Test fun exactSourceRectangleAtBothRoundTripEndpoints(){rect(button,ExportMotion.mix(button,menu,0f));rect(menu,ExportMotion.mix(button,menu,1f));rect(button,ExportMotion.mix(button,menu,ExportMotion.Run(2,1f,0f).value(1f)))}
    @Test fun noAddedEndpointTranslationForDensity1To4(){for(d in listOf(1f,1.5f,2f,3f,4f)){val source=ExportMotion.Rect(button.left*d,button.top*d,button.right*d,button.bottom*d);val target=ExportMotion.Rect(menu.left*d,menu.top*d,menu.right*d,menu.bottom*d);rect(source,ExportMotion.mix(source,target,0f))}}
    @Test fun allEdgesShareSameEasedProgress(){for(i in 1..99){val p=ExportMotion.openEase(i/100f);val r=ExportMotion.mix(button,menu,p);assertEquals(p,(r.top-button.top)/(menu.top-button.top),.00001f);assertEquals(p,(r.left-button.left)/(menu.left-button.left),.00001f);assertEquals(button.bottom,r.bottom,0f)}}
    @Test fun geometryNeverOvershootsWindowOrReverses(){var old=button;for(i in 1..100){val r=ExportMotion.mix(button,menu,ExportMotion.openEase(i/100f));assertTrue(r.top<=old.top);assertTrue(r.left<=old.left);assertTrue(r.width>=old.width);assertTrue(r.top>=menu.top);old=r}}
    @Test fun sourceAndTargetCanRetainSubpixelCoordinates(){val r=ExportMotion.mix(button,menu,0f);assertEquals(.25f,r.left%1f,0f);assertEquals(.5f,r.top%1f,0f);assertEquals(.75f,r.right%1f,0f)}
    @Test fun localMappingAccountsForWindowInsetAndParentScroll(){val mapped=ExportMotion.map(ExportMotion.Rect(0f,0f,120f,56f),listOf(ExportMotion.Step(x=250f,y=700f-120f),ExportMotion.Step(x=0f,y=24f)));rect(ExportMotion.Rect(250f,604f,370f,660f),mapped)}
    @Test fun differentDecorOffsetsCancelInTheSameSpace(){val source=ExportMotion.Rect(10f,20f,130f,76f);for(offset in listOf(0f,24f,96f,132.5f)){val inDecor=ExportMotion.map(source,listOf(ExportMotion.Step(x=5f,y=offset)));rect(source.offset(5f,0f),inDecor.offset(0f,-offset))}}
    @Test fun pressedTransformIsNotMistakenForAnUnscaledWidth(){val w=120f;val h=56f;val scale=.965f;val pressed=ExportMotion.map(ExportMotion.Rect(0f,0f,w,h),listOf(ExportMotion.Step(a=scale,e=scale,c=w*(1-scale)/2,f=h*(1-scale)/2,x=260f,y=740f)));assertEquals(w*scale,pressed.width,.0001f);assertEquals(h*scale,pressed.height,.0001f)}
    @Test fun canonicalReleasedTransformHasExactButtonWidth(){val released=ExportMotion.map(ExportMotion.Rect(0f,0f,120f,56f),listOf(ExportMotion.Step(x=260f,y=740f)));rect(ExportMotion.Rect(260f,740f,380f,796f),released)}
    @Test fun parentScalingTranslationAndScrollComposeInOrder(){val r=ExportMotion.map(ExportMotion.Rect(0f,0f,10f,20f),listOf(ExportMotion.Step(x=20f,y=30f),ExportMotion.Step(a=2f,e=3f,x=5f,y=-4f)));rect(ExportMotion.Rect(45f,86f,65f,146f),r)}
    @Test fun mapCornersOnlyThenBoundsAvoidsRotationGrowth(){val rotate=ExportMotion.Step(a=0f,b=-1f,d=1f,e=0f);val undo=ExportMotion.Step(a=0f,b=1f,d=-1f,e=0f);val r=ExportMotion.Rect(1f,2f,10f,30f);rect(r,ExportMotion.map(r,listOf(rotate,undo)))}
    @Test fun mapNestedMirrorKeepsCorrectBounds(){val r=ExportMotion.map(ExportMotion.Rect(0f,0f,100f,50f),listOf(ExportMotion.Step(a=-1f,c=100f,x=20f,y=30f)));rect(ExportMotion.Rect(20f,30f,120f,80f),r)}
    @Test fun onlyShapeMovesNoFullPanelTranslationIsRequired(){for(i in 0..100){val p=ExportMotion.closeEase(i/100f);val r=ExportMotion.mix(button,menu,1-p);assertEquals(button.bottom,r.bottom,0f)}}
    @Test fun contentHasNonlinearFadeWithExactEndpoints(){for(i in 0..12){assertEquals(0f,ExportMotion.content(0f,i),0f);assertEquals(1f,ExportMotion.content(1f,i),0f)};assertTrue(ExportMotion.content(.5f,0)>ExportMotion.content(.5f,5))}
    @Test fun ghostIsExactButtonAtClosedAndAbsentWhenOpen(){assertEquals(1f,ExportMotion.ghost(0f),0f);assertEquals(0f,ExportMotion.ghost(1f),0f);assertTrue(ExportMotion.ghost(.1f) in 0f..1f)}
    @Test fun ghostFadeNeverGoesNegativeOrFlashesBack(){var last=1f;for(i in 0..100){val p=ExportMotion.ghost(i/100f);assertTrue(p<=last && p>=0f);last=p}}
    @Test fun stateBeginsAtExactClosedButton(){val s=ExportMotion.State();assertEquals(0f,s.progress,0f);assertEquals(ExportMotion.Phase.NEW,s.phase)}
    @Test fun completedOpeningIsStable(){val s=ExportMotion.State();val r=s.open();assertTrue(s.accept(r,r.value(.5f)));assertTrue(s.complete(r));assertEquals(1f,s.progress,0f);assertEquals(ExportMotion.Phase.OPEN,s.phase)}
    @Test fun closeStartsFromActualInterruptedProgress(){val s=ExportMotion.State();val a=s.open();s.accept(a,a.value(.2f));val p=s.progress;val b=s.close()!!;assertEquals(p,b.from,0f);assertEquals(p,b.value(0f),0f)}
    @Test fun canceledOpeningCannotWriteAfterCloseStarts(){val s=ExportMotion.State();val a=s.open();s.accept(a,a.value(.3f));val b=s.close()!!;val p=s.progress;assertFalse(s.accept(a,1f));assertFalse(s.complete(a));assertEquals(p,s.progress,0f);assertTrue(s.complete(b))}
    @Test fun closeOnlyCompletesOnce(){val s=ExportMotion.State();s.complete(s.open());val c=s.close()!!;assertTrue(s.complete(c));assertFalse(s.complete(c));assertEquals(0f,s.progress,0f);assertNull(s.close())}
    @Test fun repeatedCloseCannotReplacePendingExportCallback(){val s=ExportMotion.State();s.open();val first=s.close();assertNotNull(first);assertNull(s.close());assertTrue(s.complete(first!!))}
    @Test fun lifecycleDismissInvalidatesOpeningAndClosing(){for(close in listOf(false,true)){val s=ExportMotion.State();val open=s.open();val r=if(close)s.close()!! else open;s.dismiss();assertFalse(s.accept(r,r.to));assertFalse(s.complete(r));assertEquals(ExportMotion.Phase.DISMISSED,s.phase)}}
    @Test fun preLayoutCloseDoesNotNeedAnOpeningRun(){val s=ExportMotion.State();val r=s.close()!!;assertEquals(0f,r.from,0f);assertEquals(0f,r.to,0f);assertTrue(s.complete(r));assertEquals(ExportMotion.Phase.DISMISSED,s.phase)}
    @Test fun closeCanReturnToUpdatedLiveAnchor(){val moved=button.offset(-20f,-48f);rect(moved,ExportMotion.mix(moved,menu,0f));val end=ExportMotion.Run(2,.7f,0f).value(1f);rect(moved,ExportMotion.mix(moved,menu,end))}
    @Test fun disabledAnimationCanCommitExactTerminalFrame(){val s=ExportMotion.State();val a=s.open();s.accept(a,a.to);s.complete(a);assertEquals(1f,s.progress,0f);val b=s.close()!!;s.accept(b,b.to);s.complete(b);assertEquals(0f,s.progress,0f)}
    @Test fun zeroClockFirstAndLastFramesRoundTripWithoutDrift(){repeat(100){val s=ExportMotion.State();val a=s.open();rect(button,ExportMotion.mix(button,menu,a.value(0f)));s.complete(a);val b=s.close()!!;rect(button,ExportMotion.mix(button,menu,b.value(1f)))}}
    @Test fun stateIgnoresOutOfRangeAnimationValues(){val s=ExportMotion.State();val r=s.open();s.accept(r,2f);assertEquals(1f,s.progress,0f);s.accept(r,-1f);assertEquals(0f,s.progress,0f)}
}
