package xyz.shuncongci.leica

import java.io.File
import java.io.InputStream

/** Bounded-memory byte copy, no re-encoding or modifications of the gallery original. */
object PhotoCopy {
    fun stage(directory:File, open:()->InputStream, check:()->Unit = {}):File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本机照片工作目录" }
        val temp=File.createTempFile("source-", ".part", directory)
        val final=File(directory,temp.name.removeSuffix(".part")+".image")
        try {
            open().use { input -> temp.outputStream().use { output ->
                val buffer=ByteArray(64*1024)
                var count=0L
                while(true){check();val n=input.read(buffer);if(n<0)break;if(n==0)continue;output.write(buffer,0,n);count+=n}
                require(count>0){"所选照片为空，请选择已下载到本机的照片"}
                output.fd.sync()
            } }
            check()
            check(temp.renameTo(final)){"无法保存本机照片工作副本"}
            return final
        } finally { temp.delete() }
    }
}
