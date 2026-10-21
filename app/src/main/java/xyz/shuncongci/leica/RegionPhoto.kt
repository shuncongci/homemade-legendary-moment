package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.InputStream
import kotlin.math.max

/** Viewport-only reader for 1:1 compare preview. Never used by migration or bokeh. */
class RegionPhoto(context:Context,uri:Uri):AutoCloseable {
    private val input:InputStream=context.contentResolver.openInputStream(uri) ?: error("无法读取用户原图")
    @Suppress("DEPRECATION")
    private val decoder=try { BitmapRegionDecoder.newInstance(input,false) ?: error("无法分块解码这张照片") } catch(e:Exception){input.close();throw IllegalArgumentException("此照片不支持原尺寸分块解码，请转为JPEG/PNG；不会降低分辨率保存",e)}
    val orientation=context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) } ?: 1
    val width=NativeImageMath.size(decoder.width,decoder.height,orientation).first
    val height=NativeImageMath.size(decoder.width,decoder.height,orientation).second
    private fun matrix()=Matrix().apply { when(orientation) {
        2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
        5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
        7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
    } }
    @Synchronized fun bitmap(b:NativeImageMath.Box,sample:Int=1):Bitmap {
        val e=NativeImageMath.encodedBox(b,decoder.width,decoder.height,orientation)
        val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val bmp=decoder.decodeRegion(Rect(e.left,e.top,e.right,e.bottom),opts) ?: error("读取原图区域失败")
        return if(orientation in 2..8)Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,matrix(),false).also{if(it!==bmp)bmp.recycle()} else bmp
    }
    fun planes(b:NativeImageMath.Box,sample:Int=1):Planes {
        val bmp=bitmap(b,sample)
        try {val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width);for(y in 0 until bmp.height){bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)};return p}
        finally{bmp.recycle()}
    }
    fun analysis(maxSide:Int):Planes {
        var sample=1
        while(max(width,height)/sample>maxSide)sample*=2
        return planes(NativeImageMath.Box(0,0,width,height),sample)
    }
    @Synchronized override fun close(){decoder.recycle();input.close()}
}

