package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Linear-light, display-referred HDR. SDR-trained networks guide the look, never receive super-white inputs. */
object BaselineHdrMath {
    const val EPSILON=1f/64f
    fun linear(v:Float):Float = if(v<=.04045f)v/12.92f else ((v+.055f)/1.055f).pow(2.4f)
    fun srgb(v:Float):Float = if(v<=.0031308f)12.92f*max(v,0f) else 1.055f*max(v,0f).pow(1/2.4f)-.055f
    fun linear(p:Planes)=Frame(p.width,p.height,3,FloatArray(p.size*3){i->linear((p.plane(i/p.size)[i%p.size].toInt() and 255)/255f)})
    fun checked(f:Frame):Frame {
        require(f.channels==3 && f.data.all{it.isFinite() && it>=0f && it<=65504f}){"HDR线性像素无效或超出安全范围，未截断/降SDR"};return f
    }
    /** Android Gainmap.getGamma is the decoding exponent, not Adobe's reciprocal encoding gamma. */
    fun gain(encoded:Float,minimum:Float,maximum:Float,gamma:Float):Float {
        require(encoded.isFinite() && encoded in 0f..1f && minimum>0 && maximum>=minimum && gamma>0 && listOf(minimum,maximum,gamma).all{it.isFinite()})
        return exp(ln(minimum)+(ln(maximum)-ln(minimum))*encoded.pow(gamma))
    }
    fun recover(base:Float,encoded:Float,minimum:Float,maximum:Float,gamma:Float,epsSdr:Float,epsHdr:Float):Float {
        require(base.isFinite() && base>=0 && epsSdr>=0 && epsHdr>=0)
        return max(0f,(base+epsSdr)*gain(encoded,minimum,maximum,gamma)-epsHdr)
    }
    /** Explicit look policy: regularized per-channel linear exposure from final fused SDR, not inference on HDR. */
    fun color(sourceSdr:Frame,sourceHdr:Frame,colored:Planes,cancel:Cancel):Frame {
        require(sourceSdr.w==colored.width && sourceSdr.h==colored.height && sourceHdr.w==sourceSdr.w && sourceHdr.h==sourceSdr.h)
        checked(sourceSdr);checked(sourceHdr);val target=linear(colored)
        for(i in target.data.indices){if(i%colored.width==0)cancel.check()
            target.data[i]=max(0f,(sourceHdr.data[i]+EPSILON)*(target.data[i]+EPSILON)/(sourceSdr.data[i]+EPSILON)-EPSILON)
        };return checked(target)
    }
    /** Consumes owned sourceHdr; same formula/order as color(), without allocating a third RGB float plane. */
    fun colorInPlace(sourceSdr:Frame,sourceHdr:Frame,colored:Planes,cancel:Cancel):Frame {
        require(sourceSdr.w==colored.width && sourceSdr.h==colored.height && sourceHdr.w==sourceSdr.w && sourceHdr.h==sourceSdr.h)
        require(sourceSdr.data!==sourceHdr.data){"HDR原地调色需要独立的源SDR与HDR"}
        checked(sourceSdr);checked(sourceHdr)
        for(i in sourceHdr.data.indices){if(i%colored.width==0)cancel.check()
            val target=linear((colored.plane(i/colored.size)[i%colored.size].toInt()and 255)/255f)
            sourceHdr.data[i]=max(0f,(sourceHdr.data[i]+EPSILON)*(target+EPSILON)/(sourceSdr.data[i]+EPSILON)-EPSILON)
        };return checked(sourceHdr)
    }
    /** Luminance-preserving desaturation only when P3->sRGB produces a negative channel. No white clipping. */
    fun gamut(r:Float,g:Float,b:Float):FloatArray {
        require(listOf(r,g,b).all{it.isFinite()})
        val y=max(0f,.2126f*r+.7152f*g+.0722f*b);val low=min(r,min(g,b))
        val t=if(low<0f)y/(y-low).coerceAtLeast(1e-12f) else 1f
        return floatArrayOf(max(0f,y+(r-y)*t),max(0f,y+(g-y)*t),max(0f,y+(b-y)*t))
    }
    /** Shared rounded crop, exact area integration in LINEAR HDR (SDR export has its existing encoded-area policy). */
    fun geometry(input:Frame,crop:CompositionGeometry.Crop,w:Int,h:Int,cancel:Cancel):Frame {
        val box=CompositionGeometry.pixels(crop,input.w,input.h);HdrExportPolicy.checkSize(box.w,box.h,w,h)
        if(box.left==0 && box.top==0 && box.w==input.w && box.h==input.h && w==input.w && h==input.h)return input
        val out=Frame(w,h,3,FloatArray(w*h*3));val sx=box.w.toDouble()/w;val sy=box.h.toDouble()/h
        for(c in 0..2)for(y in 0 until h){cancel.check();val t=y*sy;val b=(y+1)*sy
            for(x in 0 until w){val l=x*sx;val r=(x+1)*sx;var sum=0.0
                for(yy in floor(t).toInt() until min(box.h,ceil(b).toInt()))for(xx in floor(l).toInt() until min(box.w,ceil(r).toInt())){
                    val weight=(min(b,yy+1.0)-max(t,yy.toDouble()))*(min(r,xx+1.0)-max(l,xx.toDouble()))
                    sum+=input.data[c*input.size+(box.top+yy)*input.w+box.left+xx]*weight
                };out.data[c*out.size+y*w+x]=(sum/(sx*sy)).toFloat()
            }
        };return checked(out)
    }
    data class Map(val w:Int,val h:Int,val pixels:IntArray,val minimum:Float,val maximum:Float,val display:Float)
    fun encode(base:Planes,hdr:Frame,cancel:Cancel):Map {
        checked(hdr);require(hdr.w==base.width && hdr.h==base.height)
        var lo=0f;var hi=1e-6f;var peak=1.0001f

        for(c in 0..2)for(i in 0 until base.size){if(i%base.width==0)cancel.check();val at=c*base.size+i
            val s=linear((base.plane(c)[i].toInt() and 255)/255f);val h=hdr.data[at]
            val v=ln((h+EPSILON)/(s+EPSILON));lo=min(lo,v);hi=max(hi,v);peak=max(peak,h)
        }
        val pixels=IntArray(base.size){i->
            if(i%base.width==0)cancel.check()
            var p=0xff000000.toInt();for(c in 0..2){
                val s=linear((base.plane(c)[i].toInt()and 255)/255f)
                val value=ln((hdr.data[c*base.size+i]+EPSILON)/(s+EPSILON))
                val v=((value-lo)/(hi-lo)*255).roundToInt().coerceIn(0,255);p=p or(v shl(16-c*8))
            };p
        }
        return Map(base.width,base.height,pixels,exp(lo),exp(hi),max(peak,exp(hi)))
    }
    fun reconstruct(base:Planes,map:Map,cancel:Cancel):Frame {
        require(base.width==map.w && base.height==map.h && map.pixels.size==base.size)
        val out=linear(base)
        for(c in 0..2)for(i in 0 until base.size){if(i%base.width==0)cancel.check()
            out.data[c*base.size+i]=recover(out.data[c*base.size+i],((map.pixels[i] ushr(16-c*8))and 255)/255f,map.minimum,map.maximum,1f,EPSILON,EPSILON)
        };return checked(out)
    }
    data class Error(val maximum:Float,val rmse:Double,val peak:Float,val maxRelativeToPeak:Float,val rmseRelativeToPeak:Double)
    fun error(expected:Frame,actual:Frame):Error {
        require(expected.w==actual.w && expected.h==actual.h);checked(expected);checked(actual)
        var maxError=0f;var sum=0.0;val peak=max(1f,expected.data.maxOrNull()!!)
        for(i in expected.data.indices){val d=abs(expected.data[i]-actual.data[i]);maxError=max(maxError,d);sum+=d.toDouble()*d}
        val rmse=sqrt(sum/expected.data.size);return Error(maxError,rmse,peak,maxError/peak,rmse/peak)
    }
    fun accept(error:Error) {
        require(error.maxRelativeToPeak<=.08f && error.rmseRelativeToPeak<=.015){"HDR编码重建误差超出保护阈值（最大${error.maxRelativeToPeak}，RMSE ${error.rmseRelativeToPeak}），未发布；不会转SDR"}
    }
    fun memoryBytes(sw:Int,sh:Int,w:Int,h:Int):Long {
        require(sw>0 && sh>0 && w>0 && h>0)
        require(sw.toLong()*sh<=Int.MAX_VALUE && w.toLong()*h*3<=Int.MAX_VALUE){"HDR像素过多，未缩图"}
        return sw.toLong()*sh*32+w.toLong()*h*192+64L*1024*1024
    }
}
