package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Device-only synthetic codec checks; no gallery writes, model inference or private source access. */
object RawImportDiagnostic {
    fun run(context:Context):File {
        val dir=File(context.filesDir,"raw-import-diagnostic-${UUID.randomUUID()}");check(dir.mkdirs())
        var checks=0
        fun ok(value:Boolean,message:String){check(value){message};checks++}
        val gif=File(dir,"fallback.image").apply{writeBytes(Base64.decode("R0lGODdhCAAGAIEAAAAAAP///wAAAAAAACwAAAAACAAGAAAIFQABBBBIMMDAgwQLFkSIUOFBhgQDAgA7",Base64.DEFAULT))}
        @Suppress("DEPRECATION")
        val nativeGif=try{gif.inputStream().use{BitmapRegionDecoder.newInstance(it,false)}}catch(_:java.io.IOException){null}
        nativeGif?.recycle();ok(nativeGif==null,"GIF must exercise the whole-image fallback on this device")
        val pattern=Bitmap.createBitmap(8,6,Bitmap.Config.ARGB_8888)
        for(y in 0..5)for(x in 0..7)pattern.setPixel(x,y,0xff000000.toInt() or ((x*31) shl 16) or ((y*47) shl 8) or ((x+y)*17))
        val png=File(dir,"ordinary.image");png.outputStream().use{ok(pattern.compress(Bitmap.CompressFormat.PNG,100,it),"PNG encode")}
        val files=mutableListOf(gif,png)
        for(o in 1..8){val f=File(dir,"orientation-$o.jpg");f.outputStream().use{ok(pattern.compress(Bitmap.CompressFormat.JPEG,100,it),"JPEG encode")}
            ExifInterface(f.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,o.toString());saveAttributes()};files+=f
        }
        pattern.recycle()
        for(file in files){
            val o=if(file.name.startsWith("orientation-"))file.name.substringAfter('-').substringBefore('.').toInt()else 1
            val reference=checkNotNull(BitmapFactory.decodeFile(file.path));val w=if(o>=5)reference.height else reference.width;val h=if(o>=5)reference.width else reference.height
            fun expected(x:Int,y:Int):Int {
                val (sx,sy)=when(o){2->reference.width-1-x to y;3->reference.width-1-x to reference.height-1-y;4->x to reference.height-1-y;5->y to x;6->y to reference.height-1-x;7->reference.width-1-y to reference.height-1-x;8->reference.width-1-y to x;else->x to y}
                return reference.getPixel(sx,sy)
            }
            val source=RegionPhoto(context,Uri.fromFile(file))
            try {
                ok(source.width==w && source.height==h,"oriented dimensions ${file.name}")
                for(box in listOf(NativeImageMath.Box(0,0,w,h),NativeImageMath.Box(1,1,w-1,h-1),NativeImageMath.Box(0,0,w,h))){
                    val b=source.bitmap(box)
                    try{ok(b.width==box.w && b.height==box.h,"crop dimensions");for(y in 0 until b.height)for(x in 0 until b.width)ok(b.getPixel(x,y)==expected(box.left+x,box.top+y),"original pixels ${file.name} ($x,$y)")}
                    finally{b.recycle()}
                }
                val sampled=source.bitmap(NativeImageMath.Box(0,0,w,h),2)
                try{ok(sampled.width>=w/2 && sampled.height>=h/2,"sampled dimensions")}finally{sampled.recycle()}
                val output=File(dir,"${file.name}.export.png")
                output.outputStream().use{out->PhotoExport.write(context,out,w,h,CompositionGeometry.FULL,w,h,ExportFormat.entries.first{it.extension=="png"},Cancel()){box->
                    val b=source.bitmap(box,1);try{IntArray(box.w*box.h).also{b.getPixels(it,0,box.w,0,0,box.w,box.h)}}finally{b.recycle()}
                }}
                val saved=checkNotNull(BitmapFactory.decodeFile(output.path))
                try{ok(saved.width==w && saved.height==h,"export dimensions");for(y in 0 until h)for(x in 0 until w)ok(saved.getPixel(x,y)==expected(x,y),"export pixels ${file.name}")}
                finally{saved.recycle()}
            }finally{source.close();source.close();reference.recycle()}
            val closed=try{source.bitmap(NativeImageMath.Box(0,0,1,1)).recycle();false}catch(_:IllegalStateException){true}
            ok(closed,"closed reader must reject reads")
        }
        File(dir,"result.json").writeText(JSONObject().put("status","PASS_SYNTHETIC_ANDROID_CODECS").put("checks",checks).put("rawCameraSamplesTested",false).put("uiRecoveryTested",false).put("note","GIF full fallback, PNG region, JPEG EXIF 1..8, native crops, returned-bitmap ownership and lossless PNG export; not a real DNG/RAW quality test").toString(2))
        return dir
    }
}
