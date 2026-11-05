package xyz.shuncongci.leica

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Inserts only one owned pending video; never enumerates or modifies existing media. */
internal object VideoGalleryExport {
    fun save(context:Context,file:File,cancel:Cancel):String {
        require(Build.VERSION.SDK_INT>=29){"当前系统请使用另存为文件"}
        val cr=context.contentResolver
        val name="传奇一瞬-视频-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.mp4"
        val values=ContentValues().apply{
            put(MediaStore.Video.Media.DISPLAY_NAME,name)
            put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,VideoExportCommit.FOLDER)
            put(MediaStore.Video.Media.IS_PENDING,1)
        }
        VideoExportCommit.save(file,cancel,
            create={cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values)
                ?:error("无法创建相册视频，完整缓存保留")},
            writeAndSync={uri->
                cr.openFileDescriptor(uri,"w").use{fd->
                    requireNotNull(fd){"无法打开相册视频"}
                    FileOutputStream(fd.fileDescriptor).use{out->VideoExportCommit.copy(file,out,cancel);out.fd.sync()}
                }
            },
            read={uri->cr.openInputStream(uri)?:error("无法回读相册视频")},
            publish={uri->
                check(cr.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)==1){"相册发布失败"}
                cr.query(uri,arrayOf(MediaStore.Video.Media.IS_PENDING,MediaStore.Video.Media.SIZE),null,null,null).use{cursor->
                    check(cursor!=null && cursor.moveToFirst() && cursor.getInt(0)==0 && cursor.getLong(1)==file.length()){"相册发布状态未确认"}
                }
            },
            rollback={uri->check(cr.delete(uri,null,null)==1){"本次相册视频未能清理，请检查Movies/传奇一瞬；完整缓存仍保留"}}
        )
        return VideoExportCommit.FOLDER+name
    }
}
