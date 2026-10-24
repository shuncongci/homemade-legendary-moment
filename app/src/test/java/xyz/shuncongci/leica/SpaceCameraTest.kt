package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class SpaceCameraTest {
    private fun finger(id:Int,x:Float,y:Float)=SpaceTouch.Finger(id,x,y)
    private fun touch(camera:SpaceCamera)=SpaceTouch(camera,8f,60f)
    private fun state(c:SpaceCamera)=floatArrayOf(c.yaw,c.pitch,c.zoom,c.panX,c.panY)
    private fun tapped(t:SpaceTouch,x:Float,y:Float,at:Long){t.down(finger(1,x,y),at);assertTrue(t.up(finger(1,x,y),at+40))}
    @Test fun defaultMatchesExistingSpatialView(){val c=SpaceCamera();assertArrayEquals(floatArrayOf(.38f,-.14f,1f,0f,0f),state(c),0f)}
    @Test fun resetRestoresAllFiveCameraValues(){val c=SpaceCamera();c.rotate(.2f,.2f);c.transform(2f,.5f,.5f,.6f,.7f);c.reset();assertArrayEquals(state(SpaceCamera()),state(c),0f)}
    @Test fun rotationStillUsesExistingDirectionAndScale(){val c=SpaceCamera();c.rotate(.1f,.1f);assertEquals(.56f,c.yaw,1e-7f);assertEquals(-.24f,c.pitch,1e-7f)}
    @Test fun rotationRemainsWithinSafeProjectionRange(){val c=SpaceCamera();c.rotate(100f,100f);assertEquals(.85f,c.yaw,0f);assertEquals(-.55f,c.pitch,0f);c.rotate(-200f,-200f);assertEquals(-.85f,c.yaw,0f);assertEquals(.55f,c.pitch,0f)}
    @Test fun zoomButtonsAreReciprocalAwayFromLimits(){val c=SpaceCamera();repeat(4){c.zoomBy(1.25f)};repeat(4){c.zoomBy(.8f)};assertEquals(1f,c.zoom,2e-7f);assertEquals(0f,c.panX,0f)}
    @Test fun zoomLimitsAreHalfToFourTimes(){val c=SpaceCamera();c.zoomBy(1000f);assertEquals(4f,c.zoom,0f);c.zoomBy(.000001f);assertEquals(.5f,c.zoom,0f)}
    @Test fun overflowingZoomProductIsStillBounded(){val c=SpaceCamera();c.zoomBy(4f);c.zoomBy(Float.MAX_VALUE);assertEquals(4f,c.zoom,0f)}
    @Test fun centerZoomKeepsCenteredPhotoCentered(){val c=SpaceCamera();c.zoomBy(2f);assertEquals(0f,c.panX,0f);assertEquals(0f,c.panY,0f)}
    @Test fun offCenterZoomKeepsTouchedPointAnchored(){val c=SpaceCamera();c.transform(1.5f,.6f,.4f,.6f,.4f);assertEquals(-.05f,c.panX,1e-7f);assertEquals(.05f,c.panY,1e-7f)
        val pointX=(.6f-.5f)/1f;val pointY=(.4f-.5f)/1f;assertEquals(.6f,.5f+c.panX+pointX*c.zoom,1e-7f);assertEquals(.4f,.5f+c.panY+pointY*c.zoom,1e-7f)}
    @Test fun pinchPanUsesPreviousAndNewFocus(){val c=SpaceCamera();c.transform(2f,.6f,.45f,.65f,.55f);assertEquals(-.05f,c.panX,1e-7f);assertEquals(.15f,c.panY,1e-7f)}
    @Test fun pinchAtZoomLimitDoesNotCreateFalseScalePan(){val c=SpaceCamera();c.zoomBy(4f);c.transform(2f,.6f,.4f,.6f,.4f);assertEquals(0f,c.panX,0f);assertEquals(0f,c.panY,0f)}
    @Test fun purePanDoesNotChangeZoomOrRotation(){val c=SpaceCamera();c.transform(1f,.5f,.5f,.7f,.6f);assertEquals(1f,c.zoom,0f);assertEquals(.2f,c.panX,1e-7f);assertEquals(.1f,c.panY,1e-7f);assertEquals(.38f,c.yaw,0f)}
    @Test fun panningIsBoundedAndShrinksWithZoom(){val c=SpaceCamera();c.transform(1f,0f,0f,100f,-100f);assertEquals(.45f,c.panX,0f);assertEquals(-.45f,c.panY,0f);c.zoomBy(.5f);assertEquals(.225f,c.panX,1e-7f);assertEquals(-.225f,c.panY,1e-7f)}
    @Test fun randomTransformsAlwaysStayFiniteAndBounded(){val c=SpaceCamera();val r=Random(228);repeat(5000){c.transform(.2f+r.nextFloat()*3f,r.nextFloat(),r.nextFloat(),r.nextFloat(),r.nextFloat());assertTrue(c.zoom in .5f..4f);val limit=min(1.5f,c.zoom*.45f);assertTrue(c.panX.isFinite() && abs(c.panX)<=limit);assertTrue(c.panY.isFinite() && abs(c.panY)<=limit)}}
    @Test fun invalidZoomIsRejectedWithoutMutation(){val c=SpaceCamera();val before=state(c);for(f in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))try{c.zoomBy(f);fail()}catch(_:IllegalArgumentException){};assertArrayEquals(before,state(c),0f)}
    @Test fun invalidFocusIsRejectedWithoutMutation(){val c=SpaceCamera();val before=state(c);try{c.transform(1f,Float.NaN,0f,0f,0f);fail()}catch(_:IllegalArgumentException){};assertArrayEquals(before,state(c),0f)}
    @Test fun invalidRotationIsRejectedWithoutMutation(){val c=SpaceCamera();try{c.rotate(Float.NaN,0f);fail()}catch(_:IllegalArgumentException){};assertArrayEquals(state(SpaceCamera()),state(c),0f)}
    @Test fun normalAndFullscreenKeepSameNormalizedCamera(){val c=SpaceCamera();c.rotate(.1f,.1f);c.transform(2f,.5f,.5f,.55f,.6f);val before=state(c);val t=touch(c);t.cancel();assertArrayEquals(before,state(c),0f)
        for((w,h) in listOf(320 to 240,1080 to 1800,1800 to 1080)){val x=(w*(.5f+c.panX)+.2f*w*c.zoom)/w;assertEquals(.5f+c.panX+.2f*c.zoom,x,1e-6f);assertTrue(h>0)}}
    @Test fun smallFingerJitterDoesNotRotate(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.move(listOf(finger(1,104f,103f)),400,300);assertArrayEquals(state(SpaceCamera()),state(c),0f);assertTrue(t.up(finger(1,104f,103f),50))}
    @Test fun firstRealOrbitRetainsFullDownDelta(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.move(listOf(finger(1,104f,100f)),400,300);t.move(listOf(finger(1,140f,130f)),400,300);assertEquals(.56f,c.yaw,1e-7f);assertEquals(-.24f,c.pitch,1e-7f)}
    @Test fun dragIsNotATap(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.move(listOf(finger(1,150f,100f)),400,300);assertFalse(t.up(finger(1,150f,100f),100))}
    @Test fun pinchChangesZoomWithoutRotation(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,150f,150f),0);t.rebase(listOf(finger(1,150f,150f),finger(2,250f,150f)));t.move(listOf(finger(1,100f,150f),finger(2,300f,150f)),400,300);assertEquals(2f,c.zoom,0f);assertEquals(.38f,c.yaw,0f);assertEquals(-.14f,c.pitch,0f)}
    @Test fun twoFingerParallelDragPans(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));t.move(listOf(finger(1,140f,130f),finger(2,240f,130f)),400,300);assertEquals(1f,c.zoom,0f);assertEquals(.1f,c.panX,1e-7f);assertEquals(.1f,c.panY,1e-7f)}
    @Test fun pointerDownOnlyRebasesWithoutCameraJump(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);val before=state(c);t.rebase(listOf(finger(1,100f,100f),finger(2,300f,250f)));assertArrayEquals(before,state(c),0f)}
    @Test fun liftingOnePinchFingerCannotJumpIntoOrbit(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));t.rebase(listOf(finger(2,200f,100f)));val before=state(c);t.move(listOf(finger(2,350f,250f)),400,300);assertArrayEquals(before,state(c),0f);assertFalse(t.up(finger(2,350f,250f),200))}
    @Test fun pinchThenNewDownCanOrbitNormally(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));t.up(finger(1,100f,100f),50);t.down(finger(1,100f,100f),200);t.move(listOf(finger(1,140f,100f)),400,300);assertEquals(.56f,c.yaw,1e-7f)}
    @Test fun pointerIdsSurviveArrayOrderChanges(){val c=SpaceCamera();val t=touch(c);t.down(finger(3,100f,100f),0);t.rebase(listOf(finger(3,100f,100f),finger(9,200f,100f)));t.move(listOf(finger(9,250f,100f),finger(3,50f,100f)),400,300);assertEquals(2f,c.zoom,0f)}
    @Test fun thirdFingerTransitionsOnlyRebase(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));val before=state(c);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f),finger(3,300f,100f)));t.rebase(listOf(finger(2,200f,100f),finger(3,300f,100f)));t.move(listOf(finger(2,200f,100f),finger(3,300f,100f)),400,300);assertArrayEquals(before,state(c),1e-7f)}
    @Test fun missingPointerInMoveCannotApplyStaleSpan(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));t.move(listOf(finger(7,300f,100f),finger(8,350f,100f)),400,300);assertArrayEquals(state(SpaceCamera()),state(c),0f)}
    @Test fun coincidentFingersNeverDivideByZero(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.rebase(listOf(finger(1,100f,100f),finger(2,100f,100f)));t.move(listOf(finger(1,100f,100f),finger(2,101f,100f)),400,300);assertTrue(state(c).all{it.isFinite()});assertEquals(1f,c.zoom,0f)}
    @Test fun cancellationInvalidatesOldFingerDeltas(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.cancel();t.move(listOf(finger(1,300f,200f)),400,300);assertArrayEquals(state(SpaceCamera()),state(c),0f);assertFalse(t.up(finger(1,300f,200f),100))}
    @Test fun cancelPreservesCameraForResizeAndFullscreenHandoff(){val c=SpaceCamera();c.rotate(.1f,.2f);c.zoomBy(2f);val before=state(c);val t=touch(c);t.down(finger(1,100f,100f),0);t.cancel();assertArrayEquals(before,state(c),0f)}
    @Test fun zeroViewportDoesNotCorruptCamera(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);t.move(listOf(finger(1,300f,200f)),0,0);assertArrayEquals(state(SpaceCamera()),state(c),0f)}
    @Test fun realDoubleTapResetsZoomPanAndAngles(){val c=SpaceCamera();c.rotate(.1f,.1f);c.transform(2f,.5f,.5f,.6f,.6f);val t=touch(c);tapped(t,100f,100f,1000);tapped(t,110f,110f,1200);assertArrayEquals(state(SpaceCamera()),state(c),0f)}
    @Test fun distantTapsDoNotReset(){val c=SpaceCamera();c.zoomBy(2f);val t=touch(c);tapped(t,100f,100f,1000);tapped(t,300f,100f,1200);assertEquals(2f,c.zoom,0f)}
    @Test fun expiredDoubleTapDoesNotReset(){val c=SpaceCamera();c.zoomBy(2f);val t=touch(c);tapped(t,100f,100f,1000);tapped(t,100f,100f,1500);assertEquals(2f,c.zoom,0f)}
    @Test fun longPressDoesNotCountAsTap(){val c=SpaceCamera();val t=touch(c);t.down(finger(1,100f,100f),0);assertFalse(t.up(finger(1,100f,100f),1000))}
    @Test fun pinchBetweenTapsCannotProduceFalseDoubleTap(){val c=SpaceCamera();c.zoomBy(2f);val t=touch(c);tapped(t,100f,100f,1000);t.down(finger(1,100f,100f),1100);t.rebase(listOf(finger(1,100f,100f),finger(2,200f,100f)));assertFalse(t.up(finger(1,100f,100f),1120));tapped(t,100f,100f,1200);assertEquals(2f,c.zoom,0f)}
    @Test fun cancelBetweenTapsCannotProduceFalseDoubleTap(){val c=SpaceCamera();c.zoomBy(2f);val t=touch(c);tapped(t,100f,100f,1000);t.cancel();tapped(t,100f,100f,1200);assertEquals(2f,c.zoom,0f)}
    @Test fun dragBetweenTapsCannotProduceFalseDoubleTap(){val c=SpaceCamera();c.zoomBy(2f);val t=touch(c);tapped(t,100f,100f,1000);t.down(finger(1,100f,100f),1080);t.move(listOf(finger(1,140f,100f)),400,300);t.up(finger(1,140f,100f),1110);tapped(t,100f,100f,1200);assertEquals(2f,c.zoom,0f)}
}
