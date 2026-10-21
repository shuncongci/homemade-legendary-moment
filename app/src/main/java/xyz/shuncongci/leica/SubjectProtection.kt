package xyz.shuncongci.leica

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** Missing points only. Each point gets its own SAM + ViTMatte alpha for reversible unions. */
internal class SubjectProtection(
    private val env:OrtEnvironment,private val path:(String,String)->String,
    private val directory:File,private val report:HardwareReport,
    private val active:ActiveNativeRun<OrtSession.RunOptions>
) {
    companion object {
        const val SAM="leica/subject-sam2.1-tiny.fp32.onnx"
        const val SAM_SHA="70197e1d6d05acb8b7c69aa78366746ce2b64fe89d9b9d162623cf42e3cc2818"
        const val MATTE="leica/subject-vitmatte-small.fp32.onnx"
        const val MATTE_SHA="e07f57abe253b6a4f81cc87f9b4f5145f9893d375f7f48690710d4168b59e51b"
    }
    private data class Input(val data:FloatArray,val shape:LongArray)
    private data class Output(val data:FloatArray,val shape:LongArray)
    private fun model(asset:String,sha:String)=HardwareSession(env,path(asset,sha),if(asset==SAM)"主体识别" else "主体边缘",directory,report,forceCpu=true,preserveGeluPrimitives=true)
    private fun run(s:HardwareSession,w:Int,h:Int,inputs:Map<String,Input>,cancel:Cancel,shared:Map<String,OnnxTensor> = emptyMap()):List<Output> {
        cancel.check();val tensors=linkedMapOf<String,OnnxTensor>();val options=OrtSession.RunOptions();active.attach(options)
        try {
            inputs.forEach{(name,input)->cancel.check();tensors[name]=OnnxTensor.createTensor(env,FloatBuffer.wrap(input.data),input.shape)}
            s.run(w,h,shared+tensors,options){cancel.check()}.use{result->
                cancel.check();return (0 until result.size()).map{i->
                    val t=result.get(i) as OnnxTensor
                    val a=FloatArray(t.info.numElements.toInt());t.floatBuffer.get(a)
                    require(a.all{it.isFinite()}){"主体保护模型返回无效数值"}
                    Output(a,t.info.shape)
                }
            }
        } finally {try{active.release(options)}finally{closeNativeResources(tensors.values)}}
    }
    fun computePoints(rgb:LegendaryMath.Frame,points:List<LegendaryMath.Point>,cancel:Cancel,progress:(String)->Unit,commit:(LegendaryMath.Point,LegendaryMath.Frame)->Unit) {
        cancel.check();SubjectSelectionMath.key(points)
        require(rgb.channels==3 && maxOf(rgb.w,rgb.h)<=SubjectProtectionMath.ANALYSIS_LONG_EDGE)
        // Do not retain SAM and ViTMatte sessions simultaneously. A point is committed
        // only after both models and coverage checks succeed; earlier successes survive.
        val samInput=SubjectProtectionMath.samInput(rgb,cancel)
        points.distinct().forEachIndexed{at,point->
            progress(UiText.subjectLoading(at+1,points.size,false))
            val sam=model(SAM,SAM_SHA).use{s->run(s,1024,1024,mapOf("image" to Input(samInput,longArrayOf(1,3,1024,1024)),"point" to Input(floatArrayOf(point.x*1024,point.y*1024),longArrayOf(1,1,2))),cancel)}
            require(sam.size==2 && sam[0].shape.contentEquals(longArrayOf(1,3,256,256)) && sam[1].data.size==3){"主体识别模型输出尺寸异常"}
            val mask=SubjectSelectionMath.select(sam[0].data,sam[1].data,point,rgb.w,rgb.h,cancel)
            cancel.check();progress(UiText.subjectLoading(at+1,points.size,true))
            val trimap=SubjectProtectionMath.trimap(mask,cancel)
            val input=SubjectProtectionMath.matteInput(rgb,trimap,cancel)
            // 2.1.21: whole pass for small frames, else 1024 tiles; all tiles share one size and one session.
            val padded=model(MATTE,MATTE_SHA).use{s->SubjectMatteTiles.matte(input,cancel){tile->
                val out=run(s,tile.w,tile.h,mapOf("pixel_values" to Input(tile.data,longArrayOf(1,4,tile.h.toLong(),tile.w.toLong()))),cancel).single()
                require(out.shape.contentEquals(longArrayOf(1,1,tile.h.toLong(),tile.w.toLong()))){"主体边缘模型输出尺寸异常"}
                LegendaryMath.Frame(tile.w,tile.h,1,out.data)
            }}
            val alpha=SubjectSelectionMath.finish(padded,rgb.w,rgb.h,trimap,listOf(point),cancel)
            cancel.check();commit(point,alpha)
        }
    }
}
