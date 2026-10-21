package xyz.shuncongci.leica

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port of Pillow's separable resampler (libImaging/Resample.c, 8-bit path):
 * antialiased when downscaling, 22-bit fixed-point coefficients, horizontal pass first,
 * 8-bit rounding between passes. Matches Image.resize(..., BILINEAR / BICUBIC), which is
 * what torchvision Resize and pipeline.py use on the PC.
 */
object Resample {
    enum class Filter(val support: Double) { BILINEAR(1.0), BICUBIC(2.0), LANCZOS(3.0) }

    private const val PRECISION_BITS = 32 - 8 - 2

    private fun weight(f: Filter, v: Double): Double {
        val x = abs(v)
        return when (f) {
            Filter.BILINEAR -> if (x < 1.0) 1.0 - x else 0.0
            Filter.LANCZOS -> if(x==0.0) 1.0 else if(x<3.0) kotlin.math.sin(Math.PI*x)*kotlin.math.sin(Math.PI*x/3)/(Math.PI*Math.PI*x*x/3) else 0.0
            Filter.BICUBIC -> {
                val a = -0.5
                when {
                    x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
                    x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
                    else -> 0.0
                }
            }
        }
    }

    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    private fun coeffs(inSize: Int, outSize: Int, f: Filter): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = f.support * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val kk = IntArray(outSize * ksize)
        val k = DoubleArray(ksize)
        val ss = 1.0 / filterScale
        val one = (1 shl PRECISION_BITS).toDouble()
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = weight(f, (x + xmin - center + 0.5) * ss)
                k[x] = w
                ww += w
            }
            for (x in 0 until ksize) {
                var v = if (x < xmax) k[x] else 0.0
                if (x < xmax && ww != 0.0) v /= ww
                kk[xx * ksize + x] = if (v < 0) (-0.5 + v * one).toInt() else (0.5 + v * one).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(ksize, bounds, kk)
    }

    private fun clip8(v: Int): Byte {
        if (v <= 0) return 0
        val s = v shr PRECISION_BITS
        return (if (s > 255) 255 else s).toByte()
    }

    private fun horizontal(src: Planes, outW: Int, c: Coeffs, cancel: Cancel?): Planes {
        val out = Planes(outW, src.height)
        val inW = src.width
        val init = 1 shl (PRECISION_BITS - 1)
        for (p in 0 until 3) {
            val a = src.plane(p)
            val o = out.plane(p)
            parallelFor(src.height) { y ->
                if (y and 63 == 0) cancel?.check()
                val rowIn = y * inW
                val rowOut = y * outW
                for (xx in 0 until outW) {
                    val xmin = c.bounds[xx * 2]
                    val xmax = c.bounds[xx * 2 + 1]
                    val kOff = xx * c.ksize
                    var ss = init
                    for (x in 0 until xmax) ss += (a[rowIn + xmin + x].toInt() and 0xFF) * c.kk[kOff + x]
                    o[rowOut + xx] = clip8(ss)
                }
            }
        }
        return out
    }

    private fun vertical(src: Planes, outH: Int, c: Coeffs, cancel: Cancel?): Planes {
        val w = src.width
        val out = Planes(w, outH)
        val init = 1 shl (PRECISION_BITS - 1)
        for (p in 0 until 3) {
            val a = src.plane(p)
            val o = out.plane(p)
            parallelFor(outH) { yy ->
                if (yy and 63 == 0) cancel?.check()
                val ymin = c.bounds[yy * 2]
                val ymax = c.bounds[yy * 2 + 1]
                val kOff = yy * c.ksize
                val rowOut = yy * w
                for (x in 0 until w) {
                    var ss = init
                    for (y in 0 until ymax) ss += (a[(ymin + y) * w + x].toInt() and 0xFF) * c.kk[kOff + y]
                    o[rowOut + x] = clip8(ss)
                }
            }
        }
        return out
    }

    fun resize(src: Planes, outW: Int, outH: Int, filter: Filter, cancel: Cancel? = null): Planes {
        require(outW > 0 && outH > 0)
        var cur = src
        if (outW != src.width) cur = horizontal(cur, outW, coeffs(src.width, outW, filter), cancel)
        if (outH != src.height) cur = vertical(cur, outH, coeffs(src.height, outH, filter), cancel)
        if (cur === src) cur = Planes(src.width, src.height, src.p0.copyOf(), src.p1.copyOf(), src.p2.copyOf())
        return cur
    }
}
