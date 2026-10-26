package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** The parameter notification must stay short, plain and honest about missing values. */
class RuntimeMonitorTextTest {
    private fun sample(cpu:DiagnosticMath.Cpu?=DiagnosticMath.Cpu(150.0,18.75),thermal:Int?=0,low:Boolean=false)=
        RuntimeMonitorText.Sample(cpu,8,512.4,120.0,512.0,300.0,3000.0,8000.0,low,thermal)

    @Test fun collapsedLineHasThreeNumbers()=assertEquals("处理器 150% · 内存 512 MB · 温度正常",RuntimeMonitorText.line(sample()))
    @Test fun missingCpuShowsDash()=assertTrue(RuntimeMonitorText.line(sample(cpu=null)).startsWith("处理器 — ·"))
    @Test fun thermalLabels(){
        assertEquals("系统不提供",RuntimeMonitorText.thermal(null))
        assertEquals("正常",RuntimeMonitorText.thermal(0))
        assertEquals("即将关机保护",RuntimeMonitorText.thermal(6))
        assertEquals("未知",RuntimeMonitorText.thermal(99))
    }
    @Test fun detailShowsStageOrIdle(){
        assertTrue(RuntimeMonitorText.detail(sample(),"HDR 显影").startsWith("正在做：HDR 显影\n"))
        assertTrue(RuntimeMonitorText.detail(sample(),"  ").startsWith("正在做：空闲\n"))
    }
    @Test fun detailFlagsLowMemoryOnlyWhenTrue(){
        assertTrue(RuntimeMonitorText.detail(sample(low=true),"").contains("内存紧张"))
        assertFalse(RuntimeMonitorText.detail(sample(),"").contains("内存紧张"))
    }
    @Test fun detailStatesIntervalAndScope(){
        val d=RuntimeMonitorText.detail(sample(),"")
        assertTrue(d.contains("每 2 秒更新"));assertTrue(d.contains("只统计本应用"))
        assertEquals(5,d.lines().size)
    }
}
