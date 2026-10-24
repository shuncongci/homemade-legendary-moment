package xyz.shuncongci.leica

import java.io.File
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Worker-confined durable latest session. UI metadata is separate from model completion. */
class EditSession(directory:File) {
    val store=LatestCheckpoint(directory)
    fun source()=store.file("source")
    fun importSource(file:File){
        store.reset(mapOf("job.kind" to "", "job.stage" to "0"),mapOf("source" to {out->file.inputStream().use{input->out.outputStream().use{input.copyTo(it)}}}))
    }
    fun ui(meta:Map<String,String>){if(source()!=null)store.update(meta.mapKeys{"ui."+it.key})}
    fun ui()=store.properties().filterKeys{it.startsWith("ui.")}.mapKeys{it.key.removePrefix("ui.")}
    fun job(kind:String){store.update(mapOf("job.kind" to kind))}
    fun stage(number:Int,label:String){store.update(mapOf("job.stage" to number.toString(),"job.label" to label))}
    fun clearRender(){
        store.update(mapOf("job.kind" to "", "job.stage" to "0"),store.keys().filter{it.startsWith("render.")}.toSet()+store.properties().keys.filter{it.startsWith("render.")})
    }
    fun beginRender(key:String){
        if(store.get("render.key")!=key){clearRender();store.update(mapOf("render.key" to key))}
        job("render")
    }
    fun planes(key:String,cancel:Cancel)=store.file(key)?.let{CheckpointPixels.planes(it,cancel)}
    fun frame(key:String,cancel:Cancel)=store.file(key)?.let{CheckpointPixels.frame(it,cancel)}
    fun putPlanes(key:String,value:Planes,cancel:Cancel,meta:Map<String,String> = emptyMap(),remove:Set<String> = emptySet())=
        store.update(meta,remove,mapOf(key to {f->CheckpointPixels.planes(f,value,cancel)}))
    fun putFrame(key:String,value:Frame,cancel:Cancel,meta:Map<String,String> = emptyMap())=
        store.update(meta,blobs=mapOf(key to {f->CheckpointPixels.frame(f,value,cancel)}))
    fun subjectKey(point:Point)="subject.${point.x.toRawBits()}.${point.y.toRawBits()}"
    fun selectSubjects(points:List<Point>,resolution:Int,focus:Point?){
        val identity="$resolution:${focus?.x?.toRawBits()}:${focus?.y?.toRawBits()}"
        val keys=points.map(::subjectKey).toSet()
        val obsolete=store.keys().filter{it.startsWith("subject.") && (store.get("subject.identity")!=identity || it !in keys)}.toSet()
        store.update(mapOf("subject.identity" to identity),obsolete)
    }
    fun clearSubjects(){store.update(remove=store.keys().filter{it.startsWith("subject.")}.toSet()+"subject.identity")}
    fun renderCheckpoint()=object:MultiscaleCheckpoint {
        override fun load():MultiscaleCheckpoint.State? {
            val frame=frame("render.multiscale",Cancel())?:return null
            return MultiscaleCheckpoint.State(store.get("render.scale")!!.toFloat(),store.get("render.previous")!!.toFloat(),store.get("render.index")!!.toInt(),store.get("render.finished")=="true",frame)
        }
        override fun save(state:MultiscaleCheckpoint.State,cancel:Cancel){
            putFrame("render.multiscale",state.composite,cancel,mapOf("render.scale" to state.scale.toString(),"render.previous" to state.previous.toString(),"render.index" to state.index.toString(),"render.finished" to state.finished.toString(),"job.stage" to "6","job.label" to "虚化 · 已完成尺度 ${state.index}"))
        }
    }
}

interface MultiscaleCheckpoint {
    data class State(val scale:Float,val previous:Float,val index:Int,val finished:Boolean,val composite:Frame)
    fun load():State?
    fun save(state:State,cancel:Cancel)
}
