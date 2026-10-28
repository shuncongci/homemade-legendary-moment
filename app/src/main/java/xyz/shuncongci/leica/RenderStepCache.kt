package xyz.shuncongci.leica

import java.io.File
import java.util.concurrent.CancellationException

/** Complete substeps of one immutable render, never mutable scratch or progress percentages.
 * LatestCheckpoint syncs, hashes and atomically commits each group. A failed write leaves
 * the last group intact; a new photo/parameter/algorithm key cannot consume an old group.
 * Files returned by load are borrowed READ ONLY until the worker's render transaction ends. */
class RenderStepCache(private val store:LatestCheckpoint,private val identity:String,private val variant:String="default") {
    init{require(variant.matches(Regex("[a-z][a-z0-9-]*")))}
    data class Snapshot(val files:Map<String,File>,val meta:Map<String,String>)
    private val expected get()=mapOf("render.key" to identity)
    private fun current(){if(store.get("render.key")!=identity)throw CancellationException("显影参数已变化，本次旧步骤不再提交")}
    private fun prefix(step:String):String {require(step.matches(Regex("[a-z][a-z0-9-]*")));return "render.steps.$variant.$step"}
    private fun valid(name:String){require(name.matches(Regex("[a-zA-Z0-9_-]+")))}
    fun load(step:String,cancel:Cancel):Snapshot? {
        cancel.check();current();val p=prefix(step);val props=store.properties()
        val names=props["$p.files"]?.split(",")?:return null
        require(props["$p.schema"]=="1" && names.isNotEmpty() && names.distinct().size==names.size){"步骤恢复记录无效：$step"}
        val files=names.associateWith{name->valid(name);cancel.check();checkNotNull(store.file("$p.data.$name")){"步骤恢复文件缺失：$step/$name"}}
        val meta=props.filterKeys{it.startsWith("$p.meta.")}.mapKeys{it.key.removePrefix("$p.meta.")}
        current();cancel.check();return Snapshot(files,meta)
    }
    fun save(step:String,writers:Map<String,(File)->Unit>,cancel:Cancel,meta:Map<String,String> = emptyMap()) {
        cancel.check();current();require(writers.isNotEmpty());writers.keys.forEach(::valid);meta.keys.forEach(::valid)
        val p=prefix(step);val remove=(store.properties().keys+store.keys()).filter{it.startsWith("$p.")}.toSet()
        val label=when(step){"prepare"->"光学准备";"light-source"->"光能提取";"light-scatter"->"全局散射";"sdr-composite"->"SDR合成";"hdr-composite"->"HDR合成";else->if(step.startsWith("base"))"基础区域 ${step.removePrefix("base")}"else step}
        val values=meta.mapKeys{"$p.meta.${it.key}"}+mapOf("$p.schema" to "1","$p.files" to writers.keys.sorted().joinToString(","),"job.label" to "已保存步骤 · $label")
        val blobs=writers.mapKeys{"$p.data.${it.key}"}.mapValues{(_,write)->{f:File->cancel.check();write(f);cancel.check()}}
        if(!store.updateIf(expected,values,remove,blobs))throw CancellationException("显影参数已变化，本次旧步骤不再提交")
    }
    fun saveFiles(step:String,files:Map<String,File>,cancel:Cancel,meta:Map<String,String> = emptyMap()) {
        HdrFloatFile.requireSpace(File(store.directory,"space"),files.values.sumOf{it.length()}+16*HdrMemory.MIB)
        save(step,files.mapValues{(_,from)->{to:File->copyFile(from,to,cancel)}},cancel,meta)
    }
    fun planes(step:String,cancel:Cancel):Planes?=load(step,cancel)?.let{CheckpointPixels.planes(checkNotNull(it.files["pixels"]),cancel)}
    fun savePlanes(step:String,pixels:Planes,cancel:Cancel){
        HdrFloatFile.requireSpace(File(store.directory,"space"),16+3L*pixels.size+16*HdrMemory.MIB)
        save(step,mapOf("pixels" to {f->CheckpointPixels.planes(f,pixels,cancel)}),cancel)
    }
    internal fun restoreRegion(step:String,out:BokehFloatFile,core:BokehBox,cancel:Cancel):Boolean {
        val saved=load(step,cancel)?:return false
        BokehFloatFile.open(checkNotNull(saved.files["pixels"])).use{input->
            require(input.w==core.w && input.h==core.h && input.channels==3){"基础区域恢复尺寸不匹配"}
            val row=FloatArray(core.w)
            for(c in 0..2)for(y in 0 until core.h){input.row(c,y,0,core.w,row,cancel);out.putRow(c,core.top+y,core.left,core.w,row,cancel)}
        }
        cancel.check();return true
    }
    internal fun saveRegion(step:String,input:BokehFloatFile,core:BokehBox,cancel:Cancel){
        input.sync()
        save(step,mapOf("pixels" to {f->BokehFloatFile.create(f,core.w,core.h,3).use{out->
            val row=FloatArray(core.w)
            for(c in 0..2)for(y in 0 until core.h){input.row(c,core.top+y,core.left,core.w,row,cancel);out.putRow(c,y,0,core.w,row,cancel)}
            out.sync()
        }}),cancel)
    }
    companion object {
        fun copyFile(from:File,to:File,cancel:Cancel)=HdrFloatFile.copy(from,to,cancel)
    }
}
