package xyz.shuncongci.leica

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Source/geometry-only export. Edited photos must use HdrPairExport's recomputed map instead. */
object HdrPhotoExport {
    fun saveSource(context:Context,uri:Uri,crop:CompositionGeometry.Crop,cancel:Cancel,
                   outputSize:Pair<Int,Int>,contentEdited:Boolean=false,badge:Boolean=false,metadataSource:File?=null):String {
        HdrExportPolicy.unavailable(Build.VERSION.SDK_INT,contentEdited)?.let{error(it)}
        check(Build.VERSION.SDK_INT>=34)
        return HdrPhotoExport34.save(context,uri,crop,cancel,outputSize,badge,metadataSource)
    }
}

@RequiresApi(34)
internal object HdrPhotoExport34 {
    private fun input(context:Context,uri:Uri):InputStream = context.contentResolver.openInputStream(uri)?:error("无法打开HDR来源")
    internal fun metadata(bitmap:Bitmap):HdrExportPolicy.Metadata {
        check(bitmap.hasGainmap()){"来源或几何处理结果没有可用的HDR增益图；未生成伪HDR"}
        val map=checkNotNull(bitmap.gainmap);val image=map.gainmapContents
        return HdrExportPolicy.Metadata(bitmap.width,bitmap.height,image.width,image.height,
            map.ratioMin.toList()+map.ratioMax.toList()+map.gamma.toList()+map.epsilonSdr.toList()+map.epsilonHdr.toList()+
                listOf(map.minDisplayRatioForHdrTransition,map.displayRatioForFullHdr))
    }
    private fun orientation(context:Context,uri:Uri)=input(context,uri).use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}
    private fun matrix(orientation:Int)=Matrix().apply{when(orientation){
        2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
        5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
        7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
    }}
    private fun decode(context:Context,uri:Uri):Bitmap = input(context,uri).use{
        BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1})
    }?:error("无法解码HDR照片")

    /** Borrowed input; returned bitmap is owned by caller, and may alias input. Never attach an old map. */
    internal fun geometry(source:Bitmap,exif:Int,crop:CompositionGeometry.Crop,size:Pair<Int,Int>,cancel:Cancel):Bitmap {
        metadata(source);var current=source
        fun replace(next:Bitmap){
            if(next===current)return
            try { metadata(next) } catch(t:Throwable){next.recycle();throw t}
            if(current!==source)current.recycle();current=next
        }
        try {
            cancel.check()
            if(exif in 2..8)replace(Bitmap.createBitmap(current,0,0,current.width,current.height,matrix(exif),true))
            val box=CompositionGeometry.pixels(crop,current.width,current.height)
            HdrExportPolicy.checkSize(box.w,box.h,size.first,size.second)
            cancel.check();replace(Bitmap.createBitmap(current,box.left,box.top,box.w,box.h))
            cancel.check()
            if(current.width!=size.first || current.height!=size.second)replace(Bitmap.createScaledBitmap(current,size.first,size.second,true))
            cancel.check();return current
        }catch(t:Throwable){if(current!==source)current.recycle();throw t}
    }
    private fun encode(context:Context,uri:Uri,crop:CompositionGeometry.Crop,size:Pair<Int,Int>,cancel:Cancel,target:File):HdrExportPolicy.Metadata {
        // Try the requested image size directly. Allocation failure must not become SDR fallback.
        val exif=orientation(context,uri);cancel.check()
        val source=decode(context,uri)
        try {
            require(!source.hasAlpha()){"HDR JPEG不支持透明来源"}
            val output=geometry(source,exif,crop,size,cancel)
            try {
                val expected=metadata(output);cancel.check()
                target.outputStream().use{check(output.compress(Bitmap.CompressFormat.JPEG,95,it)){"Ultra HDR JPEG编码失败"}}
                cancel.check();return expected
            }finally{if(output!==source)output.recycle()}
        }finally{source.recycle()}
    }
    internal fun verify(context:Context,uri:Uri,expected:HdrExportPolicy.Metadata,cancel:Cancel) {
        cancel.check();val readback=decode(context,uri)
        try{HdrExportPolicy.checkRoundTrip(expected,metadata(readback));cancel.check()}finally{readback.recycle()}
    }
    fun save(context:Context,uri:Uri,crop:CompositionGeometry.Crop,cancel:Cancel,size:Pair<Int,Int>,badge:Boolean=false,metadataSource:File?=null):String {
        val encoded=File.createTempFile("hdr-export-",".jpg",context.cacheDir)
        var temporary=encoded
        try {
            // Encode before creating a MediaStore row; normal save does not decode a readback.
            try{encode(context,uri,crop,size,cancel,temporary)}catch(t:Throwable){
                if(!HdrAutoPolicy.canFallback(t))throw t
                cancel.check();throw HdrUnsupportedException(t)
            }
            if(metadataSource!=null)temporary=EditorExportMetadata.preserve(context,encoded,metadataSource,size,badge,cancel,outputSrgb=false)
            else if(badge)temporary=LeicaBadgeExport.marked(context,encoded,size.first,size.second,cancel)
            val cr=context.contentResolver;val name="传奇一瞬-HDR-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg"
            val values=ContentValues().apply{
                put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/传奇一瞬");put(MediaStore.Images.Media.IS_PENDING,1)
            }
            ExportCommit.run(cancel,
                create={cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建HDR相册文件")},
                write={pending->
                    (cr.openOutputStream(pending)?:error("无法写入HDR相册文件")).use{out->temporary.inputStream().use{stream->
                        val buffer=ByteArray(64*1024)
                        while(true){cancel.check();val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n)}
                    }}
                    cancel.check() // Full codec readback is a diagnostic, not a production save step.
                },
                publish={pending->values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);check(cr.update(pending,values,null,null)>0){"HDR相册发布失败"}},
                rollback={pending->check(cr.delete(pending,null,null)>0){"HDR半成品清理失败"}})
            return "Pictures/传奇一瞬/$name"
        }finally{temporary.delete();encoded.delete()}
    }
}
