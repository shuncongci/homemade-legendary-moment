package xyz.shuncongci.leica

import kotlin.math.*

/** Optional single-photo/whole-selection cache. Disabled is a strict zero-compute path. */
class SubjectMaskCache {
    private var key:Pair<String,List<LegendaryMath.Point>>?=null
    private var value:LegendaryMath.Frame?=null
    fun clear(){key=null;value=null}
    fun contains(photo:String,point:LegendaryMath.Point)=contains(photo,listOf(point))
    fun contains(photo:String,points:List<LegendaryMath.Point>)=points.isNotEmpty() && key==(photo to SubjectSelectionMath.key(points)) && value!=null
    fun get(enabled:Boolean,photo:String,point:LegendaryMath.Point,cancel:Cancel,compute:()->LegendaryMath.Frame):LegendaryMath.Frame? = get(enabled,photo,listOf(point),cancel,compute)
    fun get(enabled:Boolean,photo:String,points:List<LegendaryMath.Point>,cancel:Cancel,compute:()->LegendaryMath.Frame):LegendaryMath.Frame? {
        if(!enabled)return null
        cancel.check();val next=photo to SubjectSelectionMath.key(points)
        if(key==next)return checkNotNull(value)
        clear();val result=compute()
        require(result.channels==1 && result.data.all{it.isFinite() && it in 0f..1f}){"主体蒙版无效"}
        require(result.data.any{it>=.5f}){"未识别到主体，请重新点选目标内部"}
        cancel.check();key=next;value=result;return result
    }
}

