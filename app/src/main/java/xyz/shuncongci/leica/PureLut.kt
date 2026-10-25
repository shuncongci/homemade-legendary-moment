package xyz.shuncongci.leica

import java.io.DataInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Spatially invariant, single-pass 3D lookup in encoded SDR sRGB. No models,
 * automatic exposure, masks, shading, sharpening, fusion or image-size reduction.
 * Float64 nodes and the ordered eight-corner sum retain the reference quantization.
 * The generic reader/render math contains no look-specific node data.
 */
class PureLut private constructor(val edge:Int,private val nodes:DoubleArray) {
    private val low=IntArray(256){minOf(((it/255.0)*(edge-1)).toInt(),edge-2)}
    private val fraction=DoubleArray(256){(it/255.0)*(edge-1)-low[it]}

    /** Source is immutable; output dimensions are exactly the input dimensions. */
    fun render(source:Planes,cancel:Cancel):Planes {
        cancel.check()
        val out=Planes(source.width,source.height)
        parallelFor(source.height){y->
            cancel.check()
            for(x in 0 until source.width){
                if(x and 255==0)cancel.check()
                val i=y*source.width+x
                val r=source.p0[i].toInt() and 255;val g=source.p1[i].toInt() and 255;val b=source.p2[i].toInt() and 255
                val ir=low[r];val ig=low[g];val ib=low[b]
                val fr=fraction[r];val fg=fraction[g];val fb=fraction[b]
                var red=0.0;var green=0.0;var blue=0.0
                // Same arithmetic order as the canonical cube evaluator: R, then G, then B.
                for(dr in 0..1)for(dg in 0..1)for(db in 0..1){
                    val weight=(if(dr==1)fr else 1-fr)*(if(dg==1)fg else 1-fg)*(if(db==1)fb else 1-fb)
                    val at=(((ib+db)*edge+(ig+dg))*edge+(ir+dr))*3
                    red+=nodes[at]*weight;green+=nodes[at+1]*weight;blue+=nodes[at+2]*weight
                }
                out.p0[i]=(red.coerceIn(0.0,1.0)*255+0.5).toInt().toByte()
                out.p1[i]=(green.coerceIn(0.0,1.0)*255+0.5).toInt().toByte()
                out.p2[i]=(blue.coerceIn(0.0,1.0)*255+0.5).toInt().toByte()
            }
        }
        cancel.check();return out
    }

    companion object {
        /** PLUT0001, big-endian int32 edge, then float64 RGB nodes; R varies fastest.
         * Bounded allocation, exact file length, finite in-gamut nodes and full SHA-256.
         * Closes input on success, invalid data and cancellation; never returns partial data.
         */
        fun read(input:InputStream,expectedSha256:String,cancel:Cancel):PureLut = input.use { raw->
            require(expectedSha256.matches(Regex("[a-f0-9]{64}"))){"无效的色彩查表校验值"}
            cancel.check()
            val hash=MessageDigest.getInstance("SHA-256")
            val data=DataInputStream(DigestInputStream(raw.buffered(),hash))
            val magic=ByteArray(8);data.readFully(magic)
            require(magic.contentEquals("PLUT0001".toByteArray(Charsets.US_ASCII))){"色彩查表格式不支持"}
            val n=data.readInt();require(n in 2..65){"色彩查表网格尺寸无效"}
            val nodes=DoubleArray(n*n*n*3)
            for(i in nodes.indices){
                if(i and 4095==0)cancel.check()
                val v=data.readDouble();require(v.isFinite() && v in 0.0..1.0){"色彩查表节点无效"};nodes[i]=v
            }
            require(data.read()==-1){"色彩查表长度异常"}
            val actual=hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            require(actual==expectedSha256){"内置色彩查表校验失败，请重新安装应用"}
            cancel.check();PureLut(n,nodes)
        }
    }
}
