package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.util.Random

class SpaceGyroCameraTest {
    @Test fun enableIsFrontCentered(){val c=SpaceCamera();c.rotate(.2f,.1f);c.transform(2f,.5f,.5f,.7f,.8f);c.setGyroMode(true);assertTrue(c.gyroMode);assertEquals(0f,c.displayYaw,0f);assertEquals(0f,c.displayPitch,0f);assertEquals(0f,c.panX,0f);assertEquals(0f,c.panY,0f);assertEquals(2f,c.zoom,0f)}
    @Test fun disableRestoresMicroSideView(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.3f,-.2f);c.setParallax(.2f,-.1f);c.setGyroMode(false);assertEquals(.38f,c.displayYaw,0f);assertEquals(-.14f,c.displayPitch,0f)}
    @Test fun resetInGyroModeIsFrontNotSide(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.3f,.2f);c.zoomBy(3f);c.reset();assertTrue(c.gyroMode);assertEquals(0f,c.displayYaw,0f);assertEquals(0f,c.displayPitch,0f);assertEquals(1f,c.zoom,0f)}
    @Test fun duplicateStateDoesNotDestroyManualRotation(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.1f,.1f);c.setGyroMode(true);assertEquals(.18f,c.yaw,1e-6f);assertEquals(-.1f,c.pitch,0f)}
    @Test fun bothYawDirectionsKeepFullSensorGainAfterManualDrag(){for(dx in listOf(-100f,-.2f,0f,.2f,100f)){val c=SpaceCamera();c.setGyroMode(true);c.rotate(dx,0f);c.setParallax(-.24f,0f);val left=c.displayYaw;c.setParallax(.24f,0f);val right=c.displayYaw;assertEquals(.48f,right-left,1e-6f);assertEquals(c.yaw,(left+right)*.5f,1e-6f)}}
    @Test fun bothPitchDirectionsKeepFullSensorGainAfterManualDrag(){for(dy in listOf(-100f,-.2f,0f,.2f,100f)){val c=SpaceCamera();c.setGyroMode(true);c.rotate(0f,dy);c.setParallax(0f,-.18f);val a=c.displayPitch;c.setParallax(0f,.18f);assertEquals(.36f,c.displayPitch-a,1e-6f)}}
    @Test fun incrementalGainIsIndependentOfManualAngle(){for(dx in listOf(-100f,0f,100f)){val c=SpaceCamera();c.setGyroMode(true);c.rotate(dx,0f);c.setParallax(.10f,0f);val a=c.displayYaw;c.setParallax(.11f,0f);assertEquals(.01f,c.displayYaw-a,1e-6f)}}
    @Test fun sensorNeverAccumulatesIntoManualPose(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.2f,-.1f);val y=c.yaw;val p=c.pitch;repeat(1000){c.setParallax(if(it%2==0).24f else -.24f,.18f)};assertEquals(y,c.yaw,0f);assertEquals(p,c.pitch,0f)}
    @Test fun doubleTapPreservesModeButCenters(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.2f,.2f);val t=SpaceTouch(c,8f,60f);for(at in listOf(1000L,1200L)){t.down(SpaceTouch.Finger(1,100f,100f),at);t.up(SpaceTouch.Finger(1,100f,100f),at+20)};assertTrue(c.gyroMode);assertEquals(0f,c.displayYaw,0f)}
    @Test fun closingModeClearsPanButKeepsManualZoom(){val c=SpaceCamera();c.setGyroMode(true);c.transform(2f,.5f,.5f,.7f,.7f);c.setGyroMode(false);assertEquals(0f,c.panX,0f);assertEquals(2f,c.zoom,0f)}
    @Test fun randomGyroManualCombinationStaysWithinProjectionBounds(){val c=SpaceCamera();val r=Random(230);c.setGyroMode(true);repeat(5000){c.rotate(r.nextFloat()-.5f,r.nextFloat()-.5f);c.setParallax((r.nextFloat()-.5f)*.48f,(r.nextFloat()-.5f)*.36f);assertTrue(abs(c.displayYaw)<=.850001f);assertTrue(abs(c.displayPitch)<=.550001f)}}
    @Test fun modeOffRetainsExistingManualRange(){val c=SpaceCamera();c.rotate(100f,-100f);assertEquals(.85f,c.yaw,0f);assertEquals(.55f,c.pitch,0f)}
}

