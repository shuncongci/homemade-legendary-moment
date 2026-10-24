package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Production menu/toolbar policies; no Android stubs or mock rendering claims. */
class EditorChromeTest {
    @Test fun hdrOffersExactlyOneUltraHdrJpg(){
        for(sdk in listOf(34,35,36)){
            val options=ExportMenuChoices.available(true,sdk)
            assertEquals(1,options.size);assertTrue(options.single().hdr)
            assertNull(options.single().format);assertEquals("Ultra HDR JPG",options.single().label)
        }
    }
    @Test fun hdrIsSelectedRegardlessOfRememberedSdrFormat(){
        val hdr=ExportMenuChoices.available(true,35).single()
        for(format in ExportFormat.entries)assertTrue(hdr.selected(format))
    }
    @Test fun sdrOffersOnlyAllSupportedSdrFormatsInOriginalOrder(){
        for(sdk in 26..36){
            val options=ExportMenuChoices.available(false,sdk)
            assertEquals(ExportFormat.available(sdk),options.map{it.format})
            assertTrue(options.none{it.hdr || it.label==ExportMenuChoices.ULTRA_HDR_LABEL})
            for(format in ExportFormat.available(sdk))assertEquals(listOf(format),options.filter{it.selected(format)}.map{it.format})
        }
    }
    @Test fun webpLosslessStillRequiresAndroid11(){
        assertFalse(ExportMenuChoices.available(false,29).any{it.format==ExportFormat.WEBP_LOSSLESS})
        assertTrue(ExportMenuChoices.available(false,30).any{it.format==ExportFormat.WEBP_LOSSLESS})
    }
    @Test fun hdrMenuDoesNotRewriteSdrPreferenceOrRenderedState(){
        for(format in ExportFormat.entries){
            val flow=PhotoFlow().apply{photoLoaded();generated();selectExportFormat(format);saved()}
            val before=flow.snapshot()
            assertTrue(ExportMenuChoices.available(true,35).single().selected(flow.exportFormat))
            val sdr=ExportMenuChoices.available(false,35)
            assertEquals(listOf(format),sdr.filter{it.selected(flow.exportFormat)}.map{it.format})
            assertEquals(before,flow.snapshot())
        }
    }
    @Test fun spaceToolbarChecksEveryVisibilityState(){
        for(bits in 0 until 64){
            fun flag(i:Int)=bits and (1 shl i)!=0
            val visible=EditorLayoutPolicy.showSpaceTools(flag(0),flag(1),flag(2),flag(3),flag(4),flag(5))
            // Only space + ready, with parameters collapsed and no other blocking state.
            assertEquals("state=$bits",bits==3,visible)
        }
    }
    @Test fun expandingAndCollapsingParametersDoesNotChangeThePhoto(){
        val flow=PhotoFlow().apply{photoLoaded();generated();saved()};val before=flow.snapshot().minus("expanded")
        repeat(4){
            flow.showParameters(true);assertFalse(EditorLayoutPolicy.showSpaceTools(true,true,flow.parametersExpanded,false,false,false))
            flow.showParameters(false);assertTrue(EditorLayoutPolicy.showSpaceTools(true,true,flow.parametersExpanded,false,false,false))
        }
        assertEquals(before,flow.snapshot().minus("expanded"))
    }
    @Test fun restoredParameterStateUsesTheSameVisibilityRule(){
        for(expanded in listOf(false,true)){
            val prior=PhotoFlow().apply{photoLoaded();showParameters(expanded)}
            val flow=PhotoFlow().apply{restore(prior.snapshot())}
            assertEquals(!expanded,EditorLayoutPolicy.showSpaceTools(true,true,flow.parametersExpanded,false,false,false))
        }
    }
}
