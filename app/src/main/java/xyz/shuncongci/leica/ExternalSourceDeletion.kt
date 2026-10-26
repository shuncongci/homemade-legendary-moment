package xyz.shuncongci.leica

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CancellationException

/** Only operates on one journaled input in the ACTIVE run, after a read-verified successful export.
 * Android 10+ URI write approval is foreground/user initiated; granting it NEVER skips revalidation.
 * No deletion requests survive stop/restart. The page and foreground notification disclose retained inputs.
 */
class ExternalSourceDeletion(private val context:Context,private val journal:ExternalQueueStore) {
    data class Attempt(val result:OriginalOutcome,val sender:IntentSender?=null,val id:String="",val run:String="")
    private val cr=context.contentResolver
    private val folders=ExternalFolders(context)
    private val exporter=ExternalPhotoExport(context,journal)
    private fun active(run:String)=ExternalModeRuntime.status.let{it.running && !it.stopping && it.runId==run}
    private fun check(run:String,cancel:Cancel){cancel.check();if(!active(run))throw CancellationException("监听已停止；不补删原片")}
    fun outputHash(id:String,cancel:Cancel):String {
        val r=checkNotNull(journal.original(id));check(r.job.state=="DONE")
        return hash(exporter.publishedUri(id),r.job.run,cancel)
    }
    private fun hash(uri:Uri,run:String,cancel:Cancel,original:Boolean=false):String {
        val input=if(original)ExternalCaptureMetadata.openOriginal(context,uri)else cr.openInputStream(uri)?:error("文件不可读取，原片保留")
        return ExternalOriginalGuard.digest(input){check(run,cancel)}
    }
    private fun observedFileKey(r:ExternalQueueStore.OriginalReceipt):String {
        val uri=Uri.parse(r.job.photo.uri);if(uri.scheme!="file")return ""
        return try{java.nio.file.Files.readAttributes(File(checkNotNull(uri.path)).toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()?.toString().orEmpty()}
        catch(e:Exception){propagateFatalCause(e);""}
    }
    private fun observe(r:ExternalQueueStore.OriginalReceipt,folder:ExternalFolder)=folders.find(folder,r.job.photo.key)
    private fun same(r:ExternalQueueStore.OriginalReceipt,p:ExternalPhoto?)=ExternalOriginalGuard.sameIdentity(r.job.photo,p,Uri.parse(r.job.photo.uri).scheme=="file",r.sourceFileKey,observedFileKey(r))
    fun attempt(id:String,folder:ExternalFolder,cancel:Cancel,requestPermission:Boolean=false,permissionTried:Boolean=false):Attempt=synchronized(lock){
        val r=journal.original(id)?:return@synchronized Attempt(OriginalOutcome(OriginalAction.KEPT,"任务不存在，未删除任何原片"))
        if(r.action in setOf(OriginalAction.DELETED,OriginalAction.TRASHED,OriginalAction.DECLINED,OriginalAction.EXPIRED,OriginalAction.UNKNOWN))return@synchronized Attempt(OriginalOutcome(r.action,r.message))
        var sender:IntentSender?=null
        var removalAttempted=false
        var removalApplied=false
        val result=try{
            ExternalOriginalGuard.afterPublished(r.policy,r.job.state=="DONE",cancel,{active(r.job.run)},
                sourceMatches={
                    check(r.job.run,cancel)
                    r.sourceHash.matches(Regex("[a-f0-9]{64}")) && !r.job.photo.name.startsWith(ExternalModePolicy.OUTPUT_PREFIX) &&
                        same(r,observe(r,folder)) && hash(Uri.parse(r.job.photo.uri),r.job.run,cancel,original=true)==r.sourceHash && same(r,observe(r,folder))
                },
                outputMatches={r.outputHash.matches(Regex("[a-f0-9]{64}")) && Uri.parse(r.job.photo.uri)!=exporter.publishedUri(id) && outputHash(id,cancel)==r.outputHash},
                remove={
                    check(r.job.run,cancel)
                    journal.originalOutcome(id,OriginalOutcome(OriginalAction.DELETING,"成片及原片身份/字节已核对；正在处置这张原片"))
                    check(r.job.run,cancel)
                    when(folder.kind){
                        ExternalFolderKind.DIRECTORY->{
                            val uri=Uri.parse(r.job.photo.uri);check(uri.scheme=="file")
                            val source=File(checkNotNull(uri.path));check(source.canonicalFile.parentFile==File(folder.location).canonicalFile && source.canonicalPath==r.job.photo.key)
                            check(same(r,observe(r,folder))){"删除前原片状态已变化，原片保留"}
                            removalAttempted=true
                            if(!source.delete()){removalAttempted=false;error("文件删除未获允许，原片保留")};removalApplied=true
                            // Source was removed successfully. Index refresh is best effort, NOT a reason to retry.
                            try{MediaScannerConnection.scanFile(context,arrayOf(source.absolutePath),null,null)}catch(e:Exception){propagateFatalCause(e)}
                            OriginalOutcome(OriginalAction.DELETED,"本张原片已删除（文件目录可能无法恢复）；成片已保留")
                        }
                        ExternalFolderKind.TREE->{
                            check(ExternalPermissions.treeWrite(context,folder)){"所选目录没有写入授权，原片保留"}
                            val uri=Uri.parse(r.job.photo.uri);check(DocumentsContract.isDocumentUri(context,uri))
                            check(DocumentsContract.getTreeDocumentId(uri)==DocumentsContract.getTreeDocumentId(Uri.parse(folder.location)))
                            val canDelete=cr.query(uri,arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null).use{c->c!=null && c.moveToFirst() && c.getLong(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong()!=0L}
                            check(canDelete){"目录提供器不允许删除，原片保留"}
                            removalAttempted=true
                            if(!DocumentsContract.deleteDocument(cr,uri)){removalAttempted=false;error("系统目录删除失败，原片保留")};removalApplied=true
                            OriginalOutcome(OriginalAction.DELETED,"本张原片已由目录提供器删除（是否可恢复取决于提供器）；成片保留")
                        }
                        ExternalFolderKind.MEDIA->{
                            val uri=Uri.parse(r.job.photo.uri);check(uri.scheme=="content" && uri.authority=="media" && uri.lastPathSegment?.toLongOrNull()!=null)
                            if(Build.VERSION.SDK_INT>=30){
                                removalAttempted=true
                                val changed=cr.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_TRASHED,1)},null,null)
                                if(changed==0){removalAttempted=false;error("原片回收站操作未完成，原片保留")};check(changed==1);removalApplied=true
                                val confirmed=cr.query(uri,arrayOf(MediaStore.Images.Media.IS_TRASHED),android.os.Bundle().apply{putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_INCLUDE)},null).use{c->c!=null && c.moveToFirst() && c.getInt(0)==1}
                                if(confirmed)OriginalOutcome(OriginalAction.TRASHED,"本张原片已移入系统回收站；成片保留")
                                else OriginalOutcome(OriginalAction.UNKNOWN,"系统报告已处置，但回读未确认；请人工核对原片，不会自动重试")
                            }else{
                                removalAttempted=true
                                val changed=cr.delete(uri,null,null)
                                if(changed==0){removalAttempted=false;error("原片删除未完成，原片保留")};check(changed==1);removalApplied=true
                                OriginalOutcome(OriginalAction.DELETED,"本张原片已删除；成片保留")
                            }
                        }
                    }
                })
        }catch(e:SecurityException){
            if(removalApplied)OriginalOutcome(OriginalAction.UNKNOWN,"系统已返回处置成功，但之后读取授权变化；请人工核对，不会重试删除")
            else if(folder.kind==ExternalFolderKind.MEDIA && !ExternalPermissions.originalMetadata(context))OriginalOutcome(OriginalAction.FAILED,"原片元数据读取授权已撤销；原片保留，请重新授权后重新监听新片")
            else if(folder.kind==ExternalFolderKind.MEDIA && !permissionTried){
                if(requestPermission){
                    sender=when{
                        Build.VERSION.SDK_INT>=30->MediaStore.createWriteRequest(cr,listOf(Uri.parse(r.job.photo.uri))).intentSender
                        Build.VERSION.SDK_INT==29 && e is RecoverableSecurityException->e.userAction.actionIntent.intentSender
                        else->null
                    }
                }
                if(!requestPermission || sender!=null)OriginalOutcome(OriginalAction.WAIT_PERMISSION,"成片已保存；删除原片需系统授权，请回外接页面顶部确认。未授权前保留原片")
                else OriginalOutcome(OriginalAction.FAILED,"系统不允许删除这张原片，已同时保留原片与成片")
            }else OriginalOutcome(OriginalAction.FAILED,"系统仍未允许删除，原片保留；不会绕过权限或反复弹窗")
        }catch(e:CancellationException){OriginalOutcome(if(removalAttempted)OriginalAction.UNKNOWN else OriginalAction.EXPIRED,if(removalAttempted)"处置调用中断，请人工核对；不会重试删除原片" else "监听或本张任务已取消，原片不补删")}
        catch(e:Exception){propagateFatalCause(e);OriginalOutcome(if(removalAttempted)OriginalAction.UNKNOWN else OriginalAction.FAILED,if(removalAttempted)"成片已保存；原片处置调用未能完整确认：${e.message}。状态未知，请人工核对，不会重试" else "成片已保存；原片处置失败/未获确认：${e.message}。原片保留，不会自动重试删除")}
        if(r.job.state=="DONE")journal.originalOutcome(id,result)
        Attempt(result,sender,id,r.job.run)
    }
    fun decline(id:String){synchronized(lock){journal.original(id)?.takeIf{it.action==OriginalAction.WAIT_PERMISSION}?.let{journal.originalOutcome(id,OriginalOutcome(OriginalAction.DECLINED,"用户未同意系统授权；本张原片与成片同时保留"))}}}
    companion object { private val lock=Any() }
}
