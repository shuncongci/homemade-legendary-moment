package xyz.shuncongci.leica

import java.util.concurrent.CancellationException
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

/**
 * Three 8-bit planes (RGB, or OpenCV-style 8-bit LAB), row-major.
 * Pure Kotlin (no android.*) so the image math can be verified by JVM unit tests.
 */
class Planes(val width: Int, val height: Int, val p0: ByteArray, val p1: ByteArray, val p2: ByteArray) {
    constructor(width: Int, height: Int) :
        this(width, height, ByteArray(width * height), ByteArray(width * height), ByteArray(width * height))

    init {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(p0.size == width * height && p1.size == p0.size && p2.size == p0.size) { "plane size mismatch" }
    }

    val size: Int get() = width * height

    fun plane(i: Int): ByteArray = when (i) { 0 -> p0; 1 -> p1; else -> p2 }

    /** Writes rows [y0, y0+rows) from ARGB pixels (alpha ignored, like PIL convert('RGB')). */
    fun setFromArgbRows(y0: Int, rows: Int, src: IntArray) {
        val base = y0 * width
        for (i in 0 until rows * width) {
            val c = src[i]
            p0[base + i] = (c shr 16).toByte()
            p1[base + i] = (c shr 8).toByte()
            p2[base + i] = c.toByte()
        }
    }

    /** Reads rows [y0, y0+rows) as opaque ARGB pixels. */
    fun toArgbRows(y0: Int, rows: Int, out: IntArray) {
        val base = y0 * width
        for (i in 0 until rows * width) {
            out[i] = (0xFF shl 24) or
                ((p0[base + i].toInt() and 0xFF) shl 16) or
                ((p1[base + i].toInt() and 0xFF) shl 8) or
                (p2[base + i].toInt() and 0xFF)
        }
    }

    /** Applies an EXIF orientation (1..8) like PIL ImageOps.exif_transpose. Returns this when orientation is 1/unknown. */
    fun exifTransposed(orientation: Int): Planes {
        if (orientation !in 2..8) return this
        val w = width
        val h = height
        val swap = orientation >= 5
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        val out = Planes(ow, oh)
        parallelFor(oh) { y ->
            for (x in 0 until ow) {
                val (sx, sy) = when (orientation) {
                    2 -> (w - 1 - x) to y
                    3 -> (w - 1 - x) to (h - 1 - y)
                    4 -> x to (h - 1 - y)
                    5 -> y to x                       // transpose
                    6 -> y to (h - 1 - x)             // rotate 90° clockwise
                    7 -> (w - 1 - y) to (h - 1 - x)   // transverse
                    else -> (w - 1 - y) to x          // 8: rotate 90° counter-clockwise
                }
                val s = sy * w + sx
                val d = y * ow + x
                out.p0[d] = p0[s]; out.p1[d] = p1[s]; out.p2[d] = p2[s]
            }
        }
        return out
    }

    companion object {
        fun fromArgb(pixels: IntArray, width: Int, height: Int): Planes =
            Planes(width, height).also { it.setFromArgbRows(0, height, pixels) }
    }
}

/** Do not turn fatal VM errors (even wrapped by worker code) into recoverable UI failures. */
internal fun propagateFatalCause(exception: Exception) {
    var cause: Throwable? = exception
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (cause != null && visited.add(cause)) {
        if (cause is Error) throw cause
        cause = cause.cause
    }
}

/** Cooperative cancellation flag checked inside long loops. */
class Cancel {
    @Volatile var cancelled = false
    fun check() { if (cancelled) throw CancellationException("已取消") }
}

/** Runs body(i) for i in [0, n) on the common fork-join pool. */
internal fun parallelFor(n: Int, body: (Int) -> Unit) {
    if (n <= 0) return
    IntStream.range(0, n).parallel().forEach { body(it) }
}

