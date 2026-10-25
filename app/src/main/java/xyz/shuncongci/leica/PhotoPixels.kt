package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream

/** Shared, checked source pixels. This is a system decoder adapter, NOT a RAW development engine. */
internal object PhotoPixels {
    data class Info(val width:Int,val height:Int,val orientation:Int,val dng:DngDimensions.Primary?) {
        val displaySize get()=NativeImageMath.size(width,height,orientation)
    }
    private fun open(context:Context,uri:Uri):InputStream =
        context.contentResolver.openInputStream(uri)?:throw IllegalArgumentException("无法打开这张照片")

    fun inspect(context:Context,uri:Uri):Info {
        val dng=DngDimensions.read{open(context,uri)}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        open(context,uri).use{BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0){"系统不支持解码此照片；RAW请先输出全尺寸JPEG/PNG，不会使用缩略图代替"}
        dng?.requireFull(bounds.outWidth,bounds.outHeight)
        val orientation=try{open(context,uri).use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}}
            catch(e:Exception){propagateFatalCause(e);1}
        return Info(bounds.outWidth,bounds.outHeight,orientation,dng)
    }

    fun decode(context:Context,uri:Uri,sample:Int,info:Info=inspect(context,uri)):Bitmap {
        require(sample>0 && sample and (sample-1)==0){"照片采样参数无效"}
        // DNG codecs may select an embedded JPEG at small sample sizes. Always validate full pixels first.
        val actualSample=if(info.dng!=null)1 else sample
        val options=BitmapFactory.Options().apply{inSampleSize=actualSample;inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888}
        var bitmap=open(context,uri).use{BitmapFactory.decodeStream(it,null,options)}
            ?:throw IllegalArgumentException("系统无法读取完整照片像素；RAW请先输出全尺寸JPEG/PNG，不会改用内嵌预览")
        try {
            PhotoPixelContract.decoded(info.width,info.height,actualSample,bitmap.width,bitmap.height)
            if(info.dng!=null){
                info.dng.requireFull(bitmap.width,bitmap.height)
                if(sample>1){
                    val reduced=Bitmap.createScaledBitmap(bitmap,maxOf(1,bitmap.width/sample),maxOf(1,bitmap.height/sample),true)
                    if(reduced!==bitmap)bitmap.recycle();bitmap=reduced
                }
            }
            return bitmap
        }catch(t:Throwable){bitmap.recycle();throw t}
    }
}
