package xyz.shuncongci.leica

import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Same big-endian IEEE754 bits as old per-float streams; one bounded 64 KiB buffer.
 * Never compresses/quantizes floats and never weakens checkpoint framing or integrity checks.
 */
internal object FloatBlocks {
    private const val COUNT=16384
    fun write(out:OutputStream,values:FloatArray,cancel:Cancel){
        val bytes=ByteArray(min(COUNT,values.size)*4)
        val floats=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
        for(start in values.indices step COUNT){cancel.check();val n=min(COUNT,values.size-start)
            for(i in start until start+n)require(values[i].isFinite())
            floats.clear();floats.put(values,start,n);out.write(bytes,0,n*4)
        };cancel.check()
    }
    fun read(input:DataInputStream,values:FloatArray,cancel:Cancel){
        val bytes=ByteArray(min(COUNT,values.size)*4)
        val floats=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer()
        for(start in values.indices step COUNT){cancel.check();val n=min(COUNT,values.size-start)
            input.readFully(bytes,0,n*4);floats.clear();floats.get(values,start,n)
            for(i in start until start+n)require(values[i].isFinite())
        };cancel.check()
    }
}
