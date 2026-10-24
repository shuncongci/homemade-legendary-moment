package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Worker-confined immutable-photo cache. Each unit is a fully refined, validated alpha.
 * Only missing points run models. Removal max-merges retained alphas without inference.
 * A successful unit survives a later failure/cancel, but an incomplete group is not ready. */
class SubjectPointCache(private val load:((Point)->Frame?)?=null, private val save:((Point,Frame)->Unit)?=null) {
    private var photo:String?=null
    private val completed=linkedMapOf<Point,Frame>()
    private var unionKey:List<Point>?=null
    private var unionValue:Frame?=null
    fun retain(points:List<Point>){completed.keys.retainAll(points.toSet());unionKey=null;unionValue=null}
    fun hydrate(photo:String,points:List<Point>){
        if(this.photo!=photo){clear();this.photo=photo}
        points.forEach{point->if(point !in completed)load?.invoke(point)?.let{alpha->
            require(alpha.channels==1 && alpha.data.all{it.isFinite() && it in 0f..1f});SubjectSelectionMath.requireCovered(alpha,listOf(point))
            completed[point]=alpha;unionKey=null;unionValue=null
        }}
    }
    fun clear(){photo=null;completed.clear();unionKey=null;unionValue=null}
    fun completed(photo:String,points:List<Point>):List<Point> = if(this.photo!=photo)emptyList() else points.distinct().filter{it in completed}
    fun contains(photo:String,points:List<Point>)=points.isNotEmpty() && completed(photo,points).size==points.distinct().size
    fun cached(photo:String,points:List<Point>,cancel:Cancel):Frame? {
        cancel.check();val selected=completed(photo,points);if(selected.isEmpty())return null
        val key=SubjectSelectionMath.key(selected)
        if(key==unionKey)return checkNotNull(unionValue)
        val first=completed.getValue(key.first())
        // Max-union, then close contact seams between subjects so only subject/background edges stay soft.
        val merged=if(key.size==1)first else SubjectSelectionMath.joinContacts(
            SubjectSelectionMath.union(key,first.w,first.h,cancel){p,_,_->completed.getValue(p)},
            key.map{completed.getValue(it)},cancel)
        cancel.check();unionKey=key;unionValue=merged;return merged
    }
    fun resolve(enabled:Boolean,photo:String,points:List<Point>,cancel:Cancel,
                compute:(List<Point>,(Point,Frame)->Unit)->Unit):Frame? {
        if(!enabled)return null
        cancel.check();val selected=SubjectSelectionMath.key(points)
        if(this.photo!=photo){clear();this.photo=photo}
        hydrate(photo,selected)
        val missing=selected.filter{it !in completed}
        if(missing.isNotEmpty())compute(missing){point,alpha->
            cancel.check();require(point in missing && point !in completed){"重复或不属于本次请求的保护点"}
            require(alpha.channels==1 && alpha.data.all{it.isFinite() && it in 0f..1f}){"主体蒙版无效"}
            completed.values.firstOrNull()?.let{require(it.w==alpha.w && it.h==alpha.h){"主体蒙版尺寸不一致"}}
            SubjectSelectionMath.requireCovered(alpha,listOf(point))
            cancel.check();save?.invoke(point,alpha) // durable before advertising completion
            completed[point]=alpha;unionKey=null;unionValue=null
        }
        cancel.check();check(contains(photo,selected)){"仍有保护点未完成，已完成点保留"}
        return checkNotNull(cached(photo,selected,cancel))
    }
}
