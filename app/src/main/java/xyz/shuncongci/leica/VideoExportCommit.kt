package xyz.shuncongci.leica

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Complete-file publication only. Callback IO is tested without Android/MediaStore. */
internal object VideoExportCommit {
    const val FOLDER="Movies/传奇一瞬/"
    fun digest(input:InputStream,cancel:Cancel):ByteArray=input.use{stream->
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
        while(true){cancel.check();val n=stream.read(buffer);if(n<0)break;if(n>0)hash.update(buffer,0,n)}
        cancel.check();hash.digest()
    }
    fun copy(file:File,out:OutputStream,cancel:Cancel) {
        file.inputStream().use{input->val buffer=ByteArray(256*1024)
            while(true){cancel.check();val n=input.read(buffer);if(n<0)break;if(n>0)out.write(buffer,0,n)}
        };cancel.check()
    }
    fun <T> save(file:File,cancel:Cancel,create:()->T,writeAndSync:(T)->Unit,
                 read:(T)->InputStream,publish:(T)->Unit,rollback:(T)->Unit):T {
        cancel.check();require(file.isFile && file.length()>0){"完整视频缓存不存在或为空，未导出"}
        val expected=digest(file.inputStream(),cancel)
        return ExportCommit.run(cancel,create,write={item->
            writeAndSync(item)
            val actual=digest(read(item),cancel)
            check(expected.contentEquals(actual)){"相册回读与完整视频不一致，未发布"}
        },publish=publish,rollback=rollback)
    }
}
