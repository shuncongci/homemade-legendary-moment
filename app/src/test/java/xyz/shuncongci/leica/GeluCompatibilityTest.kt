package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class GeluCompatibilityTest {
    @Test fun depthDisablesBothActualOrt128TransformerNames(){
        val config=HardwarePolicy.geluSessionConfig(true)
        assertEquals("GeluFusionL1;GeluFusionL2",config["optimization.disable_specified_optimizers"])
        assertFalse(config.values.single().split(';').contains("GeluFusion"))
    }
    @Test fun ganAndBokehRetainDefaultOptimizationConfig(){
        assertTrue(HardwarePolicy.geluSessionConfig(false).isEmpty())
    }
    @Test fun workaroundDoesNotEnableApproximationOrChangePrecision(){
        assertEquals(setOf("optimization.disable_specified_optimizers"),HardwarePolicy.geluSessionConfig(true).keys)
        assertFalse(HardwarePolicy.requestNnapi(36,forceCpu=true))
        assertEquals(mapOf("height" to 518L,"width" to 686L),HardwarePolicy.dimensions(686,518))
    }
    @Test fun everyCreationGetsTheSameConfigWithoutMutatingOtherSessions(){
        val before=HardwarePolicy.geluSessionConfig(true)
        HardwarePolicy.geluSessionConfig(false)
        assertEquals(before,HardwarePolicy.geluSessionConfig(true))
        assertEquals(mapOf("height" to 686L,"width" to 518L),HardwarePolicy.dimensions(518,686))
    }
}
