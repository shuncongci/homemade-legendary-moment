package xyz.shuncongci.leica

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest

/** FP32 CPU baseline. Shared whole-image features for every candidate in one native run.
 * No NNAPI/FP16 conversion and no fallback to heuristic scores or reduced image resolution.
 * Native resources live only on the composition worker; UI cancellation only terminates RunOptions.
 */
class GaicComposition {
    private val active=ActiveNativeRun<OrtSession.RunOptions>()
    fun cancelRun(){try{active.cancel{it.setTerminate(true)}}catch(e:Exception){propagateFatalCause(e)}}
    fun analyse(context:Context,image:Planes,w:Int,h:Int,output:BokehOutput,cancel:Cancel):List<CompositionAnalysis.Suggestion> {
        cancel.check();val crops=GaicCompositionMath.candidates(w,h,output,cancel)
        val (iw,ih)=GaicCompositionMath.modelSize(image.width,image.height)
        val input=GaicCompositionMath.input(image,cancel)
        val boxes=GaicCompositionMath.boxes(crops,image.width,image.height)
        val bytes=context.assets.open(ASSET).use{it.readBytes()};cancel.check()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        check(hash==SHA256){"构图模型校验失败，请重新安装"}
        val env=OrtEnvironment.getEnvironment().also{it.setTelemetry(false)}
        OrtSession.SessionOptions().use{options->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1,4))
            options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false)
            cancel.check()
            env.createSession(bytes,options).use{session->
                cancel.check()
                val ro=OrtSession.RunOptions();active.attach(ro)
                try{
                    cancel.check()
                    OnnxTensor.createTensor(env,FloatBuffer.wrap(input),longArrayOf(1,3,ih.toLong(),iw.toLong())).use{itensor->
                        OnnxTensor.createTensor(env,FloatBuffer.wrap(boxes),longArrayOf(crops.size.toLong(),4)).use{btensor->
                            cancel.check();val start=System.nanoTime()
                            session.run(mapOf("image" to itensor,"boxes" to btensor),ro).use{result->
                                cancel.check();val tensor=result.get(0) as OnnxTensor
                                check(tensor.info.shape.contentEquals(longArrayOf(crops.size.toLong()))){"构图模型输出尺寸不符"}
                                val scores=FloatArray(crops.size);tensor.floatBuffer.get(scores)
                                Log.i("GaicComposition","FP32 CPU ${image.width}x${image.height}, tensor=${iw}x${ih}, candidates=${crops.size}, inferenceMs=${(System.nanoTime()-start)/1_000_000}")
                                return GaicCompositionMath.select(crops,scores,cancel)
                            }
                        }
                    }
                }finally{active.release(ro)}
            }
        }
    }
    companion object {
        const val ASSET="leica/gaic-mobilenetv2-reddim16.fp32.onnx"
        const val SHA256="85d1efb614594871adce2f4f28c9804ae8b69db12680108c5699ed74394871b3"
    }
}
