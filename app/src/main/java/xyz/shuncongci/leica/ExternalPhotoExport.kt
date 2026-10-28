package xyz.shuncongci.leica

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** A separate, journaled destination; normal editor export and its strict HDR policy stay unchanged. */
class ExternalPhotoExport(private val context:Context,private val journal:ExternalQueueStore) {
    private val cr=context.contentResolver
    private data class Destination(val uri:Uri,val pending:Boolean)
    private fun collection()=if(Build.VERSION.SDK_INT>=29)MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private fun find(name:String):List<Destination> {
        if(Build.VERSION.SDK_INT<29)return emptyList()
        require(name.startsWith(ExternalModePolicy.OUTPUT_PREFIX))
        val where="${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=?"
        val args=arrayOf(name,ExternalModePolicy.OUTPUT_FOLDER,context.packageName)
        val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.IS_PENDING)
        val cursor=if(Build.VERSION.SDK_INT>=30)cr.query(collection(),projection,Bundle().apply{
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,where);putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
        },null)else cr.query(collection(),projection,where,args,null)
        return cursor?.use{c->buildList{while(c.moveToNext())add(Destination(ContentUris.withAppendedId(collection(),c.getLong(0)),c.getInt(1)!=0))}}?:error("无法核对外接导出日志，未重复保存")
    }
    /** Reconcile only this app's explicitly journaled names. NEVER rerender old queued inputs. */
    fun reconcile() {
        for(job in journal.unfinishedExports()){
            if(Build.VERSION.SDK_INT>=29){
                val found=find(job.outputName)
                found.filter{it.pending}.forEach{check(cr.delete(it.uri,null,null)>0){"未完成外接文件清理失败"}}
                if(found.any{!it.pending})journal.state(job.id,"DONE","恢复确认：之前已成功发布；未重复处理")
            }else{
                val target=legacyTarget(job.outputName)
                val part=File(target.parentFile,target.name+".part")
                if(part.exists())check(part.delete()){"未完成外接文件清理失败"}
                if(target.isFile)journal.state(job.id,"DONE","恢复确认：之前已完成；未重复处理")
            }
        }
        journal.abandonOldJobs()
    }
    internal fun save(job:ExternalQueueStore.Job,result:LeicaEngine.Result,format:ExportFormat,cancel:Cancel,metadata:ExternalMetadataTransfer.Prepared,work:File):String {
        val hdr=result.hdrReady
        check(!hdr || Build.VERSION.SDK_INT>=34){"HDR平台不支持，未伪造HDR"}
        val name=ExternalModePolicy.outputName(job.id,hdr,format)
        journal.prepareExport(job.id,name)
        fun encodeImage(out:java.io.OutputStream){
            if(hdr){
                val jpeg=HdrResultStore.jpegFile(result.file)
                check(jpeg.isFile && jpeg.length()>0){"HDR成片已失效"}
                jpeg.inputStream().use{input->val b=ByteArray(64*1024);while(true){cancel.check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}}
            }else RegionPhoto(context,Uri.fromFile(result.file)).use{source->
                PhotoExport.write(context,out,source.width,source.height,CompositionGeometry.FULL,result.width,result.height,format,cancel){box->
                    source.bitmap(box,1).let{bitmap->try{check(bitmap.width==box.w && bitmap.height==box.h);IntArray(box.w*box.h).also{bitmap.getPixels(it,0,box.w,0,0,box.w,box.h)}}finally{bitmap.recycle()}}
                }
            }
        }
        check(work.isDirectory || work.mkdirs()){"无法创建元数据导出工作目录"}
        val encoded=File(work,"encoded-image");val decorated=File(work,"metadata-verified-image")
        check(!encoded.exists() && !decorated.exists())
        encoded.outputStream().use{out->encodeImage(out);cancel.check();out.fd.sync()}
        ExternalMetadataTransfer.write(encoded,decorated,metadata,result.width,result.height,cancel)
        val expectedHash=ExternalOriginalGuard.digest(decorated.inputStream()){cancel.check()}
        journal.rememberOutput(job.id,expectedHash)
        // Publication can only see the fully encoded, metadata-readback-verified file.
        fun encode(out:java.io.OutputStream){decorated.inputStream().use{input->val bytes=ByteArray(64*1024);while(true){cancel.check();val n=input.read(bytes);if(n<0)break;out.write(bytes,0,n)}}}
        if(Build.VERSION.SDK_INT>=29){
            val prior=find(name)
            prior.filter{it.pending}.forEach{check(cr.delete(it.uri,null,null)>0)}
            prior.firstOrNull{!it.pending}?.let{destination->
                ExternalOriginalGuard.verifyCopy(expectedHash,cr.openInputStream(destination.uri)?:error("既有成片不可读取")){cancel.check()}
                journal.created(job.id,destination.uri.toString());journal.state(job.id,"DONE","已存在完全相同的发布结果，未重复导出")
                return ExternalModePolicy.OUTPUT_FOLDER+name
            }
            val values=ContentValues().apply{
                put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,if(hdr)"image/jpeg" else format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH,ExternalModePolicy.OUTPUT_FOLDER);put(MediaStore.Images.Media.IS_PENDING,1)
            }
            ExportCommit.run(cancel,
                create={cr.insert(collection(),values)?:error("无法创建外接相册文件")},
                write={pending->
                    journal.created(job.id,pending.toString())
                    (cr.openOutputStream(pending)?:error("无法打开外接文件")).use{encode(it)}
                    ExternalOriginalGuard.verifyCopy(expectedHash,cr.openInputStream(pending)?:error("待发布成片不可回读；原片保留")){cancel.check()}
                },
                publish={pending->
                    check(cr.update(pending,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)>0){"外接成片发布失败"}
                    journal.state(job.id,"DONE",if(hdr)"HDR · Ultra HDR JPEG" else "SDR · ${format.label}")
                },rollback={pending->check(cr.delete(pending,null,null)>0){"外接半成品清理失败，请停止并重新打开外接模式核对"}})
            return ExternalModePolicy.OUTPUT_FOLDER+name
        }
        val target=legacyTarget(name);check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()){"无法建立外接导出目录"}
        val part=File(target.parentFile,target.name+".part")
        check(!target.exists() && !part.exists()){"已存在外接目标，未覆盖文件"}
        ExportCommit.run(cancel,create={check(part.createNewFile());part},
            write={file->
                journal.created(job.id,Uri.fromFile(target).toString());file.outputStream().use{encode(it);it.fd.sync()}
                ExternalOriginalGuard.verifyCopy(expectedHash,file.inputStream()){cancel.check()}
            },
            publish={check(part.renameTo(target)){"无法提交外接成片"};journal.state(job.id,"DONE","SDR · ${format.label}")},
            rollback={file->if(file.exists())check(file.delete());if(target.exists())check(target.delete())})
        MediaScannerConnection.scanFile(context,arrayOf(target.absolutePath),arrayOf(format.mime),null)
        return target.absolutePath
    }
    /** Read-back gate for source retention: an exact owned, non-pending, non-empty published output. */
    fun publishedUri(id:String):Uri {
        val job=journal.original(id)?.job?:error("成片日志不存在，原片保留")
        check(job.state=="DONE" && job.outputName.startsWith(ExternalModePolicy.OUTPUT_PREFIX) && job.outputUri.isNotEmpty()){"成片尚未发布，原片保留"}
        val uri=Uri.parse(job.outputUri)
        if(Build.VERSION.SDK_INT>=29){
            check(uri.scheme=="content" && uri.authority=="media")
            val cols=arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.OWNER_PACKAGE_NAME,MediaStore.Images.Media.IS_PENDING,MediaStore.Images.Media.SIZE)
            cr.query(uri,cols,null,null,null).use{c->
                check(c!=null && c.moveToFirst() && c.getString(0)==job.outputName && c.getString(1)==ExternalModePolicy.OUTPUT_FOLDER && c.getString(2)==context.packageName && c.getInt(3)==0 && c.getLong(4)>0){"已发布成片不存在或身份已变化，原片保留"}
            }
        }else{
            val target=legacyTarget(job.outputName)
            check(uri==Uri.fromFile(target) && target.isFile && target.length()>0){"已发布成片不可读，原片保留"}
        }
        return uri
    }
    @Suppress("DEPRECATION")
    private fun legacyTarget(name:String):File {
        require(name.startsWith(ExternalModePolicy.OUTPUT_PREFIX) && File(name).name==name)
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"传奇一瞬/外接/$name")
    }
}
