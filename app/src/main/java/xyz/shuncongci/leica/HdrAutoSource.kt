package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build

/** Source classification only. Does not reconstruct pixels or run inference. */
object HdrAutoSource {
    fun detect(context:Context,uri:Uri):Boolean {
        if(Build.VERSION.SDK_INT<34)return false
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0){"无法识别来源图片"}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/(sample*2)>=128)sample*=2
        val image=context.contentResolver.openInputStream(uri)!!.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}?:error("无法解码来源图片")
        return try{image.hasGainmap()}finally{image.recycle()}
    }
}

/** Capability fallback only. Allocation failures, cancellation and I/O must never become SDR success. */
object HdrAutoPolicy {
    fun canFallback(t:Throwable):Boolean {
        if(t !is IllegalArgumentException && t !is IllegalStateException)return false
        // A wrapper must not hide an actual OOM, cancellation, or failed I/O.
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var cause:Throwable?=t
        while(cause!=null && seen.add(cause)){
            if(cause is Error || cause is java.util.concurrent.CancellationException || cause is java.io.IOException)return false
            cause=cause.cause
        }
        return true
    }
    fun label(hdr:Boolean,reason:String="")=if(hdr)"HDR · Ultra HDR JPEG" else if(reason.isNotEmpty())"已转 SDR · $reason"else "SDR"
}

/** Only raised before any MediaStore row exists, so automatic SDR retry cannot duplicate a publish. */
class HdrUnsupportedException(cause:Throwable):IllegalStateException(cause.message,cause)
