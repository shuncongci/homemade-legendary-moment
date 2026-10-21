package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** Non-destructive crop over an existing full-resolution source/result. No renderer or gallery calls. */
object EditorCrop {
    data class Plan(val crop:Crop,val width:Int,val height:Int,val availableWidth:Int,val availableHeight:Int) {
        fun label()="${width}×${height}"
    }
    fun plan(w:Int,h:Int,crop:Crop,output:BokehOutput,blurEnabled:Boolean=true):Plan {
        require(w>0 && h>0)
        val c=CompositionGeometry.snapped(crop,w,h);val b=CompositionGeometry.pixels(c,w,h)
        val (tw,th)=LeicaSizes.processingSize(b.w,b.h,blurEnabled,output)
        return Plan(c,tw,th,b.w,b.h)
    }
    /** Compose using integer source pixels; repeating FULL must not drift by one pixel. */
    fun combine(parent:Crop,local:Crop,w:Int,h:Int):Crop {
        val p=CompositionGeometry.pixels(parent,w,h);val b=CompositionGeometry.pixels(local,p.w,p.h)
        return Crop((p.left+b.left).toDouble()/w,(p.top+b.top).toDouble()/h,(p.left+b.right).toDouble()/w,(p.top+b.bottom).toDouble()/h)
    }
    /** Explicit regeneration only: preserve surrounding scene, target the size AFTER cropping. */
    fun renderPlan(w:Int,h:Int,crop:Crop,output:BokehOutput,blurEnabled:Boolean=true):CompositionOutput.Plan {
        if(!blurEnabled){
            val (ww,wh)=LeicaSizes.colorSize(w,h)
            val p=plan(ww,wh,crop,BokehOutput.ORIGINAL,false)
            return CompositionOutput.Plan(p.crop,p.width,p.height,ww,wh)
        }
        return CompositionOutput.plan(w,h,crop,if(CompositionOutput.eligible(w,h,crop,output))output else BokehOutput.ORIGINAL)
    }
}
