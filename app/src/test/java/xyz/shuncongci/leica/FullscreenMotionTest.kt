package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** JVM geometry/intent regression only. Does not run Android views, sensors or permission dialogs. */
class FullscreenMotionTest {
    private val m=FullscreenMotion
    private fun safe()=ExportMotion.Rect(12f,28f,348f,620f)
    private fun origin()=ExportMotion.Rect(14f,100f,346f,450f)
    private fun bad(block:()->Unit){try{block();fail("invalid input accepted")}catch(_:IllegalArgumentException){}}
    private fun ui(composing:Boolean=true,busy:Boolean=false,closing:Boolean=false,cancelling:Boolean=false,count:Int=3)=m.composition(composing,busy,closing,cancelling,count)
    @Test fun photoFullscreenReservesHeaderWithoutChangingWindow(){assertEquals(ExportMotion.Rect(12f,76f,348f,620f),m.viewport(safe(),48f,0f))}
    @Test fun spaceToolsRemainBelowScene(){val r=m.viewport(safe(),48f,160f);assertEquals(460f,r.bottom,0f);assertEquals(76f,r.top,0f)}
    @Test fun excessiveToolHeightCannotConsumeScene(){val r=m.viewport(safe(),900f,900f);assertEquals(safe().height*.4f,r.height,.0001f)}
    @Test fun negativeHeaderRejected(){bad{m.viewport(safe(),-1f,0f)}}
    @Test fun negativeToolHeightRejected(){bad{m.viewport(safe(),0f,-1f)}}
    @Test fun nonfiniteChromeRejected(){bad{m.viewport(safe(),Float.NaN,0f)};bad{m.viewport(safe(),0f,Float.POSITIVE_INFINITY)}}
    @Test fun safeViewportAlwaysBounded(){for(w in listOf(1f,240f,360f,1024f))for(h in listOf(1f,180f,640f,1920f))for(top in listOf(0f,48f,140f))for(bottom in listOf(0f,120f,600f)){val s=ExportMotion.Rect(5f,8f,5f+w,8f+h);val r=m.viewport(s,top,bottom);assertTrue(r.left>=s.left && r.right<=s.right && r.top>=s.top && r.bottom<=s.bottom);assertTrue(r.height>0f)}}
    @Test fun openingStartsAtOriginalLiveViewport(){assertEquals(origin(),m.frame(origin(),safe(),0f))}
    @Test fun openingEndsAtSafeFullscreen(){assertEquals(safe(),m.frame(origin(),safe(),1f))}
    @Test fun closingIsExactReverseGeometry(){for(i in 0..64){val p=i/64f;assertEquals(m.frame(origin(),safe(),p),m.frame(safe(),origin(),1f-p))}}
    @Test fun midpointInterpolatesAllFourEdges(){assertEquals(ExportMotion.Rect(13f,64f,347f,535f),m.frame(origin(),safe(),.5f))}
    @Test fun interpolationNeverOvershootsEdges(){for(i in 0..100){val r=m.frame(origin(),safe(),i/100f);assertTrue(r.left in 12f..14f);assertTrue(r.top in 28f..100f);assertTrue(r.right in 346f..348f);assertTrue(r.bottom in 450f..620f)}}
    @Test fun invalidProgressRejectedRatherThanClamped(){for(p in listOf(-.01f,1.01f,Float.NaN,Float.POSITIVE_INFINITY))bad{m.frame(origin(),safe(),p)}}
    @Test fun islandContentBeginsBelowVisibleCard(){assertEquals(260,m.islandScrollTop(32,216,12))}
    @Test fun islandGrowthAdjustsOnlyInitialScrollGap(){assertEquals(56,m.islandScrollTop(32,272,12)-m.islandScrollTop(32,216,12))}
    @Test fun noInsetsStillHasIslandGap(){assertEquals(228,m.islandScrollTop(0,216,12))}
    @Test fun islandRejectsNegativeMetrics(){bad{m.islandScrollTop(-1,20,8)};bad{m.islandScrollTop(0,-1,8)};bad{m.islandScrollTop(0,20,-1)}}
    @Test fun islandOverflowCannotProduceNegativeScrollPadding(){bad{m.islandScrollTop(Int.MAX_VALUE,1,0)}}
    @Test fun parametersAreTallerAtDefaultFont(){assertEquals(576,m.panelHeight(640,1f))}
    @Test fun largeFontGetsMoreVerticalRoom(){assertEquals(602,m.panelHeight(640,2f))}
    @Test fun panelHeightHasAccessibleUpperBound(){for(h in listOf(0,1,100,320,640,1920))for(f in listOf(.85f,1f,1.5f,2f,5f))assertTrue(m.panelHeight(h,f) in 0..h)}
    @Test fun invalidPanelInputsRejected(){bad{m.panelHeight(-1,1f)};bad{m.panelHeight(640,0f)};bad{m.panelHeight(640,Float.NaN)}}
    @Test fun stoppingGyroPreservesVisiblePose(){val c=SpaceCamera();c.setGyroMode(true);c.rotate(.1f,.04f);c.setParallax(.1f,-.08f);val y=c.displayYaw;val p=c.displayPitch;c.releaseGyro();assertEquals(y,c.displayYaw,0f);assertEquals(p,c.displayPitch,0f);assertFalse(c.gyroMode)}
    @Test fun gyroReleasePreservesZoomAndPan(){val c=SpaceCamera();c.setGyroMode(true);c.transform(2f,.5f,.5f,.6f,.4f);val z=c.zoom;val x=c.panX;val y=c.panY;c.releaseGyro();assertEquals(z,c.zoom,0f);assertEquals(x,c.panX,0f);assertEquals(y,c.panY,0f)}
    @Test fun gyroReleaseIsIdempotent(){val c=SpaceCamera();c.setGyroMode(true);c.setParallax(.1f,.1f);c.releaseGyro();val y=c.displayYaw;val p=c.displayPitch;c.releaseGyro();assertEquals(y,c.displayYaw,0f);assertEquals(p,c.displayPitch,0f)}
    @Test fun ordinaryManualPoseDoesNotSnapOnRelease(){val c=SpaceCamera();c.rotate(-.2f,.12f);val y=c.displayYaw;val p=c.displayPitch;c.releaseGyro();assertEquals(y,c.displayYaw,0f);assertEquals(p,c.displayPitch,0f)}
    @Test fun explicitGyroToggleStillHasOriginalCenteringSemantics(){val c=SpaceCamera();c.setGyroMode(true);assertEquals(0f,c.displayYaw,0f);c.setGyroMode(false);assertEquals(SpaceCamera.DEFAULT_YAW,c.displayYaw,0f)}
    @Test fun confirmationCannotCommitWithoutClick(){assertFalse(FullscreenMotion.ChoiceCommit().commit(true))}
    @Test fun confirmationClickIsSingleShot(){val c=FullscreenMotion.ChoiceCommit();assertTrue(c.begin());assertFalse(c.begin())}
    @Test fun outsideOrBackIsDecline(){val c=FullscreenMotion.ChoiceCommit();assertTrue(c.cancel(true));assertFalse(c.commit(true))}
    @Test fun lifecycleDuringAffirmativeAnimationDeclines(){val c=FullscreenMotion.ChoiceCommit();c.begin();assertTrue(c.cancel(false));assertFalse(c.commit(true))}
    @Test fun affirmativeActionRequiresCompletedClose(){val c=FullscreenMotion.ChoiceCommit();c.begin();assertFalse(c.commit(false));assertTrue(c.commit(true))}
    @Test fun successfulCloseDismissDoesNotDeclineClickedAction(){val c=FullscreenMotion.ChoiceCommit();c.begin();assertFalse(c.cancel(true));assertTrue(c.commit(true));assertFalse(c.cancel(true))}
    @Test fun declineCannotRunTwice(){val c=FullscreenMotion.ChoiceCommit();assertTrue(c.cancel(false));assertFalse(c.cancel(false));assertFalse(c.begin())}
    @Test fun commitCannotRunTwice(){val c=FullscreenMotion.ChoiceCommit();c.begin();assertTrue(c.commit(true));assertFalse(c.commit(true))}
    @Test fun compositionUsesProposalRowInsteadOfPhotoViewTabs(){val p=ui();assertTrue(p.proposals);assertFalse(p.normalViews)}
    @Test fun compositionHidesNormalDevelopDock(){assertFalse(ui().normalActions)}
    @Test fun idleEditorRestoresNormalRows(){val p=ui(composing=false);assertFalse(p.proposals);assertTrue(p.normalViews);assertTrue(p.normalActions);assertFalse(p.apply);assertFalse(p.exit)}
    @Test fun analysedProposalsEnableApplyAndSelection(){val p=ui();assertTrue(p.apply && p.select && p.exit)}
    @Test fun analysisWithoutProposalsAllowsOnlyExit(){val p=ui(busy=true,count=0);assertFalse(p.apply);assertFalse(p.select);assertTrue(p.exit)}
    @Test fun failedAnalysisWithoutProposalsStillAllowsExit(){val p=ui(count=0);assertFalse(p.apply);assertFalse(p.select);assertTrue(p.exit)}
    @Test fun closingCompositionLocksActions(){val p=ui(closing=true);assertFalse(p.apply || p.exit || p.select)}
    @Test fun cancellingCompositionCannotRepeatExit(){val p=ui(busy=true,cancelling=true);assertFalse(p.apply || p.exit || p.select)}
    @Test fun busyCompositionCannotApplyOldProposal(){val p=ui(busy=true);assertFalse(p.apply);assertFalse(p.select)}
    @Test fun negativeProposalCountRejected(){bad{ui(count=-1)}}
    @Test fun recordAndModeShareWidthWithoutStacking(){for(w in listOf(0,1,120,240,320,480,960))for(d in listOf(1f,2f,3f))for(f in listOf(.85f,1f,2f,3f)){val mode=m.recordModeWidth(w,d,f);assertTrue(mode in 0..w);assertTrue(mode<=kotlin.math.round(w*.6f).toInt());assertTrue(w-mode>=0)}}
    @Test fun ordinaryRecordRowKeepsModeWidth(){assertEquals(132,m.recordModeWidth(332,1f,1f))}
    @Test fun largeFontModeLeavesRecordRoom(){val width=m.recordModeWidth(320,1f,2f);assertEquals(192,width);assertEquals(128,320-width)}
    @Test fun completedSummaryStillNamesRecord(){assertEquals("处理记录 · 已完成 3.2 秒",m.recordLabel("已完成 3.2 秒",240f,1f))}
    @Test fun narrowRecordKeepsEntryAndStatusOnSameRow(){assertEquals("处理记录 ›\n已完成",m.recordLabel("已完成",80f,1f))}
    @Test fun emptyRecordStillHasEntry(){assertEquals("处理记录 ›",m.recordLabel("",240f,1f))}
    @Test fun urgentRecordSummaryRemainsAvailableWhenRoomPermits(){assertTrue(m.recordLabel("取消，原片保留",240f,1f).contains("取消，原片保留"))}
    @Test fun recordLabelRespectsFontScaleWithoutHidingStatus(){assertEquals("处理记录 ›\n已完成",m.recordLabel("已完成",240f,2f))}
    @Test fun narrowRowNeverHidesCancellationOrFailure(){for(s in listOf("正在取消 · 等待当前步骤安全退出","失败 · 原片保留")){val label=m.recordLabel(s,80f,2f);assertTrue(label.startsWith("处理记录"));assertTrue(label.contains(s))}}
    @Test fun invalidRecordMetricsRejected(){bad{m.recordLabel("",-1f,1f)};bad{m.recordLabel("",1f,0f)};bad{m.recordModeWidth(-1,1f,1f)}}
    @Test fun guideExplainsOuterProposalsAndExitWithoutSave(){val s=WorkspaceGuide.topic("composition").steps;assertTrue(s.contains("方案横栏直接切换"));assertTrue(s.contains("应用编辑"));assertTrue(s.contains("退出构图"));assertTrue(s.contains("不自动保存"))}
    @Test fun guideExplainsTwoRecordAnchorsAndPersistentEntry(){val s=WorkspaceGuide.topic("monitor").steps;assertTrue(s.contains("同一行"));assertTrue(s.contains("处理完成后仍可查看"));assertTrue(s.contains("从原处"));assertTrue(s.contains("从右上"))}
}
