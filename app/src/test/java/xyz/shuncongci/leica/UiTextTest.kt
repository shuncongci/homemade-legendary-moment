package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UiTextTest {
    private fun chinese(text:String){assertTrue(text.isNotBlank());assertFalse(text,Regex("[A-Za-z]").containsMatchIn(text))}
    @Test fun allOperationsHaveChineseNames(){
        val labels=PhotoFlow.Operation.entries.map{UiText.operation(it)}
        labels.forEach{chinese(it)};assertEquals(labels.size,labels.distinct().size)
    }
    @Test fun threadStatesDoNotClaimCpuWorkFromRunnable(){
        Thread.State.values().forEach{chinese(UiText.threadState(it))}
        assertEquals("可运行",UiText.threadState(Thread.State.RUNNABLE))
        assertEquals("尚未启动",UiText.threadState(null))
    }
    @Test fun knownPhotoErrorsRemainActionable(){
        for(message in listOf("无法打开这张照片","不支持的照片格式","无法解码这张照片","请至少选择调色或虚化")){
            val result=UiText.failure(IllegalArgumentException(message));chinese(result);assertTrue(result.contains(message))
        }
    }
    @Test fun unknownMessagesDoNotLeakIntoMainInterface(){
        val error=IllegalStateException("InternalKernel failed at secret/path/file.kt")
        chinese(UiText.failure(error));assertFalse(UiText.failure(error).contains("secret"))
        assertTrue(error.stackTraceToString().contains("InternalKernel failed at secret/path/file.kt"))
    }
    @Test fun permissionErrorsHaveChineseAction(){
        val result=UiText.failure(SecurityException("Permission denied"));chinese(result);assertTrue(result.contains("权限"))
    }
    @Test fun ioFailuresDoNotHideOriginalDetails(){
        val error=IOException("ENOSPC device=local")
        val result=UiText.failure(error);chinese(result);assertTrue(result.contains("空间"));assertEquals("ENOSPC device=local",error.message)
    }
    @Test fun nullAndUnknownChineseMessagesRemainAvailableOnlyAsDetails(){
        chinese(UiText.failure(Exception()))
        val error=Exception("内部测试路径，不是已知公开提示")
        assertFalse(UiText.failure(error).contains(error.message!!));assertTrue(error.stackTraceToString().contains(error.message!!))
    }
}
