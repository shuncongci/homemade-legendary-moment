package xyz.shuncongci.leica

import kotlin.math.*

/** Display-only ARGB patches. Combined queued payload <= one 640-edge preview, never Float32 RGB. */
object BokehPreview {
    const val MAX_SIDE=640
    data class Rect(val x:Int,val y:Int,val w:Int,val h:Int)
    data class Patch(val index:Int,val total:Int,val canvasW:Int,val canvasH:Int,val rect:Rect,val pixels:IntArray)
    fun size(w:Int,h:Int)=LeicaSizes.previewSize(w,h,MAX_SIDE)
    internal fun rect(core:BokehBox,w:Int,h:Int):Rect {
        val (pw,ph)=size(w,h)
        val x=(core.left.toLong()*pw/w).toInt();val y=(core.top.toLong()*ph/h).toInt()
        return Rect(x,y,(core.right.toLong()*pw/w).toInt()-x,(core.bottom.toLong()*ph/h).toInt()-y)
    }
    internal fun patch(p:Planes,core:BokehBox,index:Int,total:Int,cancel:Cancel):Patch {
        require(index in 1..total && total in 1..4)
        val (w,h)=size(p.width,p.height);val r=rect(core,p.width,p.height);val pixels=IntArray(r.w*r.h)
        for(y in 0 until r.h){cancel.check();val gy=(((r.y+y+.5)*p.height/h).toInt()).coerceIn(core.top,core.bottom-1)
            for(x in 0 until r.w){val gx=(((r.x+x+.5)*p.width/w).toInt()).coerceIn(core.left,core.right-1);val i=gy*p.width+gx
                pixels[y*r.w+x]=(255 shl 24) or ((p.p0[i].toInt()and 255) shl 16) or ((p.p1[i].toInt()and 255) shl 8) or (p.p2[i].toInt()and 255)
            }
        }
        return Patch(index,total,w,h,r,pixels)
    }
}

/** UI state is unlocked exclusively by successful renderer callbacks, not elapsed time. */
class BokehRegionReveal(val width:Int,val height:Int,split:Boolean) {
    internal val cores=BokehRenderPolicy.parts(width,height,split)
    val rects=cores.map{BokehPreview.rect(it,width,height)}
    val previewSize=BokehPreview.size(width,height)
    private val ages=FloatArray(cores.size){Float.POSITIVE_INFINITY}
    var done=0;private set
    fun accept(p:BokehPreview.Patch):Boolean {
        if(done>=cores.size || p.index!=done+1 || p.total!=cores.size || (p.canvasW to p.canvasH)!=previewSize)return false
        if(p.rect!=rects[done] || p.pixels.size!=p.rect.w*p.rect.h)return false
        ages[done]=0f;done++;return true
    }
    val needsFrames get()=(0 until done).any{ages[it]<600f}
    fun completed(i:Int)=i in 0 until done
    fun advance(ms:Float){require(ms.isFinite() && ms>=0);for(i in 0 until done)ages[i]=(ages[i]+ms).coerceAtMost(10000f)}
    fun settle(){for(i in ages.indices)ages[i]=10000f}
    fun dark(i:Int)=if(completed(i))0f else .52f
    fun glow(i:Int)=if(completed(i)).018f+.045f*(1-ages[i]/600f).coerceIn(0f,1f)else 0f
}