/** User-selected final output for workflows that include bokeh. */
enum class BokehOutput(val label:String,val longEdge:Int?) {
    ORIGINAL("原图像素",null), SIZE_2048("2048出图",2048), SIZE_1024("1024出图",1024)
}
object LeicaSizes {
    const val MODEL_LONG_EDGE = 1024
    const val WORK_MAX_SIDE = 2048
    const val PREVIEW_MAX_SIDE = 1280
    /** Automatic non-bokeh output: source pixels up to 50 MP, aspect kept, never enlarged. */
    const val COLOR_MAX_PIXELS = 50_000_000L
    fun colorSize(width: Int,height: Int): Pair<Int,Int> {
        require(width>0 && height>0)
        val px=width.toLong()*height
        if(px<=COLOR_MAX_PIXELS)return width to height
        val s=kotlin.math.sqrt(COLOR_MAX_PIXELS.toDouble()/px)
        var w=max(1,(width*s).toInt());var h=max(1,(height*s).toInt())
        // A one-pixel short edge cannot follow the ideal scale; bound the other axis directly.
        if(w.toLong()*h>COLOR_MAX_PIXELS){
            if(w>=h)w=max(1,(COLOR_MAX_PIXELS/h).toInt()) else h=max(1,(COLOR_MAX_PIXELS/w).toInt())
        }
        return w to h
    }
    /** Largest power-of-two decode sample that still covers the automatic 50 MP target. */
    fun colorSample(width: Int,height: Int): Int {
        val (tw,th)=colorSize(width,height);var sample=1
        while(width/(sample*2)>=tw && height/(sample*2)>=th)sample*=2
        return sample
    }
    /** Fit to the chosen long edge; never enlarge a smaller source. */
    fun workingSize(width: Int,height: Int,output:BokehOutput=BokehOutput.SIZE_2048): Pair<Int,Int> {
        require(width>0 && height>0) { "无效的照片尺寸" }
        val edge=min(output.longEdge ?: return width to height,max(width,height))
        val long=max(width,height).toLong()
        fun fitted(n: Int)=max(1,((n.toLong()*edge+long/2)/long).toInt())
        return fitted(width) to fitted(height)
    }
    fun processingSize(width: Int,height: Int,blurEnabled: Boolean,output:BokehOutput=BokehOutput.SIZE_2048): Pair<Int,Int> {
        require(width>0 && height>0)
        return if(blurEnabled)workingSize(width,height,output) else colorSize(width,height)
    }
    /** GAN input and raw output have long edge 1024, ratio kept; bokeh/output options never change analysis size. */
    fun modelSize(width: Int,height: Int,blurEnabled: Boolean = false,output:BokehOutput=BokehOutput.SIZE_2048): Pair<Int,Int> {
        require(width>0 && height>0)
        val long=max(width,height).toLong()
        fun fitted(n:Int)=max(1,((n.toLong()*MODEL_LONG_EDGE+long/2)/long).toInt())
        return fitted(width) to fitted(height)
    }
    /** Decode toward GAN long edge 1024 from the source, never according to output or memory. */
    fun ganSample(width: Int,height: Int): Int {
        require(width>0 && height>0)
        val long=max(width,height);var sample=1
        while(long/sample/2>=MODEL_LONG_EDGE)sample*=2
        return sample
    }
    /** Fixed user-selected decode sampling; ORIGINAL is always sample=1. */
    fun workingSample(width: Int,height: Int,output:BokehOutput=BokehOutput.SIZE_2048): Int {
        require(width>0 && height>0)
        val edge=output.longEdge ?: return 1
        val long=max(width,height);var sample=1
        while(long/sample/2>=edge)sample*=2
        return sample
    }
    /** Depth transformer patch dimensions; retain normal 518-short-edge sizing unless it exceeds 2048. */
    fun depthSize(width: Int,height: Int): Pair<Int,Int> {
        require(width>0 && height>0)
        val desired=518.0/min(width,height)
        val factor=min(desired,2044.0/max(width,height))
        fun dim(n: Int)=((Math.rint(n*factor/14).toInt()*14).coerceIn(14,2044))
        return dim(width) to dim(height)
    }

    /** Every size uses user-source detail fusion. GAN output is never the final image. */
    fun usesFusion(width: Int, height: Int): Boolean = true

    fun previewSize(width: Int, height: Int, maxSide: Int = PREVIEW_MAX_SIDE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val s = maxSide.toDouble() / longest
        return max(1, (width * s).toInt()) to max(1, (height * s).toInt())
    }
}
