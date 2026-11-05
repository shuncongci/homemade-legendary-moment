package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Display policy only: these tests do not run Android widgets or certify a phone display. */
class ReferenceWorkspacePolicyTest {
    private val p=ReferenceWorkspacePolicy
    private fun badge(result:Boolean=false,compare:Boolean=false,source:Boolean=false,hdr:Boolean=false,pending:Boolean=false,visible:Boolean=false)=p.previewBadge(result,compare,source,hdr,pending,visible)
    private fun illegal(action:()->Unit){try{action();fail("invalid display input accepted")}catch(_:IllegalArgumentException){}}
    @Test fun portraitUsesFullLabels(){assertFalse(p.chrome(800,1f).compact);assertEquals(62,p.chrome(800,1f).tabHeight)}
    @Test fun shortWindowUsesIconOnlyHeight(){assertTrue(p.chrome(360,1f).compact);assertEquals(48,p.chrome(360,1f).tabHeight)}
    @Test fun compactThresholdIsDeterministic(){assertTrue(p.chrome(499,1f).compact);assertFalse(p.chrome(500,1f).compact)}
    @Test fun sixViewTargetsNeverShrinkBelow48(){for(f in listOf(.85f,1f,1.3f,2f,2.5f))assertTrue(p.chrome(800,f).tabsMinWidth>=6*48+5*6)}
    @Test fun largeFontGetsMoreTabSpace(){assertTrue(p.chrome(800,2f).tabsMinWidth>p.chrome(800,1f).tabsMinWidth);assertTrue(p.chrome(800,2f).tabHeight>p.chrome(800,1f).tabHeight)}
    @Test fun oversizedFontKeepsGeometryBounded(){assertEquals(p.chrome(800,2.5f),p.chrome(800,4f))}
    @Test fun invalidChromeInputsFailExplicitly(){illegal{p.chrome(-1,1f)};for(f in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))illegal{p.chrome(800,f)}}
    @Test fun emptyWindowHasDefinedCompactChrome(){assertTrue(p.chrome(0,1f).compact);assertEquals(48,p.chrome(0,1f).header)}
    @Test fun ordinaryDockKeepsIcons(){assertFalse(p.dock(440,1f,false).labelOnly)}
    @Test fun narrowDockUsesTextRatherThanClippedIconAndText(){assertTrue(p.dock(320,1.5f,false).labelOnly);assertEquals(8,p.dock(320,1.5f,false).margin)}
    @Test fun largeFontDockHasExtraHeight(){assertTrue(p.dock(400,2.5f,false).buttonHeight>p.dock(400,1f,false).buttonHeight)}
    @Test fun dockTouchesRemainAtLeast48(){for(w in listOf(0,280,320,360,440,900))for(f in listOf(.85f,1f,1.5f,2.5f,4f))for(c in listOf(false,true))assertTrue(p.dock(w,f,c).buttonHeight>=48)}
    @Test fun compactDockMarginsLeaveMorePhotoRoom(){assertEquals(8,p.dock(900,1f,true).margin);assertEquals(14,p.dock(900,1f,false).margin)}
    @Test fun invalidDockInputsFailExplicitly(){illegal{p.dock(-1,1f,false)};for(f in listOf(0f,Float.NaN,Float.NEGATIVE_INFINITY))illegal{p.dock(400,f,false)}}
    @Test fun actionCapsuleUsesShortDevelopLabel(){for(a in listOf("显影","重新显影","显影并构图","先选焦点","先计算保护","请结束选点"))assertEquals("显影",p.shortAction(a))}
    @Test fun compositionAndRunningHaveDistinctCapsuleLabels(){assertEquals("构图",p.shortAction("选择构图"));assertEquals("显影中",p.shortAction("显影中"))}
    @Test fun absentPhotoNeverShowsFabricatedDimensionsOrTime(){assertEquals("选择照片，或前往相机",p.dimensions(false,0,0,34300))}
    @Test fun dimensionsComeFromActualValues(){assertEquals("4000×3000",p.dimensions(true,4000,3000,null));assertEquals("3005×3775 · 34.3 s",p.dimensions(true,3005,3775,34300))}
    @Test fun zeroElapsedTimeIsNotHidden(){assertEquals("1×1 · 0.0 s",p.dimensions(true,1,1,0))}
    @Test fun negativeElapsedIsOmittedRatherThanClampedToAClaim(){assertEquals("200×100",p.dimensions(true,200,100,-1))}
    @Test fun dimensionsDoNotInventPixels(){illegal{p.dimensions(true,0,1,null)};illegal{p.dimensions(true,1,-1,null)}}
    @Test fun firstNonBlankLineIsTrimmed(){assertEquals("记录",p.oneLine("\n \n 记录 \n更多"));assertEquals("",p.oneLine("\n \n"))}
    @Test fun urgentFailuresAndCancellationAreRecognized(){for(t in listOf("显影失败","已取消","空间不足","无法读取","处理被中断","未发布","未读取原片","不支持此格式","不可用","请勿关闭"))assertTrue(t,p.urgent(t))}
    @Test fun normalStateIsNotUrgent(){for(t in listOf("","导入完成","参数待应用","已保存","正在显影"))assertFalse(t,p.urgent(t))}
    @Test fun failureOnLaterLineDoesNotDisappearBehindSuccessPrefix(){assertEquals("导出失败：空间不足",p.importantLine("已恢复上次成片\n\n 导出失败：空间不足\n详细记录"))}
    @Test fun cancellationOnLaterLineRemainsVisible(){assertEquals("已取消，原片保留",p.importantLine("原图仍在\n已取消，原片保留\n更多"))}
    @Test fun importantLineFallsBackWithoutInventingError(){assertEquals("已保存",p.importantLine("\n 已保存\n耗时"));assertEquals("",p.importantLine("\n"))}
    @Test fun sourceSdrBadgeDoesNotBorrowHdrFromAnOldResult(){assertEquals("SDR 原图",badge(hdr=true))}
    @Test fun sourceHdrRequiresActualDisplayForHdrPreviewLabel(){assertEquals("HDR 预览",badge(source=true,visible=true));assertEquals("HDR · SDR 显示",badge(source=true,visible=false))}
    @Test fun sdrResultDoesNotBorrowHdrFromItsSource(){assertEquals("SDR 成片",badge(result=true,source=true,hdr=false,visible=true))}
    @Test fun hdrResultReportsSdrDisplayHonestly(){assertEquals("HDR · SDR 显示",badge(result=true,hdr=true,visible=false));assertEquals("HDR 成片",badge(result=true,hdr=true,visible=true))}
    @Test fun pendingChoiceDoesNotReclassifyExistingPixels(){assertEquals("SDR 成片\n参数待应用",badge(result=true,source=true,hdr=false,pending=true));assertEquals("HDR 成片\n参数待应用",badge(result=true,hdr=true,pending=true,visible=true))}
    @Test fun pendingChoiceIsNotShownOnOriginal(){assertEquals(badge(source=true,visible=true),badge(source=true,pending=true,visible=true))}
    @Test fun mixedComparisonsNameBothActualFormats(){assertEquals("HDR / SDR 对比",badge(compare=true,source=true));assertEquals("SDR / HDR 对比\n参数待应用",badge(compare=true,hdr=true,pending=true))}
    @Test fun allComparisonPairsAreExplicit(){for(s in listOf(false,true))for(r in listOf(false,true))assertEquals("${if(s)"HDR"else "SDR"} / ${if(r)"HDR"else "SDR"} 对比",badge(compare=true,source=s,hdr=r))}
    @Test fun inactiveListenerCannotAdvertiseLiveCounts(){assertEquals("外接未开启",p.listener(false,true,true,true,17))}
    @Test fun stoppingAndPreparationTakePriorityOverProcessing(){assertEquals("正在停止",p.listener(true,true,true,true,17));assertEquals("正在准备",p.listener(true,false,true,false,17))}
    @Test fun listenerCountsComeFromActualSavedCount(){for(n in listOf(0,1,17,12345)){assertEquals("监听中 · 已保存 $n",p.listener(true,true,false,false,n));assertEquals("处理中 · 已保存 $n",p.listener(true,true,true,false,n))}}
    @Test fun negativeListenerCountIsRejected(){illegal{p.listener(true,true,false,false,-1)}}
    @Test fun largeFontOnShortPortraitUsesCompactTabs(){assertTrue(p.chrome(640,1.5f).compact);assertFalse(p.chrome(650,1.5f).compact)}
    @Test fun veryLargeFontDockFallsBackToAccessibleIcons(){val d=p.dock(320,2.5f,false);assertTrue(d.iconOnly);assertFalse(d.labelOnly)}
    @Test fun ordinaryDockDoesNotUseIconOnlyFallback(){assertFalse(p.dock(440,1f,false).iconOnly);assertFalse(p.dock(320,1f,false).iconOnly)}
    @Test fun dockBudgetsTheLongRunningCaption(){assertTrue(p.dock(320,1f,false).labelOnly);assertFalse(p.dock(440,1f,false).labelOnly)}
}
