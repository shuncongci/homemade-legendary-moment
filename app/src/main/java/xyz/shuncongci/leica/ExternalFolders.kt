package xyz.shuncongci.leica

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Lists metadata only, never recursively renders a library. One selected directory, direct children only. */
class ExternalFolders(private val context:Context) {
    private val cr=context.contentResolver
    @Suppress("DEPRECATION")
    fun roots():List<File> {
        val roots=mutableListOf(Environment.getExternalStorageDirectory())
        context.getExternalFilesDirs(null).filterNotNull().forEach{f->f.absolutePath.substringBefore("/Android/").takeIf{it!=f.absolutePath}?.let{roots+=File(it)}}
        return roots.map{it.canonicalFile}.distinctBy{it.path}.filter{it.isDirectory && it.canRead()}
    }
    fun directory(file:File):ExternalFolder {
        val f=file.canonicalFile
        require(roots().any{f.path==it.path || f.path.startsWith(it.path+File.separator)}){"请在用户共享存储中选择目录"}
        require(!ExternalModePolicy.outputPath(f.path)){"不能监听本应用的导出目录，避免重复处理"}
        require(f.isDirectory && f.canRead()){"目录不存在或不可读取"}
        return ExternalFolder(ExternalFolderKind.DIRECTORY,f.path,f.path)
    }
    fun children(file:File):List<File> {
        val f=directory(file)
        return (File(f.location).listFiles()?:error("无法列出目录，请检查所有文件权限")).filter{
            it.isDirectory && !it.name.startsWith('.') && it.name!="Android" && !ExternalModePolicy.outputPath(it.path) &&
                it.canonicalFile.parentFile==file.canonicalFile
        }.sortedBy{it.name.lowercase()}
    }
    fun tree(uri:Uri):ExternalFolder {
        val id=DocumentsContract.getTreeDocumentId(uri)
        require(!ExternalModePolicy.outputPath(id.substringAfter(':'))){"不能监听本应用的导出目录"}
        val doc=DocumentsContract.buildDocumentUriUsingTree(uri,id)
        var label=id
        cr.query(doc,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{c->
            require(c.moveToFirst() && c.getString(1)==DocumentsContract.Document.MIME_TYPE_DIR){"请选择文件夹"};label=c.getString(0)
        }?:error("无法读取所选目录")
        return ExternalFolder(ExternalFolderKind.TREE,uri.toString(),"$label（手动授权目录）")
    }
    fun cameraFolders():List<ExternalFolder> {
        val found=linkedMapOf<String,ExternalFolder>()
        if(ExternalPermissions.allFiles(context))for(root in roots()){
            val dcim=File(root,"DCIM")
            (listOf(File(dcim,"Camera"),File(root,"Camera"))+(dcim.listFiles()?.filter{it.isDirectory}?:emptyList())).forEach{f->
                if(f.isDirectory && f.canRead())directory(f).let{found[it.location]=it}
            }
        }
        if(ExternalPermissions.allPhotos(context)){
            val paths=if(Build.VERSION.SDK_INT>=29)arrayOf(MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.VOLUME_NAME)
                else arrayOf(MediaStore.Images.Media.DATA)
            cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,paths,null,null,null)?.use{c->
                var n=0
                while(c.moveToNext()){
                    check(++n<=ExternalModePolicy.MAX_ENTRIES){"相册条目过多，请使用手动目录选择"}
                    val path=c.getString(0)?:continue
                    val p=if(Build.VERSION.SDK_INT>=29)path else File(path).parent.orEmpty()
                    if(ExternalModePolicy.cameraScore(p)<=0 || ExternalModePolicy.outputPath(p))continue
                    val f=if(Build.VERSION.SDK_INT>=29)ExternalFolder(ExternalFolderKind.MEDIA,p,p,c.getString(1)?:"external")
                        else ExternalFolder(ExternalFolderKind.MEDIA,p,p)
                    // Raw path watcher also sees unindexed camera images. Prefer it when permitted.
                    val duplicate=found.values.any{it.kind==ExternalFolderKind.DIRECTORY && (it.location.trimEnd('/')==p.trimEnd('/') || it.location.trimEnd('/').endsWith("/"+p.trim('/')))}
                    if(!duplicate)found["${f.volume}:$p"]=f
                }
            }?:error("相册未返回目录，请检查照片权限或手动选择")
        }
        return found.values.sortedWith(compareByDescending<ExternalFolder>{ExternalModePolicy.cameraScore(it.location)}.thenBy{it.label})
    }
    /** [skip] drops already-known keys BEFORE any per-file stat; MEDIA rows with `_ID <= mediaAfter` (pre-baseline
     *  rows) are not read at all. Every returned item is identical to what the full listing returns for it. */
    fun scan(folder:ExternalFolder,skip:(String)->Boolean={false},mediaAfter:Long=0):List<ExternalPhoto> {
        check(ExternalPermissions.canRead(context,folder)){"读取权限已撤销，监听已停止"}
        val photos=when(folder.kind){
            ExternalFolderKind.DIRECTORY->scanDirectory(folder,skip)
            ExternalFolderKind.MEDIA->scanMedia(folder,skip,mediaAfter)
            ExternalFolderKind.TREE->scanTree(folder,skip)
        }
        check(photos.size<=ExternalModePolicy.MAX_ENTRIES){"目录过大，请选择具体相机目录"}
        return photos
    }
    /** Re-reads ONE admitted photo instead of re-listing its folder: same identity, filters and fields as [scan].
     *  Anything it cannot resolve exactly (tree documents, symlinked entries, other volumes) uses the full listing. */
    fun find(folder:ExternalFolder,key:String):ExternalPhoto? {
        check(ExternalPermissions.canRead(context,folder)){"读取权限已撤销，监听已停止"}
        return when(folder.kind){
            ExternalFolderKind.DIRECTORY->{
                val dir=directory(File(folder.location)).let{File(it.location)}
                val file=File(dir,File(key).name)
                if(file.path!=key)scan(folder).firstOrNull{it.key==key}
                else if(!ExternalModePolicy.image(file.name) || !file.isFile || file.canonicalFile.parentFile!=dir || file.canonicalPath!=key)null
                else directoryPhoto(file)
            }
            ExternalFolderKind.MEDIA->{
                val id=key.substringAfterLast('/').toLongOrNull()
                if(id==null || key!=ContentUris.withAppendedId(mediaUri(folder),id).toString())scan(folder).firstOrNull{it.key==key}
                else scanMedia(folder,{false},0,id).firstOrNull{it.key==key}
            }
            ExternalFolderKind.TREE->scan(folder).firstOrNull{it.key==key}
        }
    }
    private fun directoryPhoto(file:File)=ExternalPhoto(file.canonicalPath,Uri.fromFile(file).toString(),file.name,file.length(),file.lastModified(),
        created=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis())
    private fun scanDirectory(folder:ExternalFolder,skip:(String)->Boolean):List<ExternalPhoto> {
        val dir=directory(File(folder.location)).let{File(it.location)}
        val files=dir.listFiles()?:error("目录不可读取或存储已断开")
        // Name and known-key checks are free; canonical/stat calls run only for unknown image entries.
        // A symlinked entry's path differs from its canonical key, so it is never skipped wrongly.
        return files.asSequence().filter{ExternalModePolicy.image(it.name) && !skip(it.path) && it.isFile && it.canonicalFile.parentFile==dir}
            .map{directoryPhoto(it)}.toList()
    }
    private fun mediaUri(f:ExternalFolder)=if(Build.VERSION.SDK_INT>=29)MediaStore.Images.Media.getContentUri(f.volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    @Suppress("DEPRECATION")
    private fun scanMedia(folder:ExternalFolder,skip:(String)->Boolean,after:Long,only:Long?=null):List<ExternalPhoto> {
        val modern=Build.VERSION.SDK_INT>=29
        val columns=mutableListOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.SIZE,MediaStore.Images.Media.DATE_MODIFIED)
        if(modern)columns+=MediaStore.Images.Media.IS_PENDING
        columns+=MediaStore.Images.Media.DATE_TAKEN;columns+=MediaStore.Images.Media.DATE_ADDED
        var where=if(modern)"${MediaStore.Images.Media.RELATIVE_PATH}=?" else "${MediaStore.Images.Media.DATA} LIKE ?"
        val path=if(modern)folder.location.trimEnd('/')+"/" else folder.location.trimEnd('/')+"/%"
        val args=arrayListOf(path)
        // Indexed _ID filters: one row for a recheck, or only rows inserted after the baseline maximum.
        if(only!=null){where+=" AND ${MediaStore.Images.Media._ID}=?";args+=only.toString()}
        else if(after>0){where+=" AND ${MediaStore.Images.Media._ID}>?";args+=after.toString()}
        val out=ArrayList<ExternalPhoto>();val base=mediaUri(folder)
        cr.query(base,columns.toTypedArray(),where,args.toTypedArray(),"${MediaStore.Images.Media._ID} ASC")?.use{c->
            while(c.moveToNext()){
                val name=c.getString(1)?:continue
                if(!ExternalModePolicy.image(name))continue
                // Legacy LIKE could include subdirectories; exact parent is checked with a second DATA projection below.
                val uri=ContentUris.withAppendedId(base,c.getLong(0))
                if(skip(uri.toString()))continue
                if(!modern){
                    var exact=false
                    cr.query(uri,arrayOf(MediaStore.Images.Media.DATA),null,null,null)?.use{d->exact=d.moveToFirst() && File(d.getString(0)).parent==folder.location}
                    if(!exact)continue
                }
                val taken=c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                val added=c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))*1000
                out+=ExternalPhoto(uri.toString(),uri.toString(),name,c.getLong(2),c.getLong(3)*1000,modern && c.getInt(4)!=0,if(taken>0)taken else added)
                check(out.size<=ExternalModePolicy.MAX_ENTRIES){"目录照片过多"}
            }
        }?:error("相机相册不可读取，请重新授权")
        return out
    }
    private fun scanTree(folder:ExternalFolder,skip:(String)->Boolean):List<ExternalPhoto> {
        val tree=Uri.parse(folder.location);val root=DocumentsContract.getTreeDocumentId(tree)
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,root)
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE)
        val out=ArrayList<ExternalPhoto>()
        cr.query(children,columns,null,null,null)?.use{c->while(c.moveToNext()){
            val name=c.getString(1)?:continue
            if(c.getString(4)==DocumentsContract.Document.MIME_TYPE_DIR || !ExternalModePolicy.image(name))continue
            val id=c.getString(0)
            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,id)
            if(skip(uri.toString()))continue
            out+=ExternalPhoto(uri.toString(),uri.toString(),name,c.getLong(2),c.getLong(3),created=c.getLong(3))
            check(out.size<=ExternalModePolicy.MAX_ENTRIES){"目录照片过多"}
        }}?:error("授权目录已失效或存储已断开")
        return out
    }
}
