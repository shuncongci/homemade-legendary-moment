package xyz.shuncongci.leica

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.FilterOutputStream
import java.io.OutputStream

/** Android codecs require ONE complete Bitmap, filled by bounded source strips. PNG never needs it.
 * No full-resolution IntArray, hidden rescaling, alpha flattening or format fallback. */
object PhotoExport {
    fun write(context:Context,out:OutputStream,sourceW:Int,sourceH:Int,crop:CompositionGeometry.Crop,
              width:Int,height:Int,format:ExportFormat,cancel:Cancel,read:(NativeImageMath.Box)->IntArray) {
        require(format.supports(Build.VERSION.SDK_INT)){"此系统不支持${format.label}，请选择PNG无损"}
        require(format.supportsDimensions(width,height)){"WebP单边最多16383像素，请选JPEG或PNG；不会缩图"}
        if(format==ExportFormat.PNG){CompositionExport.writeSized(out,sourceW,sourceH,crop,width,height,cancel,read);return}
        val box=CompositionGeometry.pixels(crop,sourceW,sourceH)
        require(width in 1..box.w && height in 1..box.h){"不能放大裁剪后的像素"}
        val required=format.estimatedWorkingBytes(width,height)
        val runtime=Runtime.getRuntime();val headroom=(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())).coerceAtLeast(0)
        val system=ActivityManager.MemoryInfo();(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(system)
        check(!system.lowMemory && required<=headroom && required<=system.availMem/2){
            "原尺寸${format.label}编码需要较多内存，请选「原图 PNG · 无损」；已有成片保留，不会自动缩图"
        }
        cancel.check()
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            CompositionExport.rows(sourceW,sourceH,crop,width,height,cancel,read){y,p,offset->
                // Bitmap is premultiplied internally: reject transparency rather than claiming exact hidden RGB.
                for(x in 0 until width)require(p[offset+x] ushr 24==255){"检测到透明像素，请选PNG无损以保留透明与颜色；未自动填背景"}
                bitmap.setPixels(p,offset,width,0,y,width,1)
            }
            bitmap.setHasAlpha(false);cancel.check()
            val codec=codec(format)
            val checked=object:FilterOutputStream(out){
                override fun write(b:Int){cancel.check();out.write(b)}
                override fun write(b:ByteArray,off:Int,len:Int){cancel.check();out.write(b,off,len)}
            }
            val ok=bitmap.compress(codec,format.quality,checked)
            cancel.check();check(ok){"${format.label}编码失败，未保存半成品；可改选PNG无损"}
            checked.flush()
        }finally{bitmap.recycle()}
    }
    @Suppress("DEPRECATION")
    private fun codec(format:ExportFormat):Bitmap.CompressFormat=when(format){
        ExportFormat.JPEG->Bitmap.CompressFormat.JPEG
        ExportFormat.WEBP_SMALL,ExportFormat.WEBP_HIGH->if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        ExportFormat.WEBP_LOSSLESS->{check(Build.VERSION.SDK_INT>=30);Bitmap.CompressFormat.WEBP_LOSSLESS}
        ExportFormat.PNG->error("PNG使用流式无损编码器")
    }
}

object ExportPreferences {
    private const val FILE="legendary-export"
    private const val KEY="format-id"
    fun load(context:Context)=ExportFormat.fromId(context.getSharedPreferences(FILE,Context.MODE_PRIVATE).getString(KEY,null),Build.VERSION.SDK_INT)
    fun save(context:Context,format:ExportFormat){require(format.supports(Build.VERSION.SDK_INT));context.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().putString(KEY,format.id).apply()}
    /** Default off. Does not overwrite the remembered SDR format; while on, SDR export uses JPEG. */
    fun badge(context:Context)=context.getSharedPreferences(FILE,Context.MODE_PRIVATE).getBoolean("leica-badge",false)
    fun saveBadge(context:Context,on:Boolean){context.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit().putBoolean("leica-badge",on).apply()}
}