class SpaceMeshTest {
    private fun mesh(layers:Int=1)=SpaceMesh(960,720,48,36,layers)
    @Test fun fullDensityAndDisconnectedQuadsPreserved(){val m=mesh(2);assertEquals(3456,m.count);assertEquals(27648,m.vertices.size);assertEquals(20736,m.indices.size)}
    @Test fun unchangedSceneReusesProjectionAndOrder(){val m=mesh();val z=FloatArray(m.count);assertTrue(m.project(.38f,-.14f,600f,500f,400f,z));assertFalse(m.project(.38f,-.14f,600f,500f,400f,z));assertEquals(1,m.projections)}
    @Test fun changedDepthInvalidatesCache(){val m=mesh();val z=FloatArray(m.count);m.project(0f,0f,600f,500f,400f,z);z[50]=.2f;assertTrue(m.project(0f,0f,600f,500f,400f,z))}
    @Test fun allCameraParametersInvalidateCache(){val m=mesh();val z=FloatArray(m.count);m.project(0f,0f,600f,500f,400f,z);assertTrue(m.project(.1f,0f,600f,500f,400f,z));assertTrue(m.project(.1f,.1f,600f,500f,400f,z));assertTrue(m.project(.1f,.1f,601f,500f,400f,z));assertTrue(m.project(.1f,.1f,601f,501f,400f,z));assertTrue(m.project(.1f,.1f,601f,501f,401f,z))}
    @Test fun sortingMatchesIndependentLegacyComparator(){val m=mesh(2);val r=Random(230);val z=FloatArray(m.count){r.nextFloat()-.5f};m.project(.38f,-.14f,600f,500f,400f,z);val expected=(0 until m.count).sortedWith(compareBy<Int>{m.depthKey(it)});assertArrayEquals(expected.toIntArray(),m.order)}
    @Test fun vertexPositionsMatchLegacyProjection(){val m=mesh(2);val r=Random(230);val z=FloatArray(m.count){r.nextFloat()-.5f};m.project(.6f,-.3f,1300f,500f,400f,z);val p=FloatArray(3)
        for(slot in 0 until m.count){val t=m.order[slot];val x=t%1728%48;val y=t%1728/48;for(k in 0..3){val sx=(x+if(k==1 || k==2)1 else 0)/48f;val sy=(y+if(k>=2)1 else 0)/36f;val cx=(x+.5f)/48;val cy=(y+.5f)/36
            DepthVisualMath.project((sx-cx)*.993f+cx-.5f,((sy-cy)*.993f+cy-.5f)*.75f,z[t],.6f,-.3f,p)
            assertEquals(500f+p[0]*1300f,m.vertices[slot*8+k*2],.0002f);assertEquals(400f+p[1]*1300f,m.vertices[slot*8+k*2+1],.0002f)
        }}
    }
    @Test fun atlasCoordinatesKeepBothLayersDistinct(){val m=mesh(2);m.project(0f,0f,600f,500f,400f,FloatArray(m.count));for(s in 0 until m.count){val t=m.order[s];val y=m.tex[s*8+1];assertTrue(if(t>=1728)y>=720f else y<720f)}}
    @Test fun indicesReferenceOnlyOwnQuad(){val m=mesh(2);for(t in 0 until m.count)for(k in 0..5)assertTrue(m.indices[t*6+k].toInt() in t*4..t*4+3)}
    @Test fun onlyBackgroundOutlineIsBatched(){val m=mesh(2);m.project(0f,0f,600f,500f,400f,FloatArray(m.count));assertEquals(1728*16,m.lineCount)}
    @Test fun maximumSquareStaysWithinShortIndexRange(){val m=SpaceMesh(960,960,48,48,2);assertEquals(4608,m.count);assertTrue(m.indices.all{it>=0})}
    @Test fun onePixelImageWorks(){val m=SpaceMesh(1,1,1,1,1);m.project(0f,0f,100f,50f,50f,floatArrayOf(0f));assertTrue(m.vertices.all{it.isFinite()});assertEquals(16,m.lineCount)}
    @Test fun zoomAndPanAreUniformTransforms(){val a=mesh();val z=FloatArray(a.count);a.project(.2f,.1f,100f,0f,0f,z);val old=a.vertices.copyOf();a.project(.2f,.1f,200f,30f,20f,z);for(i in old.indices)assertEquals(old[i]*2+if(i%2==0)30 else 20,a.vertices[i],.0001f)}
    @Test fun noMutationOfInputDepth(){val m=mesh();val z=FloatArray(m.count){it*.00001f};val before=z.copyOf();m.project(.1f,.2f,100f,0f,0f,z);assertArrayEquals(before,z,0f)}
    @Test fun repeatedAnimatedDepthPreservesFiniteSortedOutput(){val m=mesh(2);val z=FloatArray(m.count);repeat(20){f->for(i in z.indices)z[i]=sin(i*.7+f*.1).toFloat()*.45f;m.project(f*.02f,-.2f,2000f,500f,500f,z);assertTrue(m.vertices.all{it.isFinite()});for(i in 1 until m.count)assertTrue(m.depthKey(m.order[i-1])<=m.depthKey(m.order[i]))}}
    @Test fun invalidDepthRejected(){val m=mesh();try{m.project(0f,0f,1f,0f,0f,FloatArray(m.count){Float.NaN});fail()}catch(_:IllegalArgumentException){}}
}