/** Model I/O and render math; source coordinates are EXIF-corrected and never crop-relative. */
object SubjectProtectionMath {
    /** 2.1.21: subject alpha long edge 2048 (was 1220). SAM stays at its fixed 1024 input;
     *  ViTMatte runs at this size via SubjectMatteTiles. Smaller photos are never upscaled. */
    const val ANALYSIS_LONG_EDGE=2048
    /** Unknown-band radius tracks the analysis scale: 5px at the old 1220 size, 8px at 2048. */
    fun trimapRadius(w:Int,h:Int):Int{require(w>0 && h>0);return max(5,(5.0*max(w,h)/1220).roundToInt())}
    private val mean=floatArrayOf(.485f,.456f,.406f)
    private val std=floatArrayOf(.229f,.224f,.225f)
    fun analysisSize(w:Int,h:Int):Pair<Int,Int>{
        require(w>0 && h>0);val scale=min(1.0,ANALYSIS_LONG_EDGE.toDouble()/max(w,h))
        return max(1,round(w*scale).toInt()) to max(1,round(h*scale).toInt())
    }
    /** Torch tensor bilinear antialias (float separable, no intermediate 8-bit rounding). */
    fun samInput(rgb:LegendaryMath.Frame,cancel:Cancel):FloatArray {
        require(rgb.channels==3)
        fun axis(src:FloatArray,iw:Int,ih:Int,ow:Int,oh:Int,horizontal:Boolean):FloatArray {
            val out=FloatArray(ow*oh*3);val inSide=if(horizontal)iw else ih;val outSide=if(horizontal)ow else oh
            val ratio=inSide.toDouble()/outSide;val support=max(1.0,ratio)
            for(c in 0..2)for(y in 0 until oh){if(y and 31==0)cancel.check();for(x in 0 until ow){
                val at=if(horizontal)x else y;val center=(at+.5)*ratio-.5
                val lo=max(0,ceil(center-support).toInt());val hi=min(inSide-1,floor(center+support).toInt());var sum=0.0;var weight=0.0
                for(j in lo..hi){val v=max(0.0,1-abs(j-center)/support);val ix=if(horizontal)j else x;val iy=if(horizontal)y else j;sum+=src[c*iw*ih+iy*iw+ix]*v;weight+=v}
                out[c*ow*oh+y*ow+x]=(sum/weight).toFloat()
            }};return out
        }
        val h=axis(rgb.data,rgb.w,rgb.h,1024,rgb.h,true)
        val out=axis(h,1024,rgb.h,1024,1024,false)
        for(c in 0..2)for(i in 0 until 1024*1024)out[c*1024*1024+i]=(out[c*1024*1024+i]-mean[c])/std[c]
        return out
    }
    /** OpenCV-style ellipse (11x11 at radius 5, as the comparison), neutral border values.
     *  Row prefix counts give the same result as the per-pixel ellipse scan in O(N*(2r+1)). */
    fun trimap(mask:LegendaryMath.Frame,cancel:Cancel):FloatArray=trimap(mask,trimapRadius(mask.w,mask.h),cancel)
    fun trimap(mask:LegendaryMath.Frame,radius:Int,cancel:Cancel):FloatArray {
        require(mask.channels==1 && radius>=1);val w=mask.w;val h=mask.h;val out=FloatArray(mask.size)
        val rr=radius.toDouble()
        val radii=IntArray(2*radius+1){i->round(rr*sqrt(max(0.0,1-(i-rr).pow(2)/(rr*rr)))).toInt()}
        val prefix=IntArray((w+1)*h)
        for(y in 0 until h){if(y and 31==0)cancel.check();val p=y*(w+1);val m=y*w
            for(x in 0 until w)prefix[p+x+1]=prefix[p+x]+(if(mask.data[m+x]>.5f)1 else 0)}
        for(y in 0 until h){cancel.check();for(x in 0 until w){
            var inside=0;var cells=0
            for(dy in -radius..radius){val yy=y+dy;if(yy !in 0 until h)continue;val r=radii[dy+radius]
                val lo=max(0,x-r);val hi=min(w-1,x+r);val p=yy*(w+1)
                inside+=prefix[p+hi+1]-prefix[p+lo];cells+=hi-lo+1}
            out[y*w+x]=if(inside==cells)1f else if(inside==0)0f else 128f/255f
        }};return out
    }
    fun matteInput(rgb:LegendaryMath.Frame,trimap:FloatArray,cancel:Cancel):LegendaryMath.Frame {
        require(rgb.channels==3 && trimap.size==rgb.size)
        val w=(rgb.w+31)/32*32;val h=(rgb.h+31)/32*32;val out=FloatArray(w*h*4)
        for(y in 0 until rgb.h){cancel.check();for(x in 0 until rgb.w){val i=y*rgb.w+x;val j=y*w+x
            for(c in 0..2)out[c*w*h+j]=(rgb.data[c*rgb.size+i]-.5f)/.5f
            out[3*w*h+j]=trimap[i]
        }}
        return LegendaryMath.Frame(w,h,4,out)
    }
    fun unpad(alpha:LegendaryMath.Frame,w:Int,h:Int,cancel:Cancel):LegendaryMath.Frame {
        require(alpha.channels==1 && alpha.w>=w && alpha.h>=h)
        val out=FloatArray(w*h);for(y in 0 until h){cancel.check();for(x in 0 until w){val v=alpha.data[y*alpha.w+x];require(v.isFinite());out[y*w+x]=v.coerceIn(0f,1f)}}
        return LegendaryMath.Frame(w,h,1,out)
    }
    /** Zero defocus in the opaque subject, before both ARNet and optical highlight scattering. */
    fun protectedCoc(coc:LegendaryMath.Frame,alpha:LegendaryMath.Frame,cancel:Cancel):LegendaryMath.Frame {
        require(coc.channels==1 && alpha.channels==1);val a=LegendaryMath.resize(alpha,coc.w,coc.h,cancel=cancel)
        return LegendaryMath.Frame(coc.w,coc.h,1,FloatArray(coc.size){i->if(i%coc.w==0)cancel.check();coc.data[i]*(1-a.data[i].coerceIn(0f,1f))})
    }
    /** Final occlusion safeguard. Opaque/background endpoints stay byte exact;
     * mixed edges use conservative background-delta recomposition where supported. */
    fun composite(source:Planes,blurred:Planes,alpha:LegendaryMath.Frame,cancel:Cancel):Planes {
        require(source.width==blurred.width && source.height==blurred.height)
        val a=LegendaryMath.resize(alpha,source.width,source.height,cancel=cancel)
        // 2.1.19: strip the sharp background / sharpening-halo residue from the mixed band first,
        // so it is not carried into the blur as a crisp rim. Endpoints stay byte exact.
        val sharp=EdgeDecontamination.clean(source,a,cancel);val out=Planes(sharp.width,sharp.height)
        for(y in 0 until sharp.height){cancel.check();for(i in y*sharp.width until (y+1)*sharp.width){val t=a.data[i].coerceIn(0f,1f)
            for(c in 0..2){val s=sharp.plane(c)[i].toInt() and 255;val b=blurred.plane(c)[i].toInt() and 255
                out.plane(c)[i]=Math.rint((s*t+b*(1-t)).toDouble()).toInt().toByte()
            }
        }};return GradientSubjectEdges.refine(sharp,blurred,a,NaturalSubjectEdges.refine(sharp,blurred,a,out,cancel),cancel)
    }
    fun overlay(alpha:LegendaryMath.Frame,w:Int,h:Int,cancel:Cancel):IntArray {
        val a=LegendaryMath.resize(alpha,w,h,cancel=cancel)
        return IntArray(w*h){i->if(i%w==0)cancel.check();((a.data[i]*112).roundToInt().coerceIn(0,112) shl 24) or 0x38c8f4}
    }
}
