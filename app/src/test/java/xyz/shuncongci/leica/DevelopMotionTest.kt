package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class DevelopMotionTest {
    private fun color(blur:Boolean=false)=DevelopMotion().apply{startColor(blur)}
    private fun readyColor(blur:Boolean=false)=color(blur).apply{colorCompleted()}
    private fun bokeh()=DevelopMotion().apply{startBokeh()}
    private val point=LegendaryMath.Point(.5f,.5f)
    private fun depth()=LegendaryMath.Frame(101,21,1,FloatArray(101*21){(it%101)/100f})
    @Test fun idleHasNoFramesOrProgress(){val m=DevelopMotion();assertEquals(DevelopMotion.Stage.IDLE,m.stage);assertFalse(m.visible);assertFalse(m.needsFrames);assertEquals(0f,m.progress,0f)}
    @Test fun waitingNeverPretendsToFinish(){val m=color();m.advance(1000000f);assertEquals(DevelopMotion.Stage.COLOR_WAIT,m.stage);assertEquals(0f,m.progress,0f);assertFalse(m.finishing)}
    @Test fun colorNeedsActualCallback(){val m=color();m.advance(12000f);m.colorCompleted();assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);assertEquals(0f,m.progress,0f)}
    @Test fun colorAnimationHoldsUntilRealResult(){val m=readyColor();m.advance(10000f);assertEquals(DevelopMotion.Stage.COLOR_HOLD,m.stage);assertFalse(m.needsFrames);m.resultCompleted(false);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun earlyColorResultWaitsForPresentation(){val m=readyColor();m.resultCompleted(false);m.advance(3299f);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);m.advance(1f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun combinedKeepsColorBeforeBokeh(){val m=readyColor(true);m.startBokeh();m.resultCompleted(true);assertEquals(DevelopMotion.Stage.COLOR_FINISH,m.stage);m.advance(3300f);assertEquals(DevelopMotion.Stage.BOKEH_FINISH,m.stage);m.advance(2100f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun slowBlurWaitsWithoutFakeWave(){val m=readyColor(true);m.startBokeh();m.advance(10000f);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);assertFalse(m.finishing);assertEquals(0f,m.progress,0f)}
    @Test fun lateBokehCallbackStartsFromHold(){val m=readyColor(true);m.advance(3300f);m.startBokeh();assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage)}
    @Test fun bokehOnlyHasNoColorStage(){val m=bokeh();assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);m.resultCompleted(true);assertEquals(DevelopMotion.Stage.BOKEH_FINISH,m.stage);m.advance(2100f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun failedFocusDoesNotPlayBokehSuccess(){val m=readyColor(true);m.resultCompleted(false);m.advance(3300f);assertEquals(DevelopMotion.Stage.DONE,m.stage);assertEquals(0f,m.bokehLift,0f)}
    @Test fun noRenderEventDoesNotInventTiles(){val m=DevelopMotion();m.resultCompleted(false);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun resetCancelsEveryActiveStage(){listOf(color(),readyColor(),readyColor().apply{advance(3300f)},bokeh(),bokeh().apply{resultCompleted(true)}).forEach{it.reset();assertEquals(DevelopMotion.Stage.IDLE,it.stage);assertFalse(it.visible);assertEquals(0f,it.seconds,0f)}}
    @Test fun restartDoesNotCarryPriorResult(){val m=readyColor(true);m.startBokeh();m.resultCompleted(true);m.startColor(false);m.colorCompleted();m.advance(9000f);assertEquals(DevelopMotion.Stage.COLOR_HOLD,m.stage)}
    @Test fun reducedWaitingIsNotFakeCompletion(){val m=color();m.reduceMotion();assertEquals(DevelopMotion.Stage.COLOR_WAIT,m.stage);m.colorCompleted();m.reduceMotion();assertEquals(DevelopMotion.Stage.COLOR_HOLD,m.stage)}
    @Test fun reducedActualResultEndsImmediately(){val m=readyColor(true);m.startBokeh();m.resultCompleted(true);m.reduceMotion();assertEquals(DevelopMotion.Stage.DONE,m.stage);assertFalse(m.needsFrames)}
    @Test fun reducedColorThenSlowBlurCanResume(){val m=readyColor(true);m.reduceMotion();m.startBokeh();m.advance(500f);m.resultCompleted(true);m.reduceMotion();assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun bokehLiftSettlesFromCurrentFrameOnResult(){for(ms in listOf(0f,50f,200f,550f,2000f)){val m=bokeh();m.advance(ms);val old=m.bokehLift;m.resultCompleted(true);assertEquals(old,m.bokehLift,0f);m.advance(210f);assertEquals(old*.5f,m.bokehLift,.001f);m.advance(210f);assertEquals(0f,m.bokehLift,0f)}}
    @Test fun combinedFastResultNeverReplaysLift(){val m=readyColor(true);m.startBokeh();m.resultCompleted(true);m.advance(3300f);assertEquals(0f,m.bokehLift,0f);m.advance(400f);assertEquals(0f,m.bokehLift,0f);m.advance(20f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test fun oneLargeStepMatchesSmallSteps(){
        fun sequence()=readyColor(true).apply{startBokeh();resultCompleted(true)}
        val a=sequence();val b=sequence();a.advance(4300f);repeat(430){b.advance(10f)}
        assertEquals(a.stage,b.stage);assertEquals(a.progress,b.progress,.00001f);assertEquals(a.bokehLift,b.bokehLift,.00001f)
    }
    @Test fun zeroStepDoesNotMove(){val m=readyColor();m.advance(0f);assertEquals(0f,m.progress,0f)}
    @Test(expected=IllegalArgumentException::class) fun negativeTimeRejected(){color().advance(-1f)}
    @Test(expected=IllegalArgumentException::class) fun nanTimeRejected(){color().advance(Float.NaN)}
    @Test(expected=IllegalArgumentException::class) fun infiniteTimeRejected(){color().advance(Float.POSITIVE_INFINITY)}
    @Test(expected=IllegalStateException::class) fun colorReadyWithoutStartRejected(){DevelopMotion().colorCompleted()}
    @Test(expected=IllegalStateException::class) fun duplicateResultRejected(){val m=bokeh();m.resultCompleted(true);m.resultCompleted(true)}
    @Test(expected=IllegalStateException::class) fun resultBeforeColorDataRejected(){color().resultCompleted(false)}
    @Test(expected=IllegalStateException::class) fun duplicateBokehRejected(){bokeh().startBokeh()}
    @Test fun completionFiresOnce(){val c=DevelopCompletion();var n=0;c.await{assertTrue(it);n++};c.resolve(true);c.resolve(false);c.resolve(true);assertEquals(1,n);assertFalse(c.pending)}
    @Test fun resetCallbackReportsCancellationNotSuccess(){val c=DevelopCompletion();var result:Boolean?=null;c.await{result=it};c.resolve(false);assertEquals(false,result);assertFalse(c.pending)}
    @Test fun callbackClearedBeforeReentry(){val c=DevelopCompletion();var n=0;c.await{n++;c.await{n++}};c.resolve(true);assertTrue(c.pending);c.resolve(false);assertEquals(2,n)}
    @Test(expected=IllegalStateException::class) fun pendingCompletionCannotBeSilentlyReplaced(){val c=DevelopCompletion();c.await{};c.await{}}
    @Test fun colorStartsAndEndsAtFullFrame(){for(p in listOf(0f,1f)){assertEquals(1f,DevelopMath.colorScale(p),0f);assertEquals(0f,DevelopMath.colorOffset(p),0f)};assertEquals(.75f,DevelopMath.colorScale(.5f),0f);assertEquals(-.14f,DevelopMath.colorOffset(.5f),0f)}
    @Test fun shrinkingDoesNotClipPhoto(){for(i in 0..1000){val p=i/1000f;val half=.94f*DevelopMath.colorScale(p)/2;val center=.5f+DevelopMath.colorOffset(p);assertTrue(center-half>=0f && center+half<=1f)}}
    @Test fun revealOnlyAfterBeamArrival(){for(y in 0..20)for(x in 0..20){assertEquals(0f,DevelopMath.revealed(x/20f,y/20f,DevelopMath.IMPACT),0f);assertEquals(1f,DevelopMath.revealed(x/20f,y/20f,.94f),0f)}}
    @Test fun revealStartsNearActualImpact(){assertTrue(DevelopMath.revealed(.68f,.48f,.70f)>DevelopMath.revealed(0f,1f,.70f));assertEquals(1f,DevelopMath.stripped(.5f),0f)}
    @Test fun veilTipMeetsRevealCenterForEveryStrand(){val q=FloatArray(4);for(i in 0..22){DevelopMath.colorVeilSample(i/23f,0f,DevelopMath.IMPACT,1.5f,q);assertEquals(.68f,q[0],.00001f);assertEquals(.48f,q[1],.00001f)}}
    @Test fun gaseousPathsStayFinite(){val q=FloatArray(4);for(i in 0..100)for(j in 0..32){DevelopMath.colorVeilSample(.27f,j/32f,i/100f,1.5f,q);assertTrue(q.all{it.isFinite()});DevelopMath.stripPoint(.18f,j/32f,i/100f,.3f,.7f,q);assertTrue(q.all{it.isFinite()})}}
    @Test fun pigmentEveryRegionLosesAndRegainsColor(){for(y in 0..8)for(x in 0..8){val values=(0..240).map{DevelopMath.pigment(x/8f,y/8f,it*.1f)};assertTrue(values.all{it in .06f..1f});assertTrue(values.max()-values.min()>.35f)}}
    @Test fun shockwaveBandsAreOrderedAndExpand(){for(i in 0..100){val p=i/100f;for(b in 1..3)assertTrue(DevelopMath.wave(p,b)<=DevelopMath.wave(p,b-1));if(i>0)for(b in 0..3)assertTrue(DevelopMath.wave(p,b)>=DevelopMath.wave((i-1)/100f,b))}}
    @Test fun zeroLiftIsExactTwoDimensionalImage(){val out=FloatArray(3);for(a in listOf(.05f,.75f,1f,2f,30f))for(x in listOf(0f,.5f,1f))for(y in listOf(0f,.5f,1f)){DevelopMath.project(x,y,1f,0f,a,out);assertEquals((x-.5f)*min(1f,a),out[0],.000001f);assertEquals((y-.5f)*min(1f,1/a),out[1],.000001f);assertEquals(0f,out[2],0f)}}
    @Test fun focusedTileLiftsMoreThanBackground(){val a=FloatArray(3);val b=FloatArray(3);DevelopMath.project(.5f,.5f,0f,.78f,1f,a);DevelopMath.project(.5f,.5f,1f,.78f,1f,b);assertTrue(b[2]>a[2]+.15f)}
    @Test fun allTileProjectionCornersFinite(){val out=FloatArray(3);for(a in listOf(.01f,.75f,1f,2f,100f))for(l in 0..20)for(i in 0..10)for(j in 0..10){DevelopMath.project(i/10f,j/10f,(i%3)/2f,l/20f,a,out);assertTrue(out.all{it.isFinite()})}}
    @Test fun tinyImagesHaveNonzeroBoundedTiles(){for((w,h) in listOf(1 to 1,1 to 100,100 to 1,2 to 3,4000 to 3000)){val d=LegendaryMath.Frame(9,9,1,FloatArray(81){.5f+it*.000001f});val t=DevelopMath.focusTiles(d,point,2.8f,w,h,Cancel())!!;assertTrue(t.cols in 1..min(22,w));assertTrue(t.rows in 1..min(22,h));assertEquals(t.cols*t.rows,t.values.size);assertTrue(t.values.all{it==1f})}}
    @Test fun constantDepthStillRejectedByFocusSafety(){val d=LegendaryMath.Frame(9,9,1,FloatArray(81){.5f});assertNull(DevelopMath.focusTiles(d,point,2.8f,100,100,Cancel()))}
    @Test fun manualFocusChangesLiftedRegion(){val d=depth();val a=DevelopMath.focusTiles(d,LegendaryMath.Point(.2f,.5f),.95f,2048,1024,Cancel())!!;val b=DevelopMath.focusTiles(d,LegendaryMath.Point(.8f,.5f),.95f,2048,1024,Cancel())!!;fun center(t:DevelopMath.FocusTiles):Float {var sum=0f;var n=0f;for(i in t.values.indices){sum+=(i%t.cols)*t.values[i];n+=t.values[i]};return sum/n};assertTrue(center(a)<center(b))}
    @Test fun unreliableFocusNotReplacedByAutoSelection(){val d=LegendaryMath.Frame(101,21,1,FloatArray(2121){if(it%101<50)0f else 1f});assertNull(DevelopMath.focusTiles(d,point,2.8f,2048,1024,Cancel()))}
    @Test fun focusTilesNeverModifyDepthOrExportPlan(){val d=depth();val before=d.data.copyOf();val crop=CompositionGeometry.Crop(.1,.1,.9,.9);val plan=CompositionOutput.plan(4000,3000,crop,BokehOutput.SIZE_2048);DevelopMath.focusTiles(d,point,2.8f,plan.width,plan.height,Cancel());assertArrayEquals(before,d.data,0f);assertEquals(plan,CompositionOutput.plan(4000,3000,crop,BokehOutput.SIZE_2048))}
    @Test(expected=CancellationException::class) fun cancelledFocusSamplingStops(){DevelopMath.focusTiles(depth(),point,2.8f,2048,1024,Cancel().apply{cancelled=true})}
    @Test(expected=IllegalArgumentException::class) fun malformedTileShapeRejected(){DevelopMath.FocusTiles(3,2,FloatArray(5))}
    @Test(expected=IllegalArgumentException::class) fun nonFiniteTileRejected(){DevelopMath.FocusTiles(1,1,floatArrayOf(Float.NaN))}
    @Test(expected=IllegalArgumentException::class) fun invalidAspectRejected(){DevelopMath.project(.5f,.5f,1f,0f,0f,FloatArray(3))}
    @Test(expected=IllegalArgumentException::class) fun invalidEaseRejected(){DevelopMath.ease(Float.NaN)}
    private val wallBase=floatArrayOf(0f,0f,10f,0f,10f,10f,0f,10f)
    private fun shiftedWall(dx:Float,dy:Float)=FloatArray(8){wallBase[it]+if(it%2==0)dx else dy}
    @Test fun wallCullingKeepsOnlyTopAndLeftWhenLiftMovesDownRight(){val t=shiftedWall(2f,3f);assertEquals(listOf(true,false,false,true),(0..3).map{DevelopMath.visibleSide(wallBase,t,it)});assertArrayEquals(floatArrayOf(0f,0f,10f,0f,10f,10f,0f,10f),wallBase,0f)}
    @Test fun oppositeViewShowsOppositeWalls(){val t=shiftedWall(-2f,-3f);assertEquals(listOf(false,true,true,false),(0..3).map{DevelopMath.visibleSide(wallBase,t,it)})}
    @Test fun flatTilesHaveNoVisibleWalls(){for(i in 0..3)assertFalse(DevelopMath.visibleSide(wallBase,wallBase.copyOf(),i))}
    @Test(expected=IllegalArgumentException::class) fun malformedWallRejected(){DevelopMath.visibleSide(FloatArray(7),wallBase,0)}
    @Test(expected=IllegalArgumentException::class) fun invalidWallEdgeRejected(){DevelopMath.visibleSide(wallBase,wallBase,4)}
    @Test(expected=IllegalArgumentException::class) fun nonfiniteWallRejected(){DevelopMath.visibleSide(wallBase,wallBase.copyOf().apply{this[0]=Float.NaN},0)}
    private fun veil(seed:Float=.5f,u:Float=.5f,p:Float=.55f,aspect:Float=1f)=FloatArray(4).also{DevelopMath.colorVeilSample(seed,u,p,aspect,it)}
    @Test fun veilOriginIsNearPhotoEdge(){for(s in listOf(0f,.5f,1f))for(u in listOf(0f,.5f,1f)){val q=veil(s,u,.42f);assertEquals(1.025f,q[0],.000001f);assertTrue(q[1] in .46f.. .50f)}}
    @Test fun veilNeverTeleportsAcrossScreen(){for(a in listOf(.03f,.5f,1f,2f,30f))for(p in 0..100)for(s in listOf(0f,.5f,1f)){val q=veil(s,.5f,p/100f,a);assertTrue(q[0] in .67999f..1.02501f);assertTrue(q[1] in .35f.. .51f)}}
    @Test fun veilXMovesOnlyTowardPhoto(){for(u in listOf(0f,.5f,1f)){var x=2f;for(p in 0..1000){val next=veil(u=u,p=p/1000f)[0];assertTrue(next<=x+.000001f);x=next}}}
    @Test fun veilAllTrailsMeetBeforeFadeEnds(){for(s in listOf(0f,.5f,1f))for(u in listOf(0f,.5f,1f)){val q=veil(s,u,.78f);assertEquals(.68f,q[0],.000001f);assertEquals(.48f,q[1],.000001f)}}
    @Test fun veilNoAppearanceOutsideItsWindow(){for(p in listOf(-1f,0f,.36f,.81f,1f,2f))assertEquals(0f,veil(p=p)[3],0f)}
    @Test fun veilOpacityAndRadiusRemainSubtle(){for(p in 0..1000)for(u in 0..23){val q=veil(u=u/23f,p=p/1000f);assertTrue(q[3] in 0f.. .07001f);assertTrue(q[2] in .039f.. .068f)}}
    @Test fun veilMotionAndOpacityAreContinuous(){var old=veil(p=0f);for(p in 1..1000){val q=veil(p=p/1000f);for(i in 0..3)assertTrue(abs(q[i]-old[i])<.01f);old=q}}
    @Test fun veilDoesNotChangeResultTiming(){val m=readyColor();m.resultCompleted(false);repeat(100){veil(p=it/100f)};assertEquals(0f,m.progress,0f);m.advance(3300f);assertEquals(DevelopMotion.Stage.DONE,m.stage)}
    @Test(expected=IllegalArgumentException::class) fun veilRejectsInvalidAspect(){veil(aspect=0f)}
    @Test(expected=IllegalArgumentException::class) fun veilRejectsNanProgress(){veil(p=Float.NaN)}
    @Test(expected=IllegalArgumentException::class) fun veilRejectsOutsideSeed(){veil(seed=1.01f)}
    @Test(expected=IllegalArgumentException::class) fun veilRejectsShortOutput(){DevelopMath.colorVeilSample(.5f,.5f,.5f,1f,FloatArray(3))}
    @Test fun finalResultStillRequiredAfterAnyAmountOfWait(){val m=bokeh();m.advance(Float.MAX_VALUE);assertEquals(DevelopMotion.Stage.BOKEH_WAIT,m.stage);assertTrue(m.seconds.isFinite());assertEquals(.78f,m.bokehLift,0f)}
}
