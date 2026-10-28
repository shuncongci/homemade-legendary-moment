package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Real shared-light progress must remain owned by the bokeh UI, not generic loading. */
class NaturalLightProgressTest {
    @Test fun sharedExtractionIsOwned(){assertTrue(BokehProgress.owns("SDR/HDR光能 · 提取光能"))}
    @Test fun hdrOnlyExtractionIsOwned(){assertTrue(BokehProgress.owns("HDR光能 · 提取光能"))}
    @Test fun sharedRowsAreOwned(){assertTrue(BokehProgress.owns("SDR/HDR光能 · 全局散射 128/4380行"))}
    @Test fun hdrOnlyRowsAreOwned(){assertTrue(BokehProgress.owns("HDR光能 · 全局散射 128/4380行"))}
    @Test fun sharedStageKeyIsStable(){assertEquals(BokehProgress.key("SDR/HDR光能 · 提取光能"),BokehProgress.key("SDR/HDR光能 · 全局散射 256/4380行"))}
    @Test fun hdrOnlyStageKeyIsStable(){assertEquals("HDR光能",BokehProgress.key("HDR光能 · 物理半径档位 46级"))}
    @Test fun stageCountDoesNotPretendLayerCompletion(){assertNull(BokehProgress.bar("SDR/HDR光能 · 物理半径档位 46级"))}
    @Test fun outerStagePrefixKeepsSharedIdentity(){assertTrue(BokehProgress.owns("5/7 SDR/HDR光能 · 共用光学散射"));assertEquals("SDR/HDR光能",BokehProgress.key("5/7 SDR/HDR光能 · 共用光学散射"))}
}
