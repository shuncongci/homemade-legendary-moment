package xyz.shuncongci.leica

import kotlin.math.max
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** GAIC input contract and geometry-only enumeration. No saliency, subject or rule scores. */
object GaicCompositionMath {
    private val mean=floatArrayOf(.485f,.456f,.406f)
    private val std=floatArrayOf(.229f,.224f,.225f)
    // RoI/RoD interpolation needs >=2 feature pixels. Tiny axes get replicated padding, NOT upscaling.
    fun modelSize(w:Int,h:Int):Pair<Int,Int> {
        require(w>0 && h>0 && max(w,h)<=CompositionGeometry.ANALYSIS_EDGE)
        return max(32,w) to max(32,h)
    }
    fun input(image:Planes,cancel:Cancel=Cancel()):FloatArray {
        val (w,h)=modelSize(image.width,image.height);val n=w*h
        val out=FloatArray(3*n)
        for(c in 0..2){val plane=image.plane(c)
            for(y in 0 until h){cancel.check();val row=y.coerceAtMost(image.height-1)*image.width
                for(x in 0 until w){val v=plane[row+x.coerceAtMost(image.width-1)].toInt() and 255
                    out[c*n+y*w+x]=(v/255f-mean[c])/std[c]
                }
            }
        };return out
    }
    fun candidates(w:Int,h:Int,output:BokehOutput,cancel:Cancel=Cancel()):List<Crop> {
        require(w>0 && h>0);cancel.check()
        val pool=linkedSetOf(CompositionGeometry.FULL)
        val ratios=listOf(w.toDouble()/h,1.0,4.0/3,1.5,16.0/9,3.0/4,2.0/3,9.0/16).distinct()
        for(ratio in ratios)for(scale in doubleArrayOf(1.0,.85,.70,.55)){
            val base=CompositionGeometry.fit(w,h,ratio,scale)
            for(x in 0..4)for(y in 0..4){cancel.check()
                val crop=CompositionGeometry.snapped(CompositionGeometry.placed(base.w,base.h,(1-base.w)*x/4,(1-base.h)*y/4),w,h)
                if(CompositionOutput.eligible(w,h,crop,output))pool.add(crop)
            }
        };return pool.toList()
    }
    /** Boxes stay in the actual analysis-image pixels, excluding any tiny-axis padding. */
    fun boxes(crops:List<Crop>,w:Int,h:Int):FloatArray {
        require(crops.isNotEmpty());modelSize(w,h)
        return FloatArray(crops.size*4).also{out->crops.forEachIndexed{i,c->
            out[i*4]=(c.l*w).toFloat();out[i*4+1]=(c.t*h).toFloat()
            out[i*4+2]=(c.r*w).toFloat();out[i*4+3]=(c.b*h).toFloat()
        }}
    }
    /** Original participates in scoring and remains selectable, without any artificial score bonus. */
    fun select(crops:List<Crop>,scores:FloatArray,cancel:Cancel=Cancel()):List<CompositionAnalysis.Suggestion> {
        cancel.check();require(crops.isNotEmpty() && crops.first()==CompositionGeometry.FULL)
        require(crops.size==scores.size && scores.all{it.isFinite()}){"构图模型返回了无效评分，请重试"}
        val selected=mutableListOf(0)
        for(i in scores.indices.sortedByDescending{scores[it]}){
            cancel.check();if(selected.size>=4)break
            if(i==0 || scores[i]<=scores[0])continue
            if(selected.none{CompositionGeometry.iou(crops[it],crops[i])>.88})selected.add(i)
        }
        var number=0
        return selected.sortedByDescending{scores[it]}.map{i->
            if(i==0)CompositionAnalysis.Suggestion(crops[i],"保留原构图","完整画面；已参与模型评分",null)
            else CompositionAnalysis.Suggestion(crops[i],"模型方案 ${++number}","按构图模型评分排序",null,(scores[i]-scores[0]).toDouble())
        }
    }
}
