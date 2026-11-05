package xyz.shuncongci.leica

import android.content.Context
import java.nio.ByteBuffer

/** Standalone public CPU LUT renderer; no signature binding, dynamic code or private assets. */
internal object VideoLutNative {
    init { System.loadLibrary("videolut") }
    fun open(context:Context,look:VideoColor,cancel:Cancel):Long {
        val bytes=ColorLutFiles.read(context.assets.open(look.asset),look.sha256,cancel)
        cancel.check()
        val handle=create(bytes)
        try {cancel.check();return handle} catch(t:Throwable){close(handle);throw t}
    }
    private external fun create(bytes:ByteArray):Long
    external fun render(handle:Long,buffers:Array<ByteBuffer>,layout:IntArray,width:Int,height:Int,start:Int,rows:Int,bits:Int,transfer:Int,full:Boolean)
    external fun close(handle:Long)
}
