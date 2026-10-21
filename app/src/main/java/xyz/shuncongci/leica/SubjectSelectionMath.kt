package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point
import kotlin.math.max
import kotlin.math.min

/** One focus is separate from this set of positive subject selections. No automatic seeds. */
object SubjectSelectionMath {
    fun key(points:List<Point>):List<Point> {
        require(points.isNotEmpty()){"请先选择至少一个主体保护点"}
        return points.distinct().sortedWith(compareBy<Point>{it.x}.thenBy{it.y})
    }
    fun index(point:Point,w:Int,h:Int):Int {
        require(w>0 && h>0)
        return (point.y*h).toInt().coerceIn(0,h-1)*w+(point.x*w).toInt().coerceIn(0,w-1)
    }
    /** Prefer the highest-score candidate that actually contains this positive point. */
    fun select(logits:FloatArray,scores:FloatArray,point:Point,w:Int,h:Int,cancel:Cancel):Frame {
        cancel.check();require(logits.size==3*256*256 && scores.size==3)
        require(logits.all{it.isFinite()} && scores.all{it.isFinite()})
        val pixel=index(point,w,h)
        for(best in scores.indices.sortedByDescending{scores[it]}) {
            val low=Frame(256,256,1,logits.copyOfRange(best*256*256,(best+1)*256*256))
            if(FullFrameOptics.sample(low,pixel%w,pixel/w,w,h)<=0f)continue
            val mask=LegendaryMath.resize(low,w,h,cancel=cancel)
            for(i in mask.data.indices){if(i%w==0)cancel.check();mask.data[i]=if(mask.data[i]>0f)1f else 0f}
            require(mask.data[pixel]==1f){"未覆盖保护点，请点选目标内部"}
            return mask
        }
        throw IllegalArgumentException("未识别到该保护点的主体，请重新点选目标内部")
    }
    /** Stream point masks into a union, never an intersection or 'last point wins'.
     * No partial result escapes if any point fails or the operation is cancelled. */
    fun union(points:List<Point>,w:Int,h:Int,cancel:Cancel,segment:(Point,Int,Int)->Frame):Frame {
        cancel.check();key(points);val selected=points.distinct();val result=Frame(w,h,1,FloatArray(w*h))
        selected.forEachIndexed{at,point->
            cancel.check();val mask=segment(point,at,selected.size)
            require(mask.w==w && mask.h==h && mask.channels==1 && mask.data.all{it.isFinite() && it in 0f..1f}){"主体蒙版尺寸或数值异常"}
            require(mask.data[index(point,w,h)]>=.5f){"第${at+1}个保护点未被识别，请调整该点后统一计算"}
            for(i in result.data.indices){if(i%w==0)cancel.check();result.data[i]=max(result.data[i],mask.data[i])}
        }
        cancel.check();return result
    }
    /** Exclusive-core / empty thresholds shared with the render-side edge cleaners (.98 / .02). */
    const val CORE=.98f
    const val EMPTY=.02f
    /** Box half-size spanning both soft ramps of a contact seam (11 at 1220, 17 at 2048). */
    fun contactRadius(w:Int,h:Int)=2*SubjectProtectionMath.trimapRadius(w,h)+1
    private fun smooth(v:Float):Float{val t=v.coerceIn(0f,1f);return t*t*(3-2*t)}
    /** A seam where two protected subjects touch is not a subject/blurred-background edge.
     *  Each point is matted alone, so its matte treats a touching neighbour as background and the
     *  max-union dips along the contact line; downstream edge cleanup and blur mixing would then
     *  treat that line like an outer silhouette. Lift such pixels toward 1 only where the box of
     *  radius [contactRadius] holds exclusive cores (one mask >= CORE, all others <= EMPTY) of at
     *  least two different subjects and almost no true background (union < EMPTY):
     *    w = smooth(f2/.02) * (1 - smooth((fb-.06)/.10)),  alpha' = u + w(1-u)
     *  f2 = second-largest exclusive-core fraction, fb = background fraction in the box.
     *  Outer silhouettes and real background gaps (fb >= .16) are bit-exact unchanged.
     *  Only raises alpha, never mutates inputs, returns [union] itself when nothing changes. */
    fun joinContacts(union:Frame,masks:List<Frame>,cancel:Cancel):Frame {
        cancel.check();val w=union.w;val h=union.h;val n=w*h
        require(union.channels==1 && masks.all{it.w==w && it.h==h && it.channels==1}){"主体蒙版尺寸不一致"}
        if(masks.size<2)return union
        val owner=IntArray(n){-1};val has=BooleanArray(masks.size)
        for(i in 0 until n){
            if(i%w==0)cancel.check()
            var k=-1;var many=false
            for(m in masks.indices)if(masks[m].data[i]>EMPTY){if(k>=0){many=true;break};k=m}
            if(!many && k>=0 && masks[k].data[i]>=CORE){owner[i]=k;has[k]=true}
        }
        if(has.count{it}<2)return union
        val r=contactRadius(w,h);val W=w+1;val sum=IntArray(W*(h+1))
        // Row 0 / column 0 stay zero; every interior entry is rewritten per pass.
        fun integral(inside:(Int)->Boolean){
            for(y in 0 until h){cancel.check();var row=0
                for(x in 0 until w){if(inside(y*w+x))row++;sum[(y+1)*W+x+1]=sum[y*W+x+1]+row}}
        }
        fun frac(x:Int,y:Int):Float{
            val x0=max(0,x-r);val x1=min(w,x+r+1);val y0=max(0,y-r);val y1=min(h,y+r+1)
            return (sum[y1*W+x1]-sum[y0*W+x1]-sum[y1*W+x0]+sum[y0*W+x0]).toFloat()/((x1-x0)*(y1-y0))
        }
        integral{union.data[it]<EMPTY}
        var count=0
        for(i in 0 until n){if(i%w==0)cancel.check();if(union.data[i]<1f && frac(i%w,i/w)<.16f)count++}
        if(count==0)return union
        val cand=IntArray(count);val fb=FloatArray(count);count=0
        for(i in 0 until n)if(union.data[i]<1f){val f=frac(i%w,i/w);if(f<.16f){cand[count]=i;fb[count]=f;count++}}
        val f1=FloatArray(count);val f2=FloatArray(count)
        for(m in masks.indices){
            if(!has[m])continue
            integral{owner[it]==m}
            for(j in 0 until count){
                if(j%4096==0)cancel.check()
                val f=frac(cand[j]%w,cand[j]/w)
                if(f>f1[j]){f2[j]=f1[j];f1[j]=f}else if(f>f2[j])f2[j]=f
            }
        }
        var out:FloatArray?=null
        for(j in 0 until count){
            val weight=smooth(f2[j]/.02f)*(1f-smooth((fb[j]-.06f)/.10f))
            if(weight<=0f)continue
            val i=cand[j];val u=union.data[i]
            val v=if(weight>=1f)1f else min(1f,u+weight*(1f-u))
            if(v>u){val o=out?:union.data.copyOf().also{out=it};o[i]=v}
        }
        cancel.check()
        return out?.let{Frame(w,h,1,it)}?:union
    }
    fun requireCovered(alpha:Frame,points:List<Point>) {
        require(alpha.channels==1);key(points)
        points.forEachIndexed{at,point->require(alpha.data[index(point,alpha.w,alpha.h)]>=.5f){"第${at+1}个保护点在细化后未被覆盖，请调整该点后统一计算"}}
    }
    fun finish(padded:Frame,w:Int,h:Int,trimap:FloatArray,points:List<Point>,cancel:Cancel):Frame {
        require(trimap.size==w*h)
        val alpha=SubjectProtectionMath.unpad(padded,w,h,cancel)
        for(i in alpha.data.indices){if(i%w==0)cancel.check();if(trimap[i]==1f)alpha.data[i]=1f else if(trimap[i]==0f)alpha.data[i]=0f}
        requireCovered(alpha,points);cancel.check();return alpha
    }
}
