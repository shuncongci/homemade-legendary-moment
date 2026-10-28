package xyz.shuncongci.leica

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import android.util.Log
import android.util.JsonReader
import java.io.File
import java.util.EnumSet

/** Profile actual ORT provider assignments; requesting NNAPI alone is not proof of GPU/NPU execution. */
class HardwareReport {
    private val accelerated=linkedSetOf<String>()
    private val cpu=linkedSetOf<String>()
    private val unknown=linkedSetOf<String>()
    fun reset(){accelerated.clear();cpu.clear();unknown.clear()}
    fun record(label:String,nnapiNodes:Int,cpuNodes:Int,verified:Boolean){
        if(nnapiNodes>0)accelerated.add(label)
        if(cpuNodes>0)cpu.add(label)
        if(!verified)unknown.add(label)
        Log.i("LegendaryHardware","$label: NNAPI kernel events=$nnapiNodes, CPU kernel events=$cpuNodes, profileVerified=$verified")
    }
    fun summary():String {
        val parts=mutableListOf<String>()
        if(accelerated.isNotEmpty())parts.add("硬件加速执行：${accelerated.joinToString("、")}")
        if(cpu.isNotEmpty())parts.add("中央处理器执行：${cpu.joinToString("、")}")
        if(unknown.isNotEmpty())parts.add("执行归属未确认：${unknown.joinToString("、")}")
        return parts.joinToString(" · ").ifEmpty{"硬件执行归属未确认"}
    }
}

/** One live session per model; per-size symbolic overrides let NNAPI see static photo dimensions. */
class HardwareSession(
    private val env:OrtEnvironment,private val path:String,private val label:String,
    private val cache:File,private val report:HardwareReport,
    private val forceCpu:Boolean=false,
    private val preserveGeluPrimitives:Boolean=false,
    private val modelBytes:(()->ByteArray)?=null,
    private val sessionFactory:((OrtEnvironment,OrtSession.SessionOptions)->OrtSession)?=null
):AutoCloseable {
    private var session:OrtSession?=null
    private var options:OrtSession.SessionOptions?=null
    private var shape:Pair<Int,Int>?=null
    private var requestedHardware=false
    // CPU-only sessions never register NNAPI, including after a shape change.
    private var cpuOnly=forceCpu
    fun forSize(w:Int,h:Int):OrtSession {
        if(shape==w to h)session?.let{return it}
        release()
        val request=!cpuOnly && HardwarePolicy.requestNnapi(Build.VERSION.SDK_INT,forceCpu)
        try {
            create(w,h,request)
        }catch(e:OrtException){
            if(!request || !HardwarePolicy.compatibilityFailure(e.message))throw e
            propagateFatalCause(e)
            Log.w("LegendaryHardware","$label NNAPI driver/model compatibility failure; CPU execution, unchanged photo size",e)
            cpuOnly=true
            create(w,h,false)
        }
        shape=w to h
        return session!!
    }
    /** Keep tensors and RunOptions alive across retry; never resize or reset termination. */
    fun run(w:Int,h:Int,inputs:Map<String,OnnxTensor>,ro:OrtSession.RunOptions,checkCancelled:()->Unit):OrtSession.Result =
        withCpuRetry(
            attempt={checkCancelled();val s=forSize(w,h);checkCancelled();s.run(inputs,ro)},
            shouldRetry={it is OrtException && !cpuOnly && requestedHardware && HardwarePolicy.compatibilityFailure(it.message)},
            beforeRetry={
                checkCancelled()
                cpuOnly=true
                release()
                checkCancelled()
                Log.w("LegendaryHardware","$label NNAPI execution incompatible; retry once on CPU at $w x $h")
            }
        )
    private fun create(w:Int,h:Int,hardware:Boolean){
        cache.mkdirs()
        val opts=OrtSession.SessionOptions()
        try {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            HardwarePolicy.geluSessionConfig(preserveGeluPrimitives).forEach { (key,value) ->
                opts.addConfigEntry(key,value)
            }
            opts.setCPUArenaAllocator(false)
            opts.setMemoryPatternOptimization(false)
            opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
            HardwarePolicy.dimensions(w,h).forEach{(name,value)->opts.setSymbolicDimensionValue(name,value)}
            if(hardware)opts.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED,NNAPIFlags.USE_FP16))
            // CPU-only attribution is already known: avoid native per-node profiling overhead.
            if(hardware)opts.enableProfiling(File(cache,"hardware-${java.util.UUID.randomUUID()}").absolutePath)
            val made=if(sessionFactory!=null){
                check(modelBytes==null){"Only one model source is allowed"}
                sessionFactory.invoke(env,opts)
            }else if(modelBytes==null)env.createSession(path,opts)else{
                val bytes=modelBytes.invoke()
                try{env.createSession(bytes,opts)}finally{bytes.fill(0)}
            }
            session=made;options=opts;requestedHardware=hardware
            Log.i("LegendaryHardware","$label $w x $h: NNAPI hardware requested=$hardware; providerProfiling=$hardware")
        }catch(t:Throwable){
            try { opts.close() } catch(cleanup:Throwable) { if(cleanup!==t)t.addSuppressed(cleanup) }
            throw t // Cleanup only: never swallow OOM or other fatal errors.
        }
    }
    private fun release(){
        val s=session ?: return
        val opts=options;val hardware=requestedHardware
        session=null;options=null;shape=null
        var file:File?=null
        try {
            try {
                if(hardware)try { file=File(s.endProfiling()) }
                catch(e:Exception){propagateFatalCause(e);Log.w("LegendaryHardware","Provider profile unavailable",e)}
            } finally {
                // Always attempt both closes, even if endProfiling/session close throws.
                closeNativeResources(listOfNotNull(s,opts))
            }
            if(!hardware){report.record(label,0,1,true);return}
            try {
                val counts=file?.let { profileCounts(it) }
                report.record(label,counts?.first ?: 0,counts?.second ?: 0,
                    counts!=null && counts.first+counts.second>0)
            }catch(e:Exception){
                propagateFatalCause(e)
                report.record(label,0,0,false)
                Log.w("LegendaryHardware","Provider profile unavailable",e)
            }
        }finally{file?.delete()}
    }
    /** Stream diagnostics after releasing native weights, rather than building a full JSON tree. */
    private fun profileCounts(file:File):Pair<Int,Int> {
        var nnapi=0;var cpu=0
        JsonReader(file.bufferedReader()).use { reader ->
            reader.beginArray()
            while(reader.hasNext()){
                var category="";var provider=""
                reader.beginObject()
                while(reader.hasNext())when(reader.nextName()){
                    "cat" -> category=reader.nextString()
                    "args" -> {
                        reader.beginObject()
                        while(reader.hasNext()){
                            if(reader.nextName()=="provider")provider=reader.nextString()
                            else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
                reader.endObject()
                if(category=="Node")when(provider){
                    "NnapiExecutionProvider" -> nnapi++
                    "CPUExecutionProvider" -> cpu++
                }
            }
            reader.endArray()
        }
        return nnapi to cpu
    }
    override fun close(){release()}
}
