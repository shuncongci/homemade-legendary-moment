package xyz.shuncongci.leica

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** OpenCV 8-bit COLOR_RGB2LAB / COLOR_LAB2RGB (sRGB, D65; L scaled to 0..255, a/b offset by 128). */
object Lab {
    private val toLinear = DoubleArray(256) { i ->
        val x = i / 255.0
        if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun sat(v: Double): Byte = Math.rint(v).toInt().coerceIn(0, 255).toByte()

    // Integer port of OpenCV RGB2Lab_b (color_lab.cpp). Verified on the PC against cv2 over all
    // 16.7M 8-bit colours: max diff 1 (exhaustive comparison with OpenCV).
    private const val LAB_SHIFT = 12
    private const val GAMMA_SHIFT = 3
    private const val LAB_SHIFT2 = LAB_SHIFT + GAMMA_SHIFT
    private const val TAB = 256 * 3 / 2 * (1 shl GAMMA_SHIFT)

    private fun rnd(x: Double): Int = Math.rint(x).toInt()

    private val srgbTab = IntArray(256) { i -> rnd(255.0 * (1 shl GAMMA_SHIFT) * toLinear[i]).coerceIn(0, 65535) }
    private val cbrtTab = IntArray(TAB) { i ->
        val x = i * (1.0 / (255 * (1 shl GAMMA_SHIFT)))
        val v = if (x < 0.008856) x * 7.787 + 16.0 / 116.0 else cbrt(x)
        rnd((1 shl LAB_SHIFT2) * v).coerceIn(0, 65535)
    }
    private val coeffs: IntArray = run {
        val m = doubleArrayOf(0.412453, 0.357580, 0.180423, 0.212671, 0.715160, 0.072169, 0.019334, 0.119193, 0.950227)
        val wp = doubleArrayOf(0.950456, 1.0, 1.088754)
        IntArray(9) { rnd((1 shl LAB_SHIFT) * m[it] / wp[it / 3]) }
    }
    private const val L_SCALE = (116 * 255 + 50) / 100
    private const val L_SHIFT = -((16 * 255 * (1 shl LAB_SHIFT2) + 50) / 100)

    private fun desc(x: Int, n: Int) = (x + (1 shl (n - 1))) shr n
    private fun cb(v: Int) = cbrtTab[v.coerceIn(0, TAB - 1)]
    private fun clip8(v: Int): Byte = v.coerceIn(0, 255).toByte()

    fun rgbToLabInPlace(p: Planes, cancel: Cancel? = null) {
        val w = p.width
        val c = coeffs
        parallelFor(p.height) { y ->
            if (y and 63 == 0) cancel?.check()
            for (i in y * w until (y + 1) * w) {
                val r = srgbTab[p.p0[i].toInt() and 0xFF]
                val g = srgbTab[p.p1[i].toInt() and 0xFF]
                val b = srgbTab[p.p2[i].toInt() and 0xFF]
                val fx = cb(desc(r * c[0] + g * c[1] + b * c[2], LAB_SHIFT))
                val fy = cb(desc(r * c[3] + g * c[4] + b * c[5], LAB_SHIFT))
                val fz = cb(desc(r * c[6] + g * c[7] + b * c[8], LAB_SHIFT))
                p.p0[i] = clip8(desc(L_SCALE * fy + L_SHIFT, LAB_SHIFT2))
                p.p1[i] = clip8(desc(500 * (fx - fy) + 128 * (1 shl LAB_SHIFT2), LAB_SHIFT2))
                p.p2[i] = clip8(desc(200 * (fy - fz) + 128 * (1 shl LAB_SHIFT2), LAB_SHIFT2))
            }
        }
    }

    private fun gamma(v: Double): Double {
        val x = v.coerceIn(0.0, 1.0)
        return if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
    }

    // The Lab inputs are discrete bytes. Cache their EXACT old double expressions, not a sampled 3D LUT.
    private val yOfL=DoubleArray(256){l8->
        val l=l8*100.0/255.0
        if(l<=8.0)l/903.3 else {val fy=(l+16.0)/116.0;fy*fy*fy}
    }
    private val fyOfL=DoubleArray(256){l8->
        val l=l8*100.0/255.0
        if(l<=8.0)7.787*yOfL[l8]+16.0/116.0 else (l+16.0)/116.0
    }
    private val xOfLA=DoubleArray(65536){at->
        val fx=((at and 255)-128.0)/500.0+fyOfL[at ushr 8]
        (if(fx*fx*fx>0.008856)fx*fx*fx else (fx-16.0/116.0)/7.787)*0.950456
    }
    private val zOfLB=DoubleArray(65536){at->
        val fz=fyOfL[at ushr 8]-((at and 255)-128.0)/200.0
        (if(fz*fz*fz>0.008856)fz*fz*fz else (fz-16.0/116.0)/7.787)*1.088754
    }
    // Locate the FIRST representable double mapped above each byte by the original gamma+rint.
    // Searching these 255 exact decision boundaries removes pow(), with no gamma approximation.
    private val gammaCuts=DoubleArray(255){low->
        val encoded=(low+.5)/255.0
        var value=if(encoded<=.04045)encoded/12.92 else ((encoded+.055)/1.055).pow(2.4)
        fun code(v:Double)=Math.rint(gamma(v)*255.0).toInt()
        while(code(value)<=low)value=Math.nextUp(value)
        while(code(Math.nextDown(value))>low)value=Math.nextDown(value)
        value
    }
    private fun gammaByte(v:Double):Byte {
        var lo=0;var hi=255
        while(lo<hi){val mid=(lo+hi) ushr 1;if(v>=gammaCuts[mid])lo=mid+1 else hi=mid}
        return lo.toByte()
    }
    fun labToRgbInPlace(p: Planes, cancel: Cancel? = null) {
        val w=p.width
        parallelFor(p.height){y->
            if(y and 63==0)cancel?.check()
            for(i in y*w until (y+1)*w){
                val l=p.p0[i].toInt()and 255;val a=p.p1[i].toInt()and 255;val b=p.p2[i].toInt()and 255
                val yy=yOfL[l];val x=xOfLA[(l shl 8)or a];val z=zOfLB[(l shl 8)or b]
                p.p0[i]=gammaByte(3.240479*x-1.53715*yy-0.498535*z)
                p.p1[i]=gammaByte(-0.969256*x+1.875991*yy+0.041556*z)
                p.p2[i]=gammaByte(0.055648*x-0.204043*yy+1.057311*z)
            }
        }
    }

}

/** cv2.GaussianBlur(src32f, (0,0), sigma): ksize = round(sigma*8+1)|1, BORDER_REFLECT_101, separable. */
object Gaussian {
    fun kernel(sigma: Double): FloatArray {
        val n = (sigma * 4 * 2 + 1).roundToInt() or 1
        val scale = -0.5 / (sigma * sigma)
        val d = DoubleArray(n) { i -> val x = i - (n - 1) * 0.5; exp(scale * x * x) }
        val sum = d.sum()
        return FloatArray(n) { (d[it] / sum).toFloat() }
    }

