package xyz.shuncongci.leica

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** Dedicated immutable input snapshots: parent result-cache cleanup cannot change the composition source. */
object CompositionFiles {
    private fun base(context:Context)=File(context.noBackupFilesDir,"composition-sources")
    fun stage(context:Context,uri:Uri,check:()->Unit):File {
        val base=base(context)
        base.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>7L*24*3600*1000}?.forEach{it.deleteRecursively()}
        val folder=File(base,UUID.randomUUID().toString())
        return try{PhotoCopy.stage(folder,{context.contentResolver.openInputStream(uri)?:error("无法读取构图来源")},check)}catch(e:Exception){folder.deleteRecursively();throw e}
    }
    fun source(context:Context,path:String):File {
        val file=File(path).canonicalFile;val root=base(context).canonicalFile
        require(file.parentFile?.parentFile==root && file.isFile){"构图来源已失效，请返回并重新打开"};return file
    }
    fun release(context:Context,path:String){runCatching{source(context,path).parentFile?.deleteRecursively()}}
    fun dimensions(context:Context,file:File):Pair<Int,Int> = PhotoPixels.inspect(context,Uri.fromFile(file)).displaySize
    class Loaded(val bitmap:Bitmap,val sourceW:Int,val sourceH:Int,val image:Planes)
    /** onPreview borrows the decoded bitmap; UI consumers must copy it before retaining it. */
    fun load(context:Context,file:File,cancel:Cancel,onPreview:(Bitmap)->Unit={}):Loaded = loadSource(context,Uri.fromFile(file),CompositionGeometry.FULL,cancel,onPreview)
    fun loadSource(context:Context,uri:Uri,crop:CompositionGeometry.Crop,cancel:Cancel,onPreview:(Bitmap)->Unit={}):Loaded {
        RegionPhoto(context,uri).use { source->
            cancel.check();val box=CompositionGeometry.pixels(crop,source.width,source.height)
            val (w,h)=CompositionGeometry.analysisSize(box.w,box.h)
            var bmp=source.bitmap(box,CompositionGeometry.sample(box.w,box.h))
            try {
                if(bmp.width!=w || bmp.height!=h){val scaled=Bitmap.createScaledBitmap(bmp,w,h,true);if(scaled!==bmp)bmp.recycle();bmp=scaled}
                cancel.check();onPreview(bmp);cancel.check()
                val pixels=IntArray(w*h);bmp.getPixels(pixels,0,w,0,0,w,h);cancel.check()
                val image=Planes.fromArgb(pixels,w,h)
                cancel.check();return Loaded(bmp,box.w,box.h,image)
            }catch(e:Exception){bmp.recycle();throw e}
        }
    }
    /** Publishes a new file only. Every format uses the same crop/size and pending transaction. */
    fun save(context:Context,file:File,crop:CompositionGeometry.Crop,cancel:Cancel,outputSize:Pair<Int,Int>?=null,
             format:ExportFormat=ExportPreferences.load(context)):String = saveSource(context,Uri.fromFile(file),crop,cancel,outputSize,format)
    fun saveSource(context:Context,uri:Uri,crop:CompositionGeometry.Crop,cancel:Cancel,outputSize:Pair<Int,Int>?=null,
                   format:ExportFormat=ExportPreferences.load(context)):String {
        val name="传奇一瞬-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.${format.extension}"
        val cr=context.contentResolver
        RegionPhoto(context,uri).use { source->
            val box=CompositionGeometry.pixels(crop,source.width,source.height)
            val (ow,oh)=outputSize?: (box.w to box.h)
            fun encode(output:java.io.OutputStream){PhotoExport.write(context,output,source.width,source.height,crop,ow,oh,format,cancel){b->
                source.bitmap(b,1).let{bitmap->try{check(bitmap.width==b.w && bitmap.height==b.h){"原像素区域尺寸不一致"};IntArray(b.w*b.h).also{bitmap.getPixels(it,0,b.w,0,0,b.w,b.h)}}finally{bitmap.recycle()}}
            }}
            if(Build.VERSION.SDK_INT>=29){
                val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,format.mime);put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/传奇一瞬");put(MediaStore.Images.Media.IS_PENDING,1)}
                ExportCommit.run(cancel,
                    create={cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建相册文件")},
                    write={pending->(cr.openOutputStream(pending)?:error("无法打开相册文件")).use{encode(it)}},
                    publish={pending->values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);check(cr.update(pending,values,null,null)>0){"相册发布失败"}},
                    rollback={pending->check(cr.delete(pending,null,null)>0){"半成品清理失败"}})
                return "Pictures/传奇一瞬/$name"
            }
            // Android 8–9: app-private external Pictures, no new storage permission.
            val dir=context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?:error("存储不可用")
            check(dir.isDirectory || dir.mkdirs());val target=File(dir,name)
            ExportCommit.run(cancel,create={check(target.createNewFile()){"同名文件已存在，未覆盖"};target},
                write={it.outputStream().use{out->encode(out)}},publish={},rollback={check(it.delete()){"半成品清理失败"}})
            return target.absolutePath
        }
    }
}
