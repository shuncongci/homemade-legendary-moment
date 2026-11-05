package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Presentation interlocks only. Existing service, permission, arrival and deletion tests stay intact. */
class ExternalWorkspacePolicyTest {
    @Test fun startInterlockCoversEveryState(){for(running in listOf(false,true))for(browsing in listOf(false,true))for(editor in listOf(false,true))assertEquals(!running && !browsing && !editor,ExternalWorkspacePolicy.canStart(running,browsing,editor))}
    @Test fun cameraInterlockRequiresLiveReadyNonStoppingRun(){for(running in listOf(false,true))for(ready in listOf(false,true))for(stopping in listOf(false,true))assertEquals(running && ready && !stopping,ExternalWorkspacePolicy.canOpenCamera(running,ready,stopping))}
    @Test fun staleReadyFlagCannotOpenCameraAfterStop(){assertFalse(ExternalWorkspacePolicy.canOpenCamera(false,true,false));assertFalse(ExternalWorkspacePolicy.canOpenCamera(true,true,true))}
    @Test fun preparationDoesNotInviteCapture(){assertEquals("正在准备 · 请勿拍照",ExternalWorkspacePolicy.phase(true,false,false,false));assertEquals("正在准备 · 请勿拍照",ExternalWorkspacePolicy.phase(true,false,true,false));assertFalse(ExternalWorkspacePolicy.canOpenCamera(true,false,false))}
    @Test fun stoppingWinsOverReadyAndWorking(){for(ready in listOf(false,true))for(working in listOf(false,true))assertEquals("正在停止",ExternalWorkspacePolicy.phase(true,ready,working,true))}
    @Test fun idleWinsOverStaleRuntimeFlags(){for(ready in listOf(false,true))for(working in listOf(false,true))for(stopping in listOf(false,true))assertEquals("未开启",ExternalWorkspacePolicy.phase(false,ready,working,stopping))}
    @Test fun readyIdleAndWorkingRemainDifferent(){assertEquals("监听已就绪 · 可拍新照片",ExternalWorkspacePolicy.phase(true,true,false,false));assertEquals("监听已就绪 · 正在处理",ExternalWorkspacePolicy.phase(true,true,true,false));assertTrue(ExternalWorkspacePolicy.canOpenCamera(true,true,false))}
    @Test fun displayDoesNotAlterArrivalAdmissionOrBackfill(){
        val old=ExternalPhoto("old","file:///DCIM/Camera/old.jpg","old.jpg",100,10,false,900)
        val new=ExternalPhoto("new","file:///DCIM/Camera/new.jpg","new.jpg",100,20,false,1001)
        val gate=ExternalArrivalGate(listOf(old),1000)
        repeat(4){ExternalWorkspacePolicy.phase(true,it>0,it>1,false);ExternalWorkspacePolicy.canStart(true,false,false);ExternalWorkspacePolicy.canOpenCamera(true,it>0,false)}
        assertTrue(gate.observe(listOf(old,new),0).isEmpty());assertTrue(gate.observe(listOf(old,new),1500).isEmpty());assertEquals(listOf(new),gate.observe(listOf(old,new),3000));assertTrue(gate.observe(listOf(old,new),6000).isEmpty())
        assertTrue(ExternalArrivalGate(listOf(old,new),7000).observe(listOf(old,new),8000).isEmpty())
    }
}
