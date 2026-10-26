package xyz.shuncongci.leica

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.Window

/** Display only. Never used as an export pixel source, never fabricates or reuses a source gainmap on edited RGB. */
object HdrPreview {
    /** Small display decode only; the full-resolution render/export readers remain separate. */
    fun load(context:Context,uri:Uri,expectHdr:Boolean=false):Bitmap {
        val info=PhotoPixels.inspect(context,uri)
        var sample=1
        while(maxOf(info.width,info.height)/(sample*2)>=LeicaSizes.PREVIEW_MAX_SIDE)sample*=2
        val bitmap=PhotoPixels.decode(context,uri,sample,info)
        if(expectHdr && !hasGainmap(bitmap)){bitmap.recycle();error("HDR预览缺少增益图，未用SDR图代替")}
        return orient(bitmap,info.orientation)
    }
    /** Consumes the owned display bitmap; identity orientation does not make an extra copy. */
    fun orient(source:Bitmap,exif:Int):Bitmap {
        if(exif !in 2..8)return source
        val m=Matrix().apply{when(exif){
            2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
            5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
            7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
        }}
        try {
            val output=Bitmap.createBitmap(source,0,0,source.width,source.height,m,true)
            try{requireRetained(source,output)}catch(t:Throwable){if(output!==source)output.recycle();throw t}
            if(output!==source)source.recycle()
            return output
        }catch(t:Throwable){if(!source.isRecycled)source.recycle();throw t}
    }
    fun hasGainmap(bitmap:Bitmap?)=Build.VERSION.SDK_INT>=34 && bitmap!=null && !bitmap.isRecycled && bitmap.hasGainmap()
    fun requireRetained(source:Bitmap,transformed:Bitmap){
        check(!hasGainmap(source) || hasGainmap(transformed)){"系统几何变换丢失HDR增益图，未将SDR预览冒充HDR"}
    }
    fun copy(source:Bitmap):Bitmap {
        val copy=checkNotNull(source.copy(Bitmap.Config.ARGB_8888,false))
        try{requireRetained(source,copy);return copy}catch(t:Throwable){copy.recycle();throw t}
    }
    /** Consumes an owned small display bitmap. The export crop still uses native source coordinates. */
    fun crop(source:Bitmap,crop:CompositionGeometry.Crop):Bitmap {
        try {
            val box=CompositionGeometry.pixels(crop,source.width,source.height)
            val output=Bitmap.createBitmap(source,box.left,box.top,box.w,box.h)
            try{requireRetained(source,output)}catch(t:Throwable){if(output!==source)output.recycle();throw t}
            if(output!==source)source.recycle()
            return output
        }catch(t:Throwable){if(!source.isRecycled)source.recycle();throw t}
    }
    // Follow Android's Ultra HDR display contract; do not keep the whole Activity permanently in HDR.
    fun configure(window:Window,hdr:Boolean){
        val color=if(Build.VERSION.SDK_INT>=34 && hdr)ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        if(window.colorMode!=color)window.colorMode=color
    }
    fun label(view:View):String =
        if(view.display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty()==true)"HDR预览" else "HDR内容 · 当前屏幕按SDR显示"
}
