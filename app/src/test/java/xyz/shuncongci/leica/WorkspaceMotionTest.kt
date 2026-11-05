package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Pure display geometry/intent tests. Not Android layout, gesture dispatch or frame-time validation. */
class WorkspaceMotionTest {
    private val m=WorkspaceMotion
    private fun bad(action:()->Unit){try{action();fail("invalid geometry accepted")}catch(_:IllegalArgumentException){}}
    private fun layout(w:Int=360,h:Int=640,d:Float=1f,f:Float=1f)=m.panel(w,h,d,f)
    private fun safe()=ExportMotion.Rect(10f,20f,350f,620f)
    private fun entry()=ExportMotion.Rect(280f,560f,328f,608f)
    @Test fun portraitPhotoKeepsWholeContainer(){assertEquals(EditorLayoutPolicy.Rect(0,0,360,640),layout().preview)}
    @Test fun portraitPanelUsesEnoughHeight(){assertEquals(576,layout().controls.h);assertFalse(layout().sideBySide)}
    @Test fun portraitPanelIsBottomAligned(){val p=layout();assertEquals(640,p.controls.y+p.controls.h);assertEquals(360,p.controls.w)}
    @Test fun portraitLargeFontGetsMoreScrollSpace(){assertTrue(layout(f=2f).controls.h>layout().controls.h)}
    @Test fun landscapePhotoAlsoKeepsWholeContainer(){assertEquals(EditorLayoutPolicy.Rect(0,0,960,480),layout(960,480).preview)}
    @Test fun wideWindowUsesRightHalf(){val p=layout(960,480);assertTrue(p.sideBySide);assertEquals(EditorLayoutPolicy.Rect(480,0,480,480),p.controls)}
    @Test fun rightPanelExpandsForLargeType(){assertTrue(layout(960,480,f=2f).controls.w>layout(960,480).controls.w)}
    @Test fun rightPanelEndsAtCurrentWindowEdge(){val p=layout(960,480,f=2f);assertEquals(960,p.controls.x+p.controls.w)}
    @Test fun densePortraitHasEquivalentGeometry(){val a=layout();val b=layout(1080,1920,3f);assertEquals(a.preview.w*3,b.preview.w);assertTrue(kotlin.math.abs(a.controls.h*3-b.controls.h)<=1)}
    @Test fun narrowLandscapeStillUsesBottomPanel(){assertFalse(layout(560,280).sideBySide)}
    @Test fun tallTabletDoesNotUseSidePanel(){assertFalse(layout(800,1200).sideBySide)}
    @Test fun arbitraryWindowsKeepPanelInside(){for(w in listOf(0,1,280,360,600,1280,2560))for(h in listOf(0,1,240,640,1200))for(f in listOf(.85f,1f,1.5f,3f)){val p=layout(w,h,f=f);assertEquals(w,p.preview.w);assertEquals(h,p.preview.h);assertTrue(p.controls.x>=0&&p.controls.y>=0);assertTrue(p.controls.x+p.controls.w<=w);assertTrue(p.controls.y+p.controls.h<=h)}}
    @Test fun invalidPanelExtentIsRejected(){bad{layout(-1)};bad{layout(h=-1)}}
    @Test fun invalidPanelDensityIsRejected(){for(d in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))bad{layout(d=d)}}
    @Test fun invalidPanelFontIsRejected(){for(f in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))bad{layout(f=f)}}
    @Test fun panelPlacementAboveExportIsBounded(){val r=m.place(safe(),entry(),300f,400f,false);assertEquals(152f,r.top,0f);assertEquals(552f,r.bottom,0f);assertTrue(r.left>=10&&r.right<=350)}
    @Test fun panelPlacementBelowTopEntryIsBounded(){val r=m.place(safe(),ExportMotion.Rect(16f,24f,130f,72f),280f,400f,true);assertEquals(80f,r.top,0f);assertEquals(16f,r.left,0f)}
    @Test fun oversizedPanelClampsToCurrentSafeWindow(){assertEquals(safe(),m.place(safe(),entry(),900f,1500f,false))}
    @Test fun rightEntryKeepsRightEdgeAligned(){val r=m.place(safe(),entry(),300f,300f,false);assertEquals(entry().right,r.right,0f)}
    @Test fun leftEntryKeepsLeftEdgeAligned(){val a=ExportMotion.Rect(12f,24f,100f,72f);assertEquals(12f,m.place(safe(),a,200f,300f,true).left,0f)}
    @Test fun offscreenAnchorStillClampsInsideWindow(){val r=m.place(safe(),ExportMotion.Rect(-40f,-80f,8f,-32f),250f,350f,false);assertEquals(10f,r.left,0f);assertEquals(20f,r.top,0f)}
    @Test fun placementRejectsZeroSize(){bad{m.place(safe(),entry(),0f,100f,true)};bad{m.place(safe(),entry(),100f,0f,true)}}
    @Test fun placementRejectsNonfiniteSize(){bad{m.place(safe(),entry(),Float.NaN,100f,true)};bad{m.place(safe(),entry(),100f,Float.POSITIVE_INFINITY,true)}}
    @Test fun contentStartsInvisibleAndEndsOpaque(){assertEquals(0f,m.content(0f),0f);assertEquals(1f,m.content(1f),0f)}
    @Test fun contentAppearsAfterShapeBegins(){assertEquals(0f,m.content(.1f),0f);assertTrue(m.content(.5f)>0)}
    @Test fun contentIsMonotonicAndBounded(){var last=0f;for(i in 0..100){val p=m.content(i/100f);assertTrue(p in 0f..1f);assertTrue(p>=last);last=p}}
    @Test fun interruptedOpeningUsesRemainingDuration(){assertEquals(340L,m.duration(0f,1f));assertEquals(170L,m.duration(.5f,1f))}
    @Test fun interruptedClosingUsesRemainingDuration(){assertEquals(280L,m.duration(1f,0f));assertEquals(140L,m.duration(.5f,0f))}
    @Test fun identicalEndpointIsImmediateAndFinite(){assertEquals(1L,m.duration(1f,1f))}
    @Test fun durationRejectsInvalidFractions(){bad{m.duration(-.1f,1f)};bad{m.duration(0f,1.1f)};bad{m.duration(Float.NaN,0f)}}
    @Test fun tapLeftSelectsSdr(){assertEquals(HdrDevelopMode.SDR,m.modeForGesture(20f,20f,132f,false))}
    @Test fun tapRightSelectsHdr(){assertEquals(HdrDevelopMode.HDR,m.modeForGesture(100f,100f,132f,false))}
    @Test fun tapBoundarySelectsHdr(){assertEquals(HdrDevelopMode.HDR,m.modeForGesture(66f,66f,132f,false))}
    @Test fun swipeRightSelectsHdrEvenWithinLeftSegment(){assertEquals(HdrDevelopMode.HDR,m.modeForGesture(5f,30f,132f,true))}
    @Test fun swipeLeftSelectsSdrEvenWithinRightSegment(){assertEquals(HdrDevelopMode.SDR,m.modeForGesture(125f,100f,132f,true))}
    @Test fun gestureNeverReturnsAuto(){for(a in listOf(-40f,0f,65f,140f))for(b in listOf(-40f,0f,65f,140f))for(s in listOf(false,true))assertNotEquals(HdrDevelopMode.AUTO,m.modeForGesture(a,b,132f,s))}
    @Test fun gestureRejectsMissingWidth(){bad{m.modeForGesture(0f,0f,0f,false)};bad{m.modeForGesture(0f,0f,-1f,true)}}
    @Test fun gestureRejectsNonfinitePositions(){bad{m.modeForGesture(Float.NaN,2f,132f,true)};bad{m.modeForGesture(2f,Float.POSITIVE_INFINITY,132f,false)}}
    @Test fun hdrChoiceDoesNotStartProcessing(){val f=PhotoFlow();f.photoLoaded();val mode=m.modeForGesture(10f,80f,132f,true);assertEquals(HdrDevelopMode.HDR,EditorWorkspacePolicy.chooseMode(f,HdrDevelopMode.SDR,mode,true));assertFalse(f.busy);assertEquals(PhotoFlow.Operation.IDLE,f.operation)}
    @Test fun hdrChoiceKeepsExistingCompleteResult(){val f=PhotoFlow();f.photoLoaded();f.generated();EditorWorkspacePolicy.chooseMode(f,HdrDevelopMode.SDR,HdrDevelopMode.HDR,true);assertTrue(f.hasResult);assertTrue(f.resultStale);assertFalse(f.busy)}
    @Test fun modeWidthRespectsCurrentWindow(){assertEquals(132,m.modeWidth(332,1f,1f));assertEquals(240,m.modeWidth(240,1f,3f))}
    @Test fun modeWidthUsesDensityAndAccessibleType(){assertEquals(396,m.modeWidth(1000,3f,1f));assertEquals(264,m.modeWidth(1000,1f,2f))}
    @Test fun modeWidthCannotOverflowNarrowOrEmptyRows(){for(w in listOf(0,1,60,100,240,332,960))for(f in listOf(.85f,1f,1.4f,2f,3f))assertTrue(m.modeWidth(w,3f,f) in 0..w)}
    @Test fun modeWidthRejectsInvalidMetrics(){bad{m.modeWidth(-1,1f,1f)};bad{m.modeWidth(320,Float.NaN,1f)};bad{m.modeWidth(320,1f,0f)}}
    @Test fun insetAdjustedStatusStacksBeforeSwitchCanCrowdIt(){assertTrue(UiPolishPolicy.stackInfo(332,1f));assertTrue(UiPolishPolicy.stackInfo(480,2f))}
    @Test fun tinyPanelStillHasIris(){assertTrue(m.irisRadius(180f,102f,1f)>4f)}
    @Test fun irisRemainsBoundedByHeight(){assertTrue(m.irisRadius(360f,30f,1f)<=10.2f)}
    @Test fun irisRemainsBoundedByWidth(){assertTrue(m.irisRadius(100f,102f,1f)<=19f)}
    @Test fun irisHasMaximumRadius(){assertEquals(38f,m.irisRadius(1200f,800f,1f),0f)}
    @Test fun irisScalesWithDensity(){assertEquals(3*m.irisRadius(360f,102f,1f),m.irisRadius(1080f,306f,3f),.001f)}
    @Test fun emptyIrisExtentDoesNotInventSpace(){assertEquals(0f,m.irisRadius(0f,100f,1f),0f);assertEquals(0f,m.irisRadius(100f,0f,1f),0f)}
    @Test fun irisRejectsInvalidGeometry(){bad{m.irisRadius(-1f,100f,1f)};bad{m.irisRadius(100f,Float.NaN,1f)};bad{m.irisRadius(100f,100f,0f)}}
    @Test fun onlyFivePublicViewersRemain(){assertEquals(listOf("ORIGINAL","THERMAL","RELIEF","RESULT","COMPARE"),m.viewers);assertEquals(m.viewers.size,m.viewers.toSet().size)}
    @Test fun depthHasSinglePublicEntry(){assertFalse("GRAY" in m.viewers);assertEquals(1,m.viewers.count{WorkspaceIcons.viewer(it)=="DEPTH"})}
    @Test fun allPublicViewersHaveUniqueSemanticIcons(){assertEquals(5,m.viewers.map{WorkspaceIcons.viewer(it)}.toSet().size)}
    @Test fun exportArrowIsOutwardAndSeparateFromDevelop(){assertTrue(WorkspaceIcons.spec("EXPORT").outline.contains("M12 16 V3 M7 8 L12 3 L17 8"));assertNotEquals(WorkspaceIcons.spec("EXPORT"),WorkspaceIcons.spec("DEVELOP"))}
}
