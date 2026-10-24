package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.RequiresApi
import java.io.File
import java.io.Closeable
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Reads only immutable imported snapshots. Gain decoding is in the source's LINEAR primaries before conversion. */
@RequiresApi(34)
object HdrSource {
    data class Pairing(val sdr:Frame,val hdr:Frame)
    /** Format/dimension validation only. No estimated heap/native-memory gate. */
    fun checkInput(context:Context,uri:Uri,w:Int,h:Int){
        require(w>0 && h>0 && w.toLong()*h*3<=Int.MAX_VALUE){"HDR工作尺寸无效或像素索引溢出"}
        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,opts)}
        require(opts.outMimeType=="image/jpeg"){"本版HDR输入限SDR基底Ultra HDR JPEG；不支持HEIF/PQ/HLG替代"}
        require(opts.outWidth>0 && opts.outHeight>0){"HDR来源尺寸无效"}
    }
    private class Raw(val bitmap:Bitmap,scratch:File):Closeable {
        val bytes=HdrPixelBuffer(scratch,bitmap.byteCount){bitmap.copyPixelsToBuffer(it)}
        override fun close(){bytes.close()}
        fun value(x:Int,y:Int,c:Int):Float {
            val at=y*bitmap.rowBytes
            return when(bitmap.config){
                Bitmap.Config.ALPHA_8->(bytes.get(at+x).toInt()and 255)/255f
                Bitmap.Config.ARGB_8888->(bytes.get(at+x*4+c).toInt()and 255)/255f
                else->error("不支持的增益/主图像素布局，未猜测解码")
            }
        }
    }
    /** Called by device diagnostics too: image owns the gainmap, neither is mutated. */
    fun bitmap(source:Bitmap,exif:Int,w:Int,h:Int,cancel:Cancel,scratch:File):Pairing {
        cancel.check()
        require(source.config==Bitmap.Config.ARGB_8888 && !source.hasAlpha()){"HDR来源需不透明8位SDR基底"}
        check(source.hasGainmap()){"源照片没有系统可识别的HDR增益图；未合成伪HDR"}
        HdrPhotoExport34.metadata(source)
        val cs=source.colorSpace as? ColorSpace.Rgb?:error("不支持的HDR来源色彩空间")
        val srgb=ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        val p3=ColorSpace.get(ColorSpace.Named.DISPLAY_P3) as ColorSpace.Rgb
        fun near(a:FloatArray,b:FloatArray)=a.size==b.size && a.zip(b).all{(x,y)->abs(x-y)<.002f}
        val isP3=near(cs.primaries,p3.primaries)
        require((near(cs.primaries,srgb.primaries)||isP3) && near(cs.whitePoint,srgb.whitePoint)){"当前HDR仅支持D65 sRGB/Display-P3原色；未知色域不静默猜测"}
        val map=checkNotNull(source.gainmap)
        Raw(source,scratch).use{raw->Raw(map.gainmapContents,scratch).use{gain->
        // copyPixelsToBuffer has raw channels, unlike getPixels' implicit conversion to sRGB. Verify layout.
        val sample=source.getColor(0,0)
        require(abs(sample.red()-raw.value(0,0,0))<.006f && abs(sample.blue()-raw.value(0,0,2))<.006f){"HDR主图原始通道布局校验失败"}
        val low=map.ratioMin;val high=map.ratioMax;val gamma=map.gamma;val es=map.epsilonSdr;val eh=map.epsilonHdr
        val (ow,oh)=NativeImageMath.size(source.width,source.height,exif)
        require(w in 1..ow && h in 1..oh){"HDR工作尺寸不可放大"}
        val s=Frame(w,h,3,FloatArray(w*h*3));val hdr=Frame(w,h,3,FloatArray(w*h*3))
        val sx=ow.toDouble()/w;val sy=oh.toDouble()/h
        fun encodedPosition(x:Int,y:Int):Pair<Int,Int> = when(exif){
            2->source.width-1-x to y;3->source.width-1-x to source.height-1-y;4->x to source.height-1-y
            5->y to x;6->y to source.height-1-x;7->source.width-1-y to source.height-1-x;8->source.width-1-y to x;else->x to y
        }
        fun mapValue(x:Int,y:Int,c:Int):Float {
            val gx=(x+.5)*gain.bitmap.width/source.width-.5;val gy=(y+.5)*gain.bitmap.height/source.height-.5
            val ix=floor(gx).toInt();val iy=floor(gy).toInt();val fx=(gx-ix).toFloat();val fy=(gy-iy).toFloat();var value=0f
            for(dy in 0..1)for(dx in 0..1)value+=gain.value((ix+dx).coerceIn(0,gain.bitmap.width-1),(iy+dy).coerceIn(0,gain.bitmap.height-1),c)*(if(dx==0)1-fx else fx)*(if(dy==0)1-fy else fy)
            return value.coerceIn(0f,1f)
        }
        fun convert(v:FloatArray):FloatArray {
            if(isP3){
                val r=1.2249402f*v[0]-.2249402f*v[1];val g=-.04205696f*v[0]+1.042057f*v[1];val b=-.01963755f*v[0]-.07863605f*v[1]+1.0982736f*v[2]
                val y=max(0f,.2126f*r+.7152f*g+.0722f*b);val low=min(r,min(g,b))
                val t=if(low<0f)y/(y-low).coerceAtLeast(1e-12f)else 1f
                v[0]=max(0f,y+(r-y)*t);v[1]=max(0f,y+(g-y)*t);v[2]=max(0f,y+(b-y)*t)
            };return v
        }
        val a=FloatArray(3);val z=FloatArray(3);val sums=DoubleArray(6)
        // Reconstruct each native pixel first, THEN area-reduce HDR radiance; do not multiply separately resized layers.
        for(y in 0 until h){cancel.check();for(x in 0 until w){val l=x*sx;val r=(x+1)*sx;val t=y*sy;val b=(y+1)*sy
            sums.fill(0.0)
            for(yy in floor(t).toInt() until min(oh,ceil(b).toInt()))for(xx in floor(l).toInt() until min(ow,ceil(r).toInt())){
                val (u,v)=encodedPosition(xx,yy)
                for(c in 0..2){a[c]=cs.eotf.applyAsDouble(raw.value(u,v,c).toDouble()).toFloat()
                    z[c]=HdrMath.recover(a[c],mapValue(u,v,c),low[c],high[c],gamma[c],es[c],eh[c])}
                val aa=convert(a);val zz=convert(z)
                val weight=(min(r,xx+1.0)-max(l,xx.toDouble()))*(min(b,yy+1.0)-max(t,yy.toDouble()))/(sx*sy)
                for(c in 0..2){sums[c]+=aa[c]*weight;sums[c+3]+=zz[c]*weight}
            }
            for(c in 0..2){val at=c*s.size+y*w+x;s.data[at]=sums[c].toFloat();hdr.data[at]=sums[c+3].toFloat()}
        }};return Pairing(HdrMath.checked(s),HdrMath.checked(hdr))
        }}
    }
    fun decode(context:Context,uri:Uri,w:Int,h:Int,cancel:Cancel):Pairing {
        checkInput(context,uri,w,h);cancel.check()
        val orientation=context.contentResolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
        val bitmap=context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1})}?:error("HDR来源解码失败")
        try{return bitmap(bitmap,orientation,w,h,cancel,context.cacheDir)}finally{bitmap.recycle()}
    }
}
