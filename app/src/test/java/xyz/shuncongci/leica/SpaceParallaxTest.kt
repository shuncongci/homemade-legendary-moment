package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class SpaceParallaxTest {
    private fun q(x:Double=0.0,y:Double=0.0,z:Double=0.0):FloatArray {
        val a=sqrt(x*x+y*y+z*z);val s=if(a<1e-12).5 else sin(a/2)/a
        return floatArrayOf((x*s).toFloat(),(y*s).toFloat(),(z*s).toFloat(),cos(a/2).toFloat())
    }
    private fun multiply(a:FloatArray,b:FloatArray)=floatArrayOf(
        a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],
        a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],
        a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],
        a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2])
    private fun fresh(rotation:Int=0)=SpaceParallax().apply{start();assertTrue(sample(q(),1_000_000_000L,rotation))}
    private fun offsets(p:SpaceParallax)=floatArrayOf(p.yaw,p.pitch)
    private fun zero(p:SpaceParallax){assertEquals(0f,p.yaw,0f);assertEquals(0f,p.pitch,0f)}
    private fun settle(p:SpaceParallax,pose:FloatArray,rotation:Int=0){repeat(100){assertTrue(p.sample(pose,1_033_000_000L+it*33_000_000L,rotation))}}
    @Test fun disabledByDefaultAndIgnoresSamples(){val p=SpaceParallax();assertFalse(p.enabled);assertFalse(p.sample(q(y=.2),1,0));zero(p)}
    @Test fun firstHeldPoseIsNeutral(){val p=SpaceParallax();p.start();assertTrue(p.sample(q(.8,.5,-.4),100,0));zero(p)}
    @Test fun repeatedSamePoseDoesNotIntegrateDrift(){val p=SpaceParallax();p.start();val pose=q(.8,.5,-.4);repeat(1000){p.sample(pose,100L+it*33_000_000L,0)};zero(p)}
    @Test fun positivePhoneYawCreatesPositiveDisplayOverlay(){val p=fresh();settle(p,q(y=.2));assertEquals(((.2-.004)*.65).toFloat(),p.yaw,1e-6f);assertEquals(0f,p.pitch,0f)}
    @Test fun negativePhoneYawCreatesNegativeDisplayOverlay(){val p=fresh();settle(p,q(y=-.2));assertEquals(-((.2-.004)*.65).toFloat(),p.yaw,1e-6f)}
    @Test fun positivePhonePitchCreatesNegativeDisplayOverlay(){val p=fresh();settle(p,q(x=.15));assertEquals(-((.15-.004)*.65).toFloat(),p.pitch,1e-6f);assertEquals(0f,p.yaw,0f)}
    @Test fun pureScreenRollDoesNotTiltScene(){val p=fresh();settle(p,q(z=.8));zero(p)}
    @Test fun nonIdentityReferenceUsesLocalNotWorldAxes(){val ref=q(.7,-.4,.9);val p=SpaceParallax().apply{start();sample(ref,1_000_000_000L,0)};settle(p,multiply(ref,q(y=.2)));assertEquals(.1274f,p.yaw,1e-6f);assertEquals(0f,p.pitch,1e-6f)}
    @Test fun sharedWorldRotationCancelsOut(){val ref=q(.2,.4,-.1);val change=q(.1,.15,.02);val world=q(.9,-.6,.3);val a=SpaceParallax().apply{start();sample(ref,1_000_000_000L,0)};val b=SpaceParallax().apply{start();sample(multiply(world,ref),1_000_000_000L,0)};settle(a,multiply(ref,change));settle(b,multiply(multiply(world,ref),change));assertArrayEquals(offsets(a),offsets(b),1e-6f)}
    @Test fun quaternionSignDoesNotChangePose(){val a=fresh();val b=fresh();val pose=q(.1,.2,.3);settle(a,pose);settle(b,pose.map{-it}.toFloatArray());assertArrayEquals(offsets(a),offsets(b),1e-7f)}
    @Test fun quaternionNormalizationIsScaleInvariant(){val a=fresh();val b=fresh();val pose=q(.1,.2,.3);settle(a,pose);settle(b,pose.map{it*3}.toFloatArray());assertArrayEquals(offsets(a),offsets(b),1e-7f)}
    @Test fun threeComponentAndroidVectorReconstructsW(){val a=fresh();val b=fresh();val pose=q(.1,.2,.3);settle(a,pose);settle(b,pose.copyOf(3));assertArrayEquals(offsets(a),offsets(b),1e-6f)}
    @Test fun fifthHeadingAccuracyValueIsNotUsed(){val a=fresh();val b=fresh();val pose=q(.1,.2,.3);settle(a,pose);settle(b,pose+floatArrayOf(Float.NaN));assertArrayEquals(offsets(a),offsets(b),0f)}
    @Test fun portraitAndAllScreenRotationsRemapAxes(){
        val expected=listOf(floatArrayOf(.1274f,-.0624f),floatArrayOf(-.0624f,-.1274f),floatArrayOf(-.1274f,.0624f),floatArrayOf(.0624f,.1274f))
        for(rotation in 0..3){val p=fresh(rotation);settle(p,q(.1,.2,0.0),rotation);assertArrayEquals(expected[rotation],offsets(p),1e-6f)}
    }
    @Test fun displayRotationChangeRebasesWithoutOldTilt(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);assertTrue(p.yaw>0);assertTrue(p.sample(q(y=.2),1_066_000_000L,1));zero(p)}
    @Test fun referenceAcrossHeadingWrapDoesNotJump(){val p=SpaceParallax().apply{start();sample(q(y=Math.toRadians(179.0)),1_000_000_000L,0)};settle(p,q(y=Math.toRadians(-179.0)));assertEquals(((Math.toRadians(2.0)-.004)*.65).toFloat(),p.yaw,1e-6f)}
    @Test fun oneStepSmoothingMatchesIndependentExponential(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);val expected=.1274*(1-exp(-.033/.09));assertEquals(expected.toFloat(),p.yaw,1e-7f);assertTrue(abs(p.yaw)<.1274f)}
    @Test fun smoothingIsFrameRateIndependentForConstantPose(){val a=fresh();val b=fresh();repeat(30){a.sample(q(y=.2),1_000_000_000L+(it+1)*10_000_000L,0)};repeat(10){b.sample(q(y=.2),1_000_000_000L+(it+1)*30_000_000L,0)};assertArrayEquals(offsets(a),offsets(b),1e-7f)}
    @Test fun tinyJitterFallsInsideDeadZone(){val p=fresh();settle(p,q(.003,-.003,.001));zero(p)}
    @Test fun deadZoneBoundaryHasNoSuddenStep(){val p=fresh();settle(p,q(y=.0041));assertTrue(abs(p.yaw)<.0001f);assertTrue(p.yaw>0)}
    @Test fun yawAndPitchHaveIndependentComfortLimits(){val a=fresh();val b=fresh();settle(a,q(y=1.5));settle(b,q(x=-1.5));assertEquals(.24f,a.yaw,1e-6f);assertEquals(.18f,b.pitch,1e-6f)}
    @Test fun randomPosesStayFiniteAndBounded(){val p=fresh();val r=Random(229);repeat(5000){val pose=q(r.nextDouble()*6-3,r.nextDouble()*6-3,r.nextDouble()*6-3);assertTrue(p.sample(pose,1_033_000_000L+it*33_000_000L,it/500%4));assertTrue(p.yaw.isFinite() && abs(p.yaw)<=.24f);assertTrue(p.pitch.isFinite() && abs(p.pitch)<=.18f)}}
    @Test fun duplicateAndOutOfOrderSamplesAreIgnored(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);val before=offsets(p);assertFalse(p.sample(q(y=-.4),1_033_000_000L,0));assertFalse(p.sample(q(y=-.4),1_020_000_000L,0));assertArrayEquals(before,offsets(p),0f)}
    @Test fun longSensorGapRebasesInsteadOfApplyingOldTilt(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);assertTrue(p.sample(q(y=.9),2_000_000_000L,0));zero(p)}
    @Test fun exactlyHalfSecondGapStillFilters(){val p=fresh();assertTrue(p.sample(q(y=.2),1_500_000_000L,0));assertTrue(p.yaw>0)}
    @Test fun recenterResetsNowAndNextHeldPose(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);p.recenter();zero(p);p.sample(q(y=.7),1_066_000_000L,0);zero(p);assertTrue(p.enabled)}
    @Test fun recenterDoesNotAdmitQueuedOldSample(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);p.recenter();assertFalse(p.sample(q(y=.8),1_020_000_000L,0));zero(p)}
    @Test fun stopClearsAndLateSamplesCannotRestoreOffset(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);p.stop();assertFalse(p.enabled);assertFalse(p.sample(q(y=.3),1_066_000_000L,0));zero(p)}
    @Test fun restartUsesNewReferenceNotPreviousSession(){val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);p.stop();p.start();p.sample(q(y=.8),2_000_000_000L,0);zero(p)}
    @Test fun invalidSensorValuesDoNotMutateOrAdvanceClock(){
        val bad=listOf(floatArrayOf(),floatArrayOf(0f,0f),floatArrayOf(0f,0f,0f,0f),floatArrayOf(Float.NaN,0f,0f,1f),floatArrayOf(0f,Float.POSITIVE_INFINITY,0f,1f),floatArrayOf(0f,0f,0f,Float.NaN),floatArrayOf(2f,0f,0f))
        for(v in bad){val p=fresh();assertFalse(p.sample(v,1_033_000_000L,0));zero(p);assertTrue(p.sample(q(y=.2),1_033_000_000L,0));assertTrue(p.yaw>0)}
    }
    @Test fun invalidTimestampOrDisplayRotationIsIgnored(){val p=fresh();assertFalse(p.sample(q(y=.2),-1,0));assertFalse(p.sample(q(y=.2),1_033_000_000L,4));assertFalse(p.sample(q(y=.2),1_033_000_000L,-1));zero(p);assertTrue(p.sample(q(y=.2),1_033_000_000L,0))}
    @Test fun inputRotationVectorIsNotModified(){val p=fresh();val pose=q(.1,.2,.3);val before=pose.copyOf();p.sample(pose,1_033_000_000L,0);assertArrayEquals(before,pose,0f)}
    @Test fun overlayDoesNotMutateManualCameraZoomOrPan(){val c=SpaceCamera();c.rotate(.1f,.1f);c.transform(2f,.5f,.5f,.6f,.6f);val manual=floatArrayOf(c.yaw,c.pitch,c.zoom,c.panX,c.panY);c.setParallax(.1f,-.1f);assertArrayEquals(manual,floatArrayOf(c.yaw,c.pitch,c.zoom,c.panX,c.panY),0f);assertEquals(.66f,c.displayYaw,1e-7f);assertEquals(-.34f,c.displayPitch,1e-7f)}
    @Test fun cameraOverlayIsBoundedEvenForUntrustedInput(){val c=SpaceCamera();c.setParallax(100f,-100f);assertEquals(.62f,c.displayYaw,1e-7f);assertEquals(-.32f,c.displayPitch,1e-7f)}
    @Test fun combinedCameraRespectsExistingProjectionLimits(){val c=SpaceCamera();c.rotate(100f,-100f);c.setParallax(.24f,.18f);assertEquals(.85f,c.displayYaw,0f);assertEquals(.55f,c.displayPitch,0f)}
    @Test fun disablingOverlayRestoresManualPoseExactly(){val c=SpaceCamera();c.rotate(.1f,.1f);val yaw=c.yaw;val pitch=c.pitch;c.setParallax(.2f,-.1f);c.setParallax(0f,0f);assertEquals(yaw,c.displayYaw,0f);assertEquals(pitch,c.displayPitch,0f)}
    @Test fun invalidOverlayDoesNotPartiallyMutateCamera(){val c=SpaceCamera();c.setParallax(.1f,.1f);val before=floatArrayOf(c.displayYaw,c.displayPitch);try{c.setParallax(.2f,Float.NaN);fail()}catch(_:IllegalArgumentException){};assertArrayEquals(before,floatArrayOf(c.displayYaw,c.displayPitch),0f)}
    @Test fun resetClearsManualAndSensorOverlay(){val c=SpaceCamera();c.setParallax(.2f,.1f);c.zoomBy(2f);c.reset();assertEquals(.38f,c.displayYaw,0f);assertEquals(-.14f,c.displayPitch,0f);assertEquals(1f,c.zoom,0f)}
    @Test fun doubleTapNotifiesReferenceResetExactlyOnce(){val c=SpaceCamera();val p=fresh();p.sample(q(y=.2),1_033_000_000L,0);c.setParallax(p.yaw,p.pitch);var resets=0;val t=SpaceTouch(c,8f,60f,300L){resets++;p.recenter()};for(at in listOf(1000L,1200L)){t.down(SpaceTouch.Finger(1,100f,100f),at);assertTrue(t.up(SpaceTouch.Finger(1,100f,100f),at+30))};assertEquals(1,resets);zero(p);assertEquals(.38f,c.displayYaw,0f);p.sample(q(y=.8),1_066_000_000L,0);zero(p)}
    @Test fun pinchAndPanRemainIndependentOfSensorOffset(){val c=SpaceCamera();c.setParallax(.1f,.05f);val t=SpaceTouch(c,8f,60f);t.down(SpaceTouch.Finger(1,100f,100f),0);t.rebase(listOf(SpaceTouch.Finger(1,100f,100f),SpaceTouch.Finger(2,200f,100f)));t.move(listOf(SpaceTouch.Finger(1,100f,130f),SpaceTouch.Finger(2,300f,130f)),400,300);assertEquals(2f,c.zoom,0f);assertEquals(.48f,c.displayYaw,1e-7f);assertEquals(-.09f,c.displayPitch,1e-7f);assertEquals(.25f,c.panX,1e-7f);assertEquals(.2666667f,c.panY,1e-7f)}
}
