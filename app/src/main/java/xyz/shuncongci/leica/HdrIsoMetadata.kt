package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.ln
import kotlin.math.roundToLong

/** ISO 21496-1 metadata for the existing SDR-base, linear-sRGB RGB gain map.
 * Serialization follows libultrahdr v2.0.2 gainmapmetadata.cpp (version 0).
 * This only describes already-encoded pixels: it never changes gain bounds or headroom.
 */
internal object HdrIsoMetadata {
    private val namespace = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.US_ASCII)
    // A power-of-two denominator keeps gamma/offset exact. For every positive finite
    // Float gain, log2 lies in [-149,128], so signed numerators cannot overflow.
    // The worst log2 rounding error is <= 2^-24 stops; Adobe keeps its original Double text.
    private const val DENOMINATOR = 1 shl 23
    private fun log2(value:Float):Double = ln(value.toDouble()) / ln(2.0)
    private fun numerator(value:Double):Int {
        require(value.isFinite()) { "无效HDR增益元数据" }
        val n = (value * DENOMINATOR).roundToLong()
        require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "HDR增益元数据超出范围" }
        return n.toInt()
    }
    /** Main-image packet contains only minimum_version and writer_version. */
    fun primary():ByteArray = namespace + byteArrayOf(0,0,0,0)

    /** RGB channel parameters are explicitly repeated, even though their bounds are equal. */
    fun gain(map:HdrMath.Map):ByteArray {
        require(map.minimum.isFinite() && map.minimum > 0f &&
            map.maximum.isFinite() && map.maximum >= map.minimum &&
            map.display.isFinite() && map.display >= 1f) { "无效HDR增益元数据" }
        return gainLog2(log2(map.minimum),log2(map.maximum),log2(map.display))
    }

    /** Legacy Adobe packets already contain log2 values. Do not round-trip them through Float/pow. */
    fun gainLog2(minimum:Double,maximum:Double,display:Double):ByteArray {
        require(minimum.isFinite() && maximum.isFinite() && display.isFinite() &&
            minimum >= -149.0 && maximum <= 128.0 && maximum >= minimum && display in 0.0..128.0) { "无效HDR增益元数据" }
        val low = numerator(minimum)
        val high = numerator(maximum)
        val headroom = numerator(display)
        val offset = numerator(HdrMath.EPSILON.toDouble())
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(namespace)
            out.writeShort(0) // minimum_version
            out.writeShort(0) // writer_version
            // bit 7: three channel parameter sets; bit 6: use base (sRGB) primaries;
            // bit 3: common denominator. Forward SDR->HDR, all reserved bits zero.
            out.writeByte(0xc8)
            out.writeInt(DENOMINATOR)
            out.writeInt(0) // log2(base HDR headroom) = 0, i.e. 1x
            out.writeInt(headroom)
            repeat(3) {
                out.writeInt(low)
                out.writeInt(high)
                out.writeInt(DENOMINATOR) // gamma = 1
                out.writeInt(offset) // base / SDR offset
                out.writeInt(offset) // alternate / HDR offset
            }
        }
        return bytes.toByteArray()
    }
}
