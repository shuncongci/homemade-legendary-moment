package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

/** Original-pixel reader for compare and composition. Ordinary formats keep region decoding.
 * Formats without a region codec use one checked full bitmap, released on close. No thumbnail fallback.
 * Full decoding can require more memory; OOM is never retried at a lower resolution.
 */
class RegionPhoto(private val context:Context,private val uri:Uri):AutoCloseable {
    private val info=PhotoPixels.inspect(context,uri)
    private var input:InputStream?=null
    private var decoder:BitmapRegionDecoder?=null
    private var whole:Bitmap?=null
    private var closed=false
    val orientation=info.orientation
    val width=info.displaySize.first
    val height=info.displaySize.second
    init {
        // A DNG region decoder must not be allowed to choose a different embedded image.
        if(info.dng==null){
            val stream=context.contentResolver.openInputStream(uri)?:error("无法读取用户原图")
            input=stream
            try {
                @Suppress("DEPRECATION")
                val region=try{BitmapRegionDecoder.newInstance(stream,false)}catch(e:Exception){
                    propagateFatalCause(e)
                    if(e !is IOException && e !is IllegalArgumentException)throw e
                    null
                }
                decoder=region
                if(region!=null)PhotoPixelContract.decoded(info.width,info.height,1,region.width,region.height)
                else {input=null;stream.close()}
            }catch(t:Throwable){decoder?.recycle();decoder=null;input=null;stream.close();throw t}
        }
    }
    private fun full():Bitmap=whole?:PhotoPixels.decode(context,uri,1,info).also{whole=it}
    private fun matrix()=Matrix().apply { when(orientation) {
        2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
        5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
        7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
    } }
    @Synchronized fun bitmap(b:NativeImageMath.Box,sample:Int=1):Bitmap {
        check(!closed){"原图读取器已关闭"}
        require(b.left>=0 && b.top>=0 && b.right<=width && b.bottom<=height && b.w>0 && b.h>0){"原图区域超出边界"}
        require(sample>0 && sample and (sample-1)==0){"照片采样参数无效"}
        val e=NativeImageMath.encodedBox(b,info.width,info.height,orientation)
        val region=decoder
        val bmp=if(region!=null){
            val opts=BitmapFactory.Options().apply{inSampleSize=sample;inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888}
            region.decodeRegion(Rect(e.left,e.top,e.right,e.bottom),opts)?:error("读取原图区域失败")
        }else{
            val source=full()
            val scale=Matrix().apply{setScale(maxOf(1,e.w/sample).toFloat()/e.w,maxOf(1,e.h/sample).toFloat()/e.h)}
            val part=Bitmap.createBitmap(source,e.left,e.top,e.w,e.h,scale,sample>1)
            // createBitmap may alias a whole-image identity crop. Callers always own/recycle the returned bitmap.
            if(part===source)source.copy(Bitmap.Config.ARGB_8888,false)?:error("无法复制原像素") else part
        }
        try {
            PhotoPixelContract.decoded(e.w,e.h,sample,bmp.width,bmp.height)
            return if(orientation in 2..8)Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,matrix(),false).also{if(it!==bmp)bmp.recycle()} else bmp
        }catch(t:Throwable){bmp.recycle();throw t}
    }
    fun planes(b:NativeImageMath.Box,sample:Int=1):Planes {
        val bmp=bitmap(b,sample)
        try {val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width);for(y in 0 until bmp.height){bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)};return p}
        finally{bmp.recycle()}
    }
    fun analysis(maxSide:Int):Planes {
        require(maxSide>0)
        var sample=1
        while(max(width,height)/sample>maxSide)sample*=2
        return planes(NativeImageMath.Box(0,0,width,height),sample)
    }
    @Synchronized override fun close(){
        if(closed)return;closed=true
        try{decoder?.recycle();whole?.recycle()}finally{decoder=null;whole=null;input?.close();input=null}
    }
}
