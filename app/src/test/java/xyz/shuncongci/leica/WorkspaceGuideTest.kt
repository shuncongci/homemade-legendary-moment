package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Documentation coverage and safety wording, not evidence of a visible Android help panel. */
class WorkspaceGuideTest {
    private val g=WorkspaceGuide
    @Test fun topicIdsAreUnique(){assertEquals(g.topics.size,g.topics.map{it.id}.toSet().size)}
    @Test fun everyFunctionHasMainDescriptionStepsAndLimits(){for(t in g.topics){assertTrue(t.id,t.title.length>=4);assertTrue(t.id,t.overview.length>=16);assertTrue(t.id,t.steps.length>=30);assertTrue(t.id,t.limits.length>=30)}}
    @Test fun coreTopicsArePresent(){assertEquals(setOf("editor","import","workflow","style","hdr","focus","aperture","subject","subject-resolution","output","views","space","composition","export","develop","monitor","external","motion"),g.topics.map{it.id}.toSet())}
    @Test fun sevenWorkflowIdentitiesAreCovered(){assertEquals(PhotoWorkflow.entries.map{it.name},g.workflowIds);assertEquals(7,g.workflowIds.size)}
    @Test fun allWorkflowLabelsAreExplained(){val steps=g.topic("workflow").steps;for(w in PhotoWorkflow.entries)assertTrue(w.name,steps.contains(w.label))}
    @Test fun parameterHelpExplainsWholePhotoAfterClose(){assertTrue(g.topic("editor").steps.contains("收起后照片占用全部可用区域"));assertTrue(g.topic("editor").limits.contains("导出上次完整成片"))}
    @Test fun styleHelpUsesUniqueTopLeftEntry(){assertTrue(g.topic("style").overview.contains("唯一风格入口在左上角"));assertTrue(g.topic("style").limits.contains("不会自动导出"))}
    @Test fun hdrHelpExplainsFutureIntentRatherThanDisplayToggle(){assertTrue(g.topic("hdr").steps.contains("左右滑动"));assertTrue(g.topic("hdr").limits.contains("不是屏幕亮度"));assertTrue(g.topic("hdr").limits.contains("失败不会静默改用 SDR"))}
    @Test fun focusHelpSeparatesFocusAndProtection(){assertTrue(g.topic("focus").overview.contains("保护点不是额外焦点"));assertTrue(g.topic("focus").limits.contains("相对深度"))}
    @Test fun aperturePreviewIsNotACompletedPhoto(){assertTrue(g.topic("aperture").steps.contains("九叶光圈"));assertTrue(g.topic("aperture").limits.contains("不是最终散景效果"))}
    @Test fun optionalSubjectHelpRequiresExplicitComputation(){assertTrue(g.topic("subject").overview.contains("默认关闭"));assertTrue(g.topic("subject").steps.contains("统一计算"))}
    @Test fun subjectResolutionIsNotOutputResolution(){assertTrue(g.topic("subject-resolution").overview.contains("不是成片尺寸"));assertTrue(g.topic("subject-resolution").limits.contains("内存成本"))}
    @Test fun outputHelpKeepsPixelCapsAndNoUpscale(){assertTrue(g.topic("output").steps.contains("1250万"));assertTrue(g.topic("output").steps.contains("5000万"));assertTrue(g.topic("output").limits.contains("不放大"))}
    @Test fun viewsHelpHidesSeparateGrayWithoutDeletingDepth(){assertTrue(g.topic("views").limits.contains("深度和空间用于查看，不能直接导出为成片"));assertTrue(g.topic("views").steps.contains("先计算并缓存"))}
    @Test fun spaceHelpCoversAllGesturesAndGyroPause(){for(s in listOf("单指旋转","双指缩放和平移","双击复位","校准"))assertTrue(s,g.topic("space").steps.contains(s));assertTrue(g.topic("space").limits.contains("切后台会暂停"))}
    @Test fun compositionHelpKeepsApplySeparateFromSave(){assertTrue(g.topic("composition").steps.contains("应用返回，再导出"));assertTrue(g.topic("composition").limits.contains("不重新调色或虚化"))}
    @Test fun exportHelpExplainsMetadataAndNonBackup(){assertTrue(g.topic("export").limits.contains("可能含位置"));assertTrue(g.topic("export").limits.contains("PNG 不是原片备份"))}
    @Test fun cancellationHelpDoesNotPromiseImmediateExit(){assertTrue(g.topic("develop").limits.contains("不是立即终止"));assertTrue(g.topic("develop").limits.contains("不发布半成品"))}
    @Test fun monitorHelpDoesNotInventTemperatureOrSpeed(){assertTrue(g.topic("monitor").limits.contains("不是摄氏度"));assertTrue(g.topic("monitor").limits.contains("显影耗时不含界面动画和相册导出"))}
    @Test fun externalHelpKeepsOptInAndOriginalRisk(){assertTrue(g.topic("external").steps.contains("手动开始监听"));assertTrue(g.topic("external").limits.contains("默认同时保留原片"));assertTrue(g.topic("external").limits.contains("删除可能不可恢复"))}
    @Test fun reducedMotionAndDeviceBoundaryAreExplicit(){assertTrue(g.topic("motion").steps.contains("系统关闭动画"));assertTrue(g.topic("motion").limits.contains("在系统设置中关闭动画"))}
}
