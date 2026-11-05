package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Pure UI decisions and vector vocabulary, not Android rendering or phone interaction tests. */
class UiPolishPolicyTest {
    private val p=UiPolishPolicy
    private val cancellable=setOf(PhotoFlow.Operation.GENERATE,PhotoFlow.Operation.FOCUS_PREVIEW,PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.COMPOSITION,PhotoFlow.Operation.SAVE)
    private fun illegal(action:()->Unit){try{action();fail("invalid UI input accepted")}catch(_:IllegalArgumentException){}}
    @Test fun closedParametersStayClosed(){assertFalse(p.parametersVisible(false,false,false))}
    @Test fun explicitExpansionOpensPanel(){assertTrue(p.parametersVisible(true,false,false))}
    @Test fun compositionOwnsPanelRegardlessOfManualState(){for(v in listOf(false,true))assertTrue(p.parametersVisible(v,true,false))}
    @Test fun fullscreenHidesAllPanels(){for(v in listOf(false,true))for(c in listOf(false,true))assertFalse(p.parametersVisible(v,c,true))}
    @Test fun parameterToggleUsesOnePersistedState(){val f=PhotoFlow();f.showParameters(false);f.showParameters(!f.parametersExpanded);assertTrue(p.parametersVisible(f.parametersExpanded,false,false));f.showParameters(!f.parametersExpanded);assertFalse(p.parametersVisible(f.parametersExpanded,false,false))}
    @Test fun loadedPhotoCanExposeParametersWithoutSecondLatch(){val f=PhotoFlow();f.showParameters(false);f.photoLoaded();assertTrue(p.parametersVisible(f.parametersExpanded,false,false))}
    @Test fun successfulGenerationClosesParameters(){val f=PhotoFlow();f.photoLoaded();f.generated();assertFalse(p.parametersVisible(f.parametersExpanded,false,false))}
    @Test fun restoredClosedStateStaysClosed(){val f=PhotoFlow();f.showParameters(false);val restored=PhotoFlow();restored.restore(f.snapshot());assertFalse(p.parametersVisible(restored.parametersExpanded,false,false))}
    @Test fun ordinaryPortraitKeepsViewLabels(){assertFalse(p.tabsIconOnly(800,1f))}
    @Test fun shortWindowUsesAccessibleViewIcons(){assertTrue(p.tabsIconOnly(360,1f))}
    @Test fun veryLargeFontUsesViewIconsEvenOnTallWindow(){assertTrue(p.tabsIconOnly(1000,1.8f));assertFalse(p.tabsIconOnly(1000,1.79f))}
    @Test fun invalidViewGeometryIsRejected(){illegal{p.tabsIconOnly(-1,1f)};illegal{p.tabsIconOnly(800,Float.NaN)}}
    @Test fun narrowInformationLineStacks(){assertTrue(p.stackInfo(320,1f));assertFalse(p.stackInfo(360,1f))}
    @Test fun largeFontInformationLineStacks(){assertTrue(p.stackInfo(600,1.4f));assertFalse(p.stackInfo(600,1.39f))}
    @Test fun invalidInformationGeometryIsRejected(){illegal{p.stackInfo(-1,1f)};illegal{p.stackInfo(400,0f)};illegal{p.stackInfo(400,Float.POSITIVE_INFINITY)}}
    @Test fun twoOrdinaryActionsFitAsRow(){assertFalse(p.stackActions(280,1f,2))}
    @Test fun narrowActionsStackInsteadOfClipping(){assertTrue(p.stackActions(207,1f,2));assertFalse(p.stackActions(208,1f,2))}
    @Test fun accessibilityFontCanStackActions(){assertTrue(p.stackActions(270,2.5f,2))}
    @Test fun hiddenOrSingleActionDoesNotNeedAStack(){for(n in 0..1)assertFalse(p.stackActions(0,2.5f,n))}
    @Test fun invalidActionGeometryIsRejected(){illegal{p.stackActions(-1,1f,2)};illegal{p.stackActions(320,Float.NaN,2)};illegal{p.stackActions(320,1f,-1)}}
    @Test fun singleCapsuleKeepsCancelCaptionOnNarrowNormalFont(){val d=p.dock(320,1f,false);assertFalse(d.iconOnly);assertFalse(d.labelOnly)}
    @Test fun singleCapsuleKeepsCaptionAtModerateAccessibilityFont(){val d=p.dock(320,1.5f,false);assertFalse(d.iconOnly)}
    @Test fun extremeFontCapsuleUsesAccessibleIconFallback(){val d=p.dock(320,2.5f,false);assertTrue(d.iconOnly);assertFalse(d.labelOnly)}
    @Test fun capsuleKeepsOriginalMinimumHeightsAndMargins(){for(w in listOf(0,280,320,360,480,900))for(f in listOf(.85f,1f,1.5f,2.5f,4f))for(c in listOf(false,true)){val d=p.dock(w,f,c);val old=ReferenceWorkspacePolicy.dock(w,f,c);assertEquals(old.buttonHeight,d.buttonHeight);assertEquals(old.margin,d.margin);assertTrue(d.buttonHeight>=48);assertFalse(d.labelOnly&&d.iconOnly)}}
    @Test fun invalidCapsuleGeometryIsRejected(){illegal{p.dock(-1,1f,false)};illegal{p.dock(400,0f,false)}}
    @Test fun cancelOnlyExistsForSupportedOperations(){for(op in PhotoFlow.Operation.entries)assertEquals(op.name,op in cancellable,p.cancelUi(op,false,true).visible)}
    @Test fun absentCancellationTokenHidesAndDisablesAction(){for(op in PhotoFlow.Operation.entries)for(c in listOf(false,true)){val ui=p.cancelUi(op,c,false);assertFalse(ui.visible);assertFalse(ui.enabled)}}
    @Test fun activeCancellationHasAnExplicitLabel(){for(op in cancellable){val ui=p.cancelUi(op,false,true);assertTrue(ui.visible);assertTrue(ui.enabled);assertEquals("取消处理",ui.label)}}
    @Test fun cancellingRemainsVisibleButCannotBeRepeated(){for(op in cancellable){val ui=p.cancelUi(op,true,true);assertTrue(ui.visible);assertFalse(ui.enabled);assertEquals("正在取消",ui.label)}}
    @Test fun idleAndImportNeverPretendToBeCancellable(){for(op in listOf(PhotoFlow.Operation.IDLE,PhotoFlow.Operation.PREVIEW)){assertFalse(p.cancelUi(op,true,true).visible);assertFalse(p.cancelUi(op,true,true).enabled)}}
    @Test fun cancellationStateCannotBecomeEnabledByResultPresence(){val f=PhotoFlow();f.photoLoaded();f.generated();f.begin(PhotoFlow.Operation.GENERATE);f.cancel();assertFalse(p.cancelUi(f.operation,f.cancelling,true).enabled);assertTrue(f.hasResult);f.finish();assertFalse(p.cancelUi(f.operation,f.cancelling,false).visible);assertTrue(f.hasResult)}
    @Test fun busyLabelsWinOverCompositionAndOldResult(){assertEquals("显影中",p.runLabel(PhotoFlow.Operation.GENERATE,true,true));assertEquals("导出中",p.runLabel(PhotoFlow.Operation.SAVE,true,true));assertEquals("读取中",p.runLabel(PhotoFlow.Operation.PREVIEW,true,true))}
    @Test fun otherBusyOperationsHaveHonestProgressLabel(){for(op in listOf(PhotoFlow.Operation.FOCUS_PREVIEW,PhotoFlow.Operation.DEPTH,PhotoFlow.Operation.COMPOSITION))assertEquals("处理中",p.runLabel(op,true,true))}
    @Test fun idleLabelsDistinguishCompositionResultAndFirstRun(){assertEquals("选择构图",p.runLabel(PhotoFlow.Operation.IDLE,true,true));assertEquals("重新显影",p.runLabel(PhotoFlow.Operation.IDLE,false,true));assertEquals("显影",p.runLabel(PhotoFlow.Operation.IDLE,false,false))}
    @Test fun listenerStoppedOffersStart(){val a=p.listenerAction(false,false,false,false);assertEquals("开始监听",a.label);assertTrue(a.enabled);assertTrue(a.highlighted)}
    @Test fun listenerStartKeepsAllExistingInterlocks(){for(b in listOf(false,true))for(e in listOf(false,true))assertEquals(ExternalWorkspacePolicy.canStart(false,b,e),p.listenerAction(false,false,b,e).enabled)}
    @Test fun runningListenerCanBeStoppedEvenIfBrowsingOrEditorBusy(){for(b in listOf(false,true))for(e in listOf(false,true)){val a=p.listenerAction(true,false,b,e);assertEquals("停止监听",a.label);assertTrue(a.enabled);assertFalse(a.highlighted)}}
    @Test fun stoppingListenerHasNoRepeatedStop(){val a=p.listenerAction(true,true,false,false);assertEquals("正在停止",a.label);assertFalse(a.enabled);assertFalse(a.highlighted)}
    @Test fun monitorStateDoesNotConfuseLivePanelWithNotification(){assertTrue(p.monitorState(true).contains("已开启"));assertTrue(p.monitorState(false).contains("未开启"));for(v in listOf(false,true))assertTrue(p.monitorState(v).contains("点击查看实时监测"))}
    @Test fun originalAndResultHaveDistinctPhotographicIcons(){assertEquals("IMAGE",WorkspaceIcons.viewer("ORIGINAL"));assertEquals("RESULT",WorkspaceIcons.viewer("RESULT"));assertNotEquals(WorkspaceIcons.spec("IMAGE"),WorkspaceIcons.spec("RESULT"))}
    @Test fun sixViewIconsAreOneToOne(){val views=listOf("ORIGINAL","GRAY","THERMAL","RELIEF","RESULT","COMPARE");assertEquals(listOf("IMAGE","GRAY","DEPTH","SPACE","RESULT","COMPARE"),views.map{WorkspaceIcons.viewer(it)});assertEquals(6,views.map{WorkspaceIcons.viewer(it)}.toSet().size)}
    @Test fun resultIsNotTheOutputOrExportIcon(){for(k in listOf("OUTPUT","EXPORT","FILE"))assertNotEquals(WorkspaceIcons.spec("RESULT"),WorkspaceIcons.spec(k));assertTrue(WorkspaceIcons.spec("RESULT").outline.contains("M13 18 L16 21 L22 14"))}
    @Test fun developIsNotADownloadArrow(){assertTrue(WorkspaceIcons.spec("DEVELOP").outline.startsWith("M5 19 L15 9"));assertNotEquals(WorkspaceIcons.spec("DEVELOP"),WorkspaceIcons.spec("EXPORT"))}
    @Test fun exportArrowPointsOutwards(){assertTrue(WorkspaceIcons.spec("EXPORT").outline.contains("M12 16 V3 M7 8 L12 3 L17 8"))}
    @Test fun externalPlugAndSystemCameraAreDistinct(){assertNotEquals(WorkspaceIcons.spec("EXTERNAL"),WorkspaceIcons.spec("CAMERA"));assertTrue(WorkspaceIcons.spec("EXTERNAL").outline.startsWith("M8 3 V8 M16 3 V8"))}
    @Test fun backAndDisclosureAreNotReversed(){assertTrue(WorkspaceIcons.spec("BACK").outline.startsWith("M14 5 L7 12 L14 19"));assertTrue(WorkspaceIcons.spec("CHEVRON").outline.startsWith("M9 5 L16 12 L9 19"))}
    @Test fun fullscreenAndCollapseAreDistinct(){assertNotEquals(WorkspaceIcons.spec("FULLSCREEN"),WorkspaceIcons.spec("COLLAPSE"))}
    @Test fun controlsAndSafetyActionsHaveUniqueIcons(){val keys=listOf("PARAMETERS","MONITOR","MORE","CLOSE","STOP","CROP","FLOW","PALETTE","FILE","RESTORE");assertEquals(keys.size,keys.map{WorkspaceIcons.spec(it)}.toSet().size)}
    @Test fun everyGlyphHasANonEmptyVector(){assertEquals(39,WorkspaceIcons.catalog.size);for((key,s)in WorkspaceIcons.catalog){assertTrue(key,s.outline.isNotBlank()||s.solid.isNotBlank());assertFalse(key,s.outline.contains("NaN")||s.solid.contains("NaN"))}}
    @Test fun allVectorsUseTheSupportedPathAlphabet(){val path=Regex("[MmLlHhVvCcSsQqTtAaZz0-9., +\\-]*");for((k,s)in WorkspaceIcons.catalog){assertTrue(k,path.matches(s.outline));assertTrue(k,path.matches(s.solid))}}
    @Test fun unknownGlyphFailsRatherThanDrawingAWrongFallback(){illegal{WorkspaceIcons.spec("UNKNOWN")}}
    @Test fun unknownViewerFailsRatherThanShowingAnUnrelatedIcon(){try{WorkspaceIcons.viewer("UNKNOWN");fail("unknown viewer accepted")}catch(_:IllegalStateException){}}
}
