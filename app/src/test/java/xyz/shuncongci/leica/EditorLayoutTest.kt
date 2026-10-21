package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Geometry regression, not a claim of Android screenshot/device acceptance. */
class EditorLayoutTest {
    @Test fun narrowPortraitKeepsScrollablePanelAndVisiblePreview(){
        for(w in listOf(280,320,360,393,480))for(h in listOf(180,260,420,640))for(font in listOf(1f,1.3f,2f)){
            val p=EditorLayoutPolicy.arrange(w,h,1f,font,true)
            assertFalse(p.sideBySide);assertEquals(w,p.preview.w);assertEquals(w,p.controls.w)
            assertTrue(p.preview.h>=h*.45f);assertTrue(p.controls.h>0);assertTrue(p.controls.y>=p.preview.h)
            assertEquals(h,p.controls.y+p.controls.h)
        }
    }
    @Test fun landscapeUsesTwoColumnsWithEnoughControlWidth(){
        for(w in listOf(720,844,1024,1280))for(font in listOf(1f,1.5f,2f)){
            val p=EditorLayoutPolicy.arrange(w,320,1f,font,true)
            assertTrue(p.sideBySide);assertTrue(p.controls.w>=300);assertTrue(p.preview.w>w*.5f)
            assertEquals(320,p.preview.h);assertEquals(w,p.controls.x+p.controls.w)
        }
    }
    @Test fun collapseGivesEntireBodyToPhoto(){
        for((w,h) in listOf(320 to 400,900 to 300)){
            val p=EditorLayoutPolicy.arrange(w,h,1f,2f,false)
            assertEquals(EditorLayoutPolicy.Rect(0,0,w,h),p.preview);assertEquals(0,p.controls.w);assertEquals(0,p.controls.h)
        }
    }
    @Test fun densityAndRotationKeepAllRegionsInsideBounds(){
        for(d in listOf(1f,1.5f,2.75f,3f))for((wd,hd) in listOf(320 to 420,393 to 510,844 to 260,1280 to 600)){
            val w=(wd*d).toInt();val h=(hd*d).toInt();val p=EditorLayoutPolicy.arrange(w,h,d,1.8f,true)
            for(r in listOf(p.preview,p.controls)){assertTrue(r.x>=0 && r.y>=0 && r.w>=0 && r.h>=0);assertTrue(r.x+r.w<=w);assertTrue(r.y+r.h<=h)}
            assertTrue(p.preview.x+p.preview.w<=p.controls.x || p.preview.y+p.preview.h<=p.controls.y)
        }
    }
    @Test fun transientZeroAndTinyMeasurementsDoNotCrash(){
        for(w in listOf(0,1,2,20))for(h in listOf(0,1,2,20)){
            val p=EditorLayoutPolicy.arrange(w,h,3f,2f,true)
            assertTrue(p.preview.w>=0 && p.preview.h>=0 && p.controls.w>=0 && p.controls.h>=0)
        }
    }
    @Test fun shortWindowsAndLargeFontsUseCompactChrome(){
        assertTrue(EditorLayoutPolicy.compactChrome(360,1f));assertTrue(EditorLayoutPolicy.compactChrome(600,2f))
        assertFalse(EditorLayoutPolicy.compactChrome(800,1f));assertFalse(EditorLayoutPolicy.compactChrome(700,2f))
        assertTrue(EditorLayoutPolicy.arrange(600,250,1f,2f,true).sideBySide)
    }
    @Test fun invalidDensityRejected(){try{EditorLayoutPolicy.arrange(320,500,0f,1f,true);fail()}catch(_:IllegalArgumentException){}}
}
