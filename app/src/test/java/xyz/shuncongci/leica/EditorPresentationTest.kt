package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class EditorPresentationTest {
    @Test fun panelEndsAtTheExistingExpandedLayout(){
        for((w,h) in listOf(320 to 620,900 to 330,0 to 0,1 to 1))for(f in listOf(1f,1.5f,2f)){
            assertEquals(EditorLayoutPolicy.arrange(w,h,1f,f,true),EditorPresentation.panel(w,h,1f,f,1f))
        }
    }
    @Test fun collapsedPreviewUsesWholeBody(){for((w,h) in listOf(320 to 500,900 to 330)){
        val p=EditorPresentation.panel(w,h,1f,1f,0f)
        assertEquals(EditorLayoutPolicy.Rect(0,0,w,h),p.preview)
        assertTrue(p.controls.x>=w || p.controls.y>=h)
    }}
    @Test fun panelNeverSquashesTheTextWhileMoving(){for((w,h) in listOf(320 to 500,900 to 330)){
        val full=EditorPresentation.panel(w,h,1f,1f,1f)
        for(i in 0..100){val p=EditorPresentation.panel(w,h,1f,1f,i/100f);assertEquals(full.controls.w,p.controls.w);assertEquals(full.controls.h,p.controls.h)}
    }}
    @Test fun portraitPreviewChangesMonotonically(){var height=500;for(i in 0..100){val p=EditorPresentation.panel(320,500,1f,1f,i/100f);assertTrue(p.preview.h<=height);assertTrue(p.controls.y>=p.preview.h);height=p.preview.h}}
    @Test fun landscapePreviewChangesMonotonically(){var width=900;for(i in 0..100){val p=EditorPresentation.panel(900,330,1f,1f,i/100f);assertTrue(p.preview.w<=width);assertTrue(p.controls.x>=p.preview.w);width=p.preview.w}}
    @Test fun interruptedDirectionStartsAtExactlyTheSameGeometry(){for(i in 1..99){val f=i/100f;val before=EditorPresentation.panel(393,520,2f,1.2f,f);val reversed=EditorPresentation.panel(393,520,2f,1.2f,f+(0f-f)*0f);assertEquals(before,reversed)}}
    @Test fun finiteFractionsAreClamped(){assertEquals(EditorPresentation.panel(320,500,1f,1f,0f),EditorPresentation.panel(320,500,1f,1f,-1f));assertEquals(EditorPresentation.panel(320,500,1f,1f,1f),EditorPresentation.panel(320,500,1f,1f,2f))}
    @Test(expected=IllegalArgumentException::class) fun nanPanelRejected(){EditorPresentation.panel(320,500,1f,1f,Float.NaN)}
    @Test fun finalFrameSurvivesDoneUntilTheViewReleasesIt(){for(s in DevelopMotion.Stage.entries)for(ready in listOf(false,true))assertEquals(s==DevelopMotion.Stage.DONE && ready,EditorPresentation.holdFinal(s,ready))}
    @Test fun finalFitMatchesUncroppedCompareViewport(){for((w,h,bw,bh) in listOf(listOf(800,600,400,600),listOf(320,400,1600,900))){
        val actual=EditorPresentation.finalRect(w,h,bw,bh,CompositionGeometry.FULL,1f)
        val expected=PreviewViewport().layout(w.toFloat(),h.toFloat(),bw,bh)
        assertEquals(expected.left,actual.left,.0001f);assertEquals(expected.top,actual.top,.0001f);assertEquals(expected.right,actual.right,.0001f);assertEquals(expected.bottom,actual.bottom,.0001f)
    }}
    @Test fun finalFitMatchesCroppedCompareGeometry(){val c=CompositionGeometry.Crop(.25,.25,.75,.75);val r=EditorPresentation.finalRect(400,300,800,600,c,1f);assertEquals(EditorPresentation.Rect(-200f,-150f,600f,450f),r)}
    @Test fun finalFitStartsAtDecorativeSize(){assertEquals(EditorPresentation.Rect(12f,9f,388f,291f),EditorPresentation.finalRect(400,300,800,600,CompositionGeometry.FULL,0f))}
    @Test fun handoffAllCoordinatesStayFinite(){for((w,h) in listOf(0 to 0,320 to 500,900 to 330))for(i in 0..100){val r=EditorPresentation.finalRect(w,h,800,600,CompositionGeometry.Crop(.1,.2,.7,.9),i/100f);assertTrue(listOf(r.left,r.top,r.right,r.bottom).all{it.isFinite()});assertTrue(r.right>=r.left && r.bottom>=r.top)}}
    @Test fun engineStageNumberAndHardwareAreNotShown(){assertEquals("读取照片",EditorPresentation.stage("1/7 读取 · 8000×6000 · 中央处理器"));assertEquals("应用调色风格",EditorPresentation.stage("4/7 融合 · 8000×6000"))}
    @Test fun depthAndHdrHavePlainNames(){assertEquals("计算照片深度",EditorPresentation.stage("完整深度计算 · 单精度"));assertEquals("生成 HDR 成片",EditorPresentation.stage("HDR · 按行缓存成片与JPEG · 不降低精度"))}
    @Test fun neverHideARealFailureOrFallback(){for(t in listOf("保存失败 · 磁盘空间不足","已转 SDR · 系统不支持","正在取消 · 等待当前步骤退出","保护已计算 · 空间预览暂不可用"))assertEquals(t,EditorPresentation.stage(t))}
    @Test fun unknownStageDoesNotInventProgress(){assertEquals("新的实际阶段",EditorPresentation.stage("新的实际阶段 · 原始详情"));assertEquals("等待操作",EditorPresentation.stage(" "))}
    @Test fun cachedAndColorOnlyDevelopCanCompleteWithoutBokeh(){for(blur in listOf(false,true)){
        val m=DevelopMotion();m.startColor(blur);m.colorCompleted();m.resultCompleted(false);m.advance(10000f)
        assertTrue(EditorPresentation.holdFinal(m.stage,true));m.reset();assertFalse(EditorPresentation.holdFinal(m.stage,false))
    }}
    @Test fun blurAndCombinedDevelopBothHoldFinal(){for(color in listOf(false,true)){
        val m=DevelopMotion();if(color){m.startColor(true);m.colorCompleted()};m.startBokeh();m.bokehPassCompleted();m.resultCompleted(true);m.advance(20000f)
        assertTrue(EditorPresentation.holdFinal(m.stage,true))
    }}
    @Test fun finalClipStartsWithTheWholeDecorativeFrame(){val r=EditorPresentation.Rect(12f,9f,388f,291f);assertEquals(r,EditorPresentation.finalClip(r,CompositionGeometry.Crop(.1,.2,.7,.9),0f))}
    @Test fun finalClipMatchesCroppedCompareLetterboxing(){
        val c=CompositionGeometry.Crop(.25,.1,.75,.9);val r=EditorPresentation.finalRect(400,500,800,600,c,1f)
        val actual=EditorPresentation.finalClip(r,c,1f);val expected=PreviewViewport().layout(400f,500f,400,480)
        assertEquals(expected.left,actual.left,.0001f);assertEquals(expected.top,actual.top,.0001f);assertEquals(expected.right,actual.right,.0001f);assertEquals(expected.bottom,actual.bottom,.0001f)
    }
    @Test fun finalClipStaysInsideTheImageAndDoesNotReverse(){val c=CompositionGeometry.Crop(.1,.2,.7,.9);for(i in 0..100){
        val r=EditorPresentation.finalRect(320,500,800,600,c,i/100f);val clip=EditorPresentation.finalClip(r,c,i/100f)
        assertTrue(clip.left>=r.left && clip.top>=r.top && clip.right<=r.right && clip.bottom<=r.bottom);assertTrue(clip.right>=clip.left && clip.bottom>=clip.top)
    }}
    @Test fun badgeEnabledSdrOnlyShowsJpeg(){for(sdk in listOf(26,29,30,34,35))assertEquals(listOf(ExportFormat.JPEG),ExportMenuChoices.available(false,sdk).filter{ExportMenuChoices.visible(it,true)}.map{it.format})}
    @Test fun badgeEnabledHdrStillHasOneTrueHdrChoice(){for(on in listOf(false,true)){val choices=ExportMenuChoices.available(true,35).filter{ExportMenuChoices.visible(it,on)};assertEquals(1,choices.size);assertTrue(choices.single().hdr)}}
    @Test fun disablingBadgeRestoresTheRememberedFormatWithoutChangingQuality(){for(f in ExportFormat.entries){assertEquals(ExportFormat.JPEG,ExportMenuChoices.exportFormat(f,true));assertEquals(f,ExportMenuChoices.exportFormat(f,false))};assertEquals(95,ExportFormat.JPEG.quality)}
    @Test fun badgeOffDoesNotHideAnySupportedSdrFormats(){for(sdk in listOf(26,30,35))assertEquals(ExportFormat.available(sdk),ExportMenuChoices.available(false,sdk).filter{ExportMenuChoices.visible(it,false)}.map{it.format})}
    @Test fun reducedMotionStillRequiresARealFinal(){val m=DevelopMotion();m.startColor(false);m.colorCompleted();m.reduceMotion();assertFalse(EditorPresentation.holdFinal(m.stage,false));m.resultCompleted(false);m.reduceMotion();assertTrue(EditorPresentation.holdFinal(m.stage,true))}
}