    internal fun reflect101(i: Int, n: Int): Int {
        if (n == 1) return 0
        var v = i
        while (v < 0 || v >= n) { if (v < 0) v = -v; if (v >= n) v = 2 * n - 2 - v }
        return v
    }

    fun blur(data: FloatArray, w: Int, h: Int, sigma: Double, cancel: Cancel? = null) {
        val k = kernel(sigma)
        val n = k.size
        val r = n / 2
        parallelFor(h) { y ->
            if (y and 31 == 0) cancel?.check()
            val base = y * w
            val buf = FloatArray(w + 2 * r)
            for (i in buf.indices) buf[i] = data[base + reflect101(i - r, w)]
            for (x in 0 until w) {
                var acc = 0f
                for (j in 0 until n) acc += k[j] * buf[x + j]
                data[base + x] = acc
            }
        }
        val strip = 16
        parallelFor((w + strip - 1) / strip) { s ->
            cancel?.check()
            val x0 = s * strip
            val cw = min(strip, w - x0)
            val buf = FloatArray((h + 2 * r) * cw)
            for (i in 0 until h + 2 * r) System.arraycopy(data, reflect101(i - r, h) * w + x0, buf, i * cw, cw)
            val acc = FloatArray(cw)
            for (y in 0 until h) {
                acc.fill(0f)
                for (j in 0 until n) {
                    val kj = k[j]
                    val off = (y + j) * cw
                    for (c in 0 until cw) acc[c] += kj * buf[off + c]
                }
                System.arraycopy(acc, 0, data, y * w + x0, cw)
            }
        }
    }
}

/**
 * leica_terminal.py (every original size): per LAB channel, result = blur(gan) + (orig - blur(orig)), clip, truncate.
 * Gaussian blur is linear, so this equals orig + blur(gan - orig): one blur per channel instead of two.
 * Both inputs are RGB of equal size and are overwritten; the returned planes (orig) hold the RGB result.
 */
object Fusion {
    const val SIGMA_L = 25.0
    const val SIGMA_AB = 20.0

    fun fuse(orig: Planes, gan: Planes, cancel: Cancel? = null): Planes {
        require(orig.width == gan.width && orig.height == gan.height) { "size mismatch" }
        val w = orig.width
        val h = orig.height
        Lab.rgbToLabInPlace(orig, cancel)
        Lab.rgbToLabInPlace(gan, cancel)
        for (c in 0 until 3) {
            FusionFir.channel(orig.plane(c),gan.plane(c),w,h,if(c==0)SIGMA_L else SIGMA_AB,cancel)
        }
        Lab.labToRgbInPlace(orig, cancel)
        return orig
    }
}
