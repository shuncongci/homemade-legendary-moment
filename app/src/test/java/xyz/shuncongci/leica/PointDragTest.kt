package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Point

class PointDragTest {
    private val a=Point(.5f,.5f)
    private fun begin(d:PointDrag,x:Float=110f,y:Float=70f,w:Float=200f,h:Float=100f,hs:List<PointDrag.Handle> = listOf(PointDrag.Handle(a)))=d.begin(hs,x,y,10f,20f,w,h,24f,8f)
    @Test fun enlargedHitTargetAcceptsOutsideVisibleMarker(){val d=PointDrag();assertTrue(begin(d,130f,70f));assertEquals(a,d.preview)}
    @Test fun blankAreaIsLeftForPan(){assertFalse(begin(PointDrag(),20f,20f))}
    @Test fun focusMovesBySourceDeltaNotGrabCenter(){val d=PointDrag();begin(d,130f);d.move(150f,80f);val c=d.finish()!!;assertEquals(-1,c.handle.subjectIndex);assertEquals(.6f,c.point.x,.00001f);assertEquals(.6f,c.point.y,.00001f)}
    @Test fun arbitraryNumberedSubjectCanMove(){val d=PointDrag();begin(d,hs=listOf(PointDrag.Handle(a,7)));d.move(150f,70f);assertEquals(7,d.finish()!!.handle.subjectIndex)}
    @Test fun movementOnlyPreviewUntilRelease(){val d=PointDrag();begin(d);d.move(150f,70f);assertEquals(a,d.handle!!.point);assertEquals(.7f,d.preview!!.x,.00001f);assertEquals(a,d.display(a,0))}
    @Test fun tapAndFingerJitterDoNotCommit(){val d=PointDrag();begin(d);d.move(115f,73f);assertEquals(a,d.preview);assertNull(d.finish())}
    @Test fun cancelNeverCommits(){val d=PointDrag();begin(d);d.move(150f,70f);d.cancel();assertFalse(d.active);assertNull(d.finish())}
    @Test fun multiplePointerAbortCanRestartCleanly(){val d=PointDrag();begin(d);d.move(150f,70f);d.cancel();assertTrue(begin(d));assertEquals(a,d.preview)}
    @Test fun pointCannotLeaveImage(){val d=PointDrag();begin(d);d.move(-1000f,2000f);assertEquals(Point(0f,1f),d.finish()!!.point)}
    @Test fun zoomedImageDeltaIsSmaller(){val d=PointDrag();begin(d,210f,120f,400f,200f);d.move(230f,120f);assertEquals(.55f,d.finish()!!.point.x,.00001f)}
    @Test fun nearestMarkerWins(){val d=PointDrag();begin(d,120f,70f,hs=listOf(PointDrag.Handle(a),PointDrag.Handle(Point(.56f,.5f),3)));assertEquals(3,d.handle!!.subjectIndex)}
    @Test fun overlappingMarkersUseExplicitPickingPriority(){val d=PointDrag();begin(d,hs=listOf(PointDrag.Handle(a,2),PointDrag.Handle(a)));assertEquals(2,d.handle!!.subjectIndex);begin(d,hs=listOf(PointDrag.Handle(a),PointDrag.Handle(a,2)));assertEquals(-1,d.handle!!.subjectIndex)}
    @Test fun releaseOnlyCommitsOnce(){val d=PointDrag();begin(d);d.move(150f,70f);assertNotNull(d.finish());assertNull(d.finish());assertFalse(d.active)}
    @Test fun returningToStartDoesNotInvalidateCache(){val d=PointDrag();begin(d);d.move(150f,70f);d.move(110f,70f);assertNull(d.finish())}
}
