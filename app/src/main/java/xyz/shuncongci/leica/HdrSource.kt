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
        // Bitmap metadata is immutable during this owned decode. Avoid JNI getters per channel/tap.
        private val rowBytes=bitmap.rowBytes
        private val pixelStride=when(bitmap.config){
            Bitmap.Config.ALPHA_8->1
            Bitmap.Config.ARGB_8888->4
            else->error("不支持的增益/主图像素布局，未猜测解码")
        }
        val bytes=HdrPixelBuffer(scratch,bitmap.byteCount){bitmap.copyPixelsToBuffer(it)}
        override fun close(){bytes.close()}
        fun code(x:Int,y:Int,c:Int):Int =
            bytes.get(y*rowBytes+x*pixelStride+if(pixelStride==1)0 else c).toInt()and 255
        fun value(x:Int,y:Int,c:Int)=code(x,y,c)/255f
    }
    /** Called by device diagnostics too: image owns the gainmap, neither is mutated. */
    fun bitmap(source:Bitmap,exif:Int,w:Int,h:Int,cancel:Cancel,scratch:File):Pairing {
        val made=withInput(source,cancel,scratch){HdrReconstruction.render(it,exif,w,h,null,true,cancel)}
        return Pairing(checkNotNull(made.sdr),made.hdr)
    }
    private fun <T> withInput(source:Bitmap,cancel:Cancel,scratch:File,render:(HdrReconstruction.Input)->T):T {
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
        val eotf=cs.eotf
        val input=HdrReconstruction.Input(source.width,source.height,gain.bitmap.width,gain.bitmap.height,
            raw::code,gain::code,FloatArray(256){eotf.applyAsDouble((it/255f).toDouble()).toFloat()},
            map.ratioMin,map.ratioMax,map.gamma,map.epsilonSdr,map.epsilonHdr,isP3)
        return render(input)
        }}
    }
    fun decode(context:Context,uri:Uri,w:Int,h:Int,cancel:Cancel):Pairing {
        checkInput(context,uri,w,h);cancel.check()
        val orientation=context.contentResolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
        val bitmap=context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1})}?:error("HDR来源解码失败")
        try{return bitmap(bitmap,orientation,w,h,cancel,context.cacheDir)}finally{bitmap.recycle()}
    }
    /** Shared production/Android diagnostic entry; bitmap and its gain map remain immutable. */
    internal fun bitmapForColor(source:Bitmap,exif:Int,w:Int,h:Int,colored:Planes?,cancel:Cancel,scratch:File):Frame =
        withInput(source,cancel,scratch){HdrReconstruction.render(it,exif,w,h,colored,false,cancel)}.hdr
    /** Production: no full-size SDR FloatArray. Apply transfer only AFTER native-radiance area integration. */
    fun decodeForColor(context:Context,uri:Uri,w:Int,h:Int,colored:Planes?,cancel:Cancel):Frame {
        checkInput(context,uri,w,h);cancel.check()
        val orientation=context.contentResolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
        val bitmap=context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1})}?:error("HDR来源解码失败")
        try{return bitmapForColor(bitmap,orientation,w,h,colored,cancel,context.cacheDir)}finally{bitmap.recycle()}
    }
    /** Production color-only path: native pixels -> exact Float32 rows -> lossless checkpoint file. */
    internal fun decodeForColorToFile(context:Context,uri:Uri,w:Int,h:Int,colored:Planes?,target:File,cancel:Cancel){
        checkInput(context,uri,w,h);cancel.check()
        val orientation=context.contentResolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
        val bitmap=context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1})}?:error("HDR来源解码失败")
        try{withInput(bitmap,cancel,context.cacheDir){input->
            HdrFloatFile.write(target,w,h,cancel){write->
                HdrReconstruction.renderRows(input,orientation,w,h,colored,false,cancel){y,hdr,_->write(y,hdr)}
            }
        }}finally{bitmap.recycle()}
    }

}
