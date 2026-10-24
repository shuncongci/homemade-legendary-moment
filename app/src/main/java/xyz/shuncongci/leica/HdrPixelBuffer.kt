package xyz.shuncongci.leica

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** File-backed raw pixel snapshot, never a native-sized managed ByteArray.
 * Android unlinks while mapped (pages reclaimable); desktop Windows requires unmapping first. */
internal class HdrPixelBuffer(directory:File,size:Int,copy:(ByteBuffer)->Unit):Closeable {
    private val file=File.createTempFile("hdr-pixels-",".raw",directory)
    private var memory:ByteBuffer?=null
    init {
        try {
            require(size>0)
            val space=directory.usableSpace
            require(space>=size.toLong()+16L*1024*1024){"HDR原始通道缓存空间不足；未缩图或转SDR"}
            RandomAccessFile(file,"rw").use{f->
                f.setLength(size.toLong())
                val mapped=f.channel.map(FileChannel.MapMode.READ_WRITE,0,size.toLong())
                memory=mapped // retain owner even when copy throws, so close can release it
                copy(mapped)
            }
        }catch(t:Throwable){try{close()}catch(cleanup:Throwable){t.addSuppressed(cleanup)};throw t}
    }
    fun get(index:Int):Byte=checkNotNull(memory){"HDR像素缓存已关闭"}.get(index)
    override fun close(){
        val owned=memory;memory=null
        if(file.delete() || !file.exists())return
        // Desktop-JVM diagnostic portability only. Never reflect into Android's hidden buffer APIs.
        if(owned!=null && System.getProperty("os.name","")?.startsWith("Windows")==true){
            val type=Class.forName("sun.misc.Unsafe")
            val field=type.getDeclaredField("theUnsafe").apply{isAccessible=true}
            type.getMethod("invokeCleaner",ByteBuffer::class.java).invoke(field.get(null),owned)
        }
        check(file.delete() || !file.exists()){"HDR原始通道临时缓存清理失败"}
    }
}
