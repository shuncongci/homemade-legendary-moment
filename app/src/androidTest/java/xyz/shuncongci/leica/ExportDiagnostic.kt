package xyz.shuncongci.leica

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Test-APK-only codec smoke checks. Never writes to the gallery or changes app preferences. */
object ExportDiagnostic {
    fun run(context:Context):File {
        val dir=File(context.filesDir,"export-diagnostic-${UUID.randomUUID()}");check(dir.mkdirs())
        val cases=JSONArray();val w=257;val h=193
        fun pixels(b:NativeImageMath.Box)=IntArray(b.w*b.h){i->
            val x=b.left+i%b.w;val y=b.top+i/b.w
            (255 shl 24) or (((x*3+y) and 255) shl 16) or (((y*2+x/3) and 255) shl 8) or ((x+y*2) and 255)
        }
        for(format in ExportFormat.available(Build.VERSION.SDK_INT)){
            for(size in listOf(w to h,131 to 97)){
                val bytes=ByteArrayOutputStream()
                PhotoExport.write(context,bytes,w,h,CompositionGeometry.FULL,size.first,size.second,format,Cancel(),::pixels)
                val encoded=bytes.toByteArray();val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(encoded,0,encoded.size,options)
                check(options.outWidth==size.first && options.outHeight==size.second && options.outMimeType==format.mime){"Codec MIME/dimensions mismatch"}
                val decoded=checkNotNull(BitmapFactory.decodeByteArray(encoded,0,encoded.size))
                try{
                    if(format.lossless){
                        CompositionExport.rows(w,h,CompositionGeometry.FULL,size.first,size.second,Cancel(),::pixels){y,p,offset->
                            val row=IntArray(size.first);decoded.getPixels(row,0,row.size,0,y,row.size,1)
                            for(x in row.indices)check(row[x]==p[offset+x]){"Lossless RGB changed"}
                        }
                    }
                }finally{decoded.recycle()}
                val name="${format.id}-${size.first}x${size.second}.${format.extension}";File(dir,name).writeBytes(encoded)
                cases.put(JSONObject().put("format",format.id).put("file",name).put("bytes",encoded.size).put("mime",options.outMimeType).put("pixelExactChecked",format.lossless))
            }
        }
        // Transparency must never become an implicit white/black background.
        for(format in ExportFormat.available(Build.VERSION.SDK_INT).filter{it!=ExportFormat.PNG}){
            try{PhotoExport.write(context,ByteArrayOutputStream(),2,2,CompositionGeometry.FULL,2,2,format,Cancel()){b->IntArray(b.w*b.h){0x80123456.toInt()}};error("Transparent source accepted")}
            catch(e:IllegalArgumentException){check(e.message?.contains("透明")==true)}
        }
        File(dir,"result.json").writeText(JSONObject().put("status","PASS").put("sdk",Build.VERSION.SDK_INT).put("cases",cases).put("galleryWritten",false).toString(2))
        return dir
    }
}
