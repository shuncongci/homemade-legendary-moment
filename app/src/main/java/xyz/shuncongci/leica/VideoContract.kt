package xyz.shuncongci.leica

/** The first video look shares its exact LUT with the public photo look. */
internal enum class VideoColor(val label:String,val asset:String,val sha256:String) {
    INSTANT("徕卡一瞬","looks/leica-instant-v2.bin","87d1b46ae221b272227f845f2a7a984357b0d4056a79a2570b84c1271e3f9dcf"),
    N_INSTANT("徕卡N瞬","looks/leica-n-instant.bin","72d3e0d901aba3560738486a2fa2139a44e53b966e5d0b35ac0ca33d6fb16ff9");
    companion object {
        val DEFAULT=N_INSTANT
        fun restore(name:String?)=entries.firstOrNull{it.name==name}?:DEFAULT
    }
}

internal object VideoContract {
    data class Color(val primaries:Int,val transfer:Int,val matrix:Int,val fullRange:Boolean,val bits:Int) {
        val hdr get()=transfer==16 || transfer==18
        val label get()=when(transfer){16->"PQ HDR · 10bit";18->"HLG · 10bit";13->"sRGB · 8bit";else->"Rec.709 · 8bit"}
        fun validate() {
            require(if(hdr)primaries==9 && matrix==9 && bits==10 else primaries==1 && matrix==1 && transfer in setOf(1,6,13) && bits==8){
                "该色域、传递曲线或位深尚不能原生保留；不会转为SDR或降低位深"
            }
        }
    }
    fun timelineUs(points:List<Long>,scale:Long):LongArray {
        require(scale>0 && points.isNotEmpty())
        val min=points.min();require(min>=0){"暂不支持负起始时间戳"}
        val out=LongArray(points.size){i->
            val v=java.math.BigInteger.valueOf(points[i]).subtract(java.math.BigInteger.valueOf(min))
            v.multiply(java.math.BigInteger.valueOf(1_000_000)).add(java.math.BigInteger.valueOf(scale/2)).divide(java.math.BigInteger.valueOf(scale)).longValueExact()
        }
        require((1 until out.size).all{out[it]>out[it-1]}){"时间戳重复或精度超出系统编码器能力；不抽帧处理"}
        return out
    }
    fun verifyFrames(expected:LongArray,actual:List<Long>) {
        require(expected.size==actual.size){"输出帧数与原片不同，已阻止保存"}
        val first=expected.indices.firstOrNull{expected[it]!=actual[it]}
        require(first==null){"输出帧时间戳变化，已阻止保存：编码阶段第${first!!+1}帧，原片${expected[first]}µs，编码${actual[first]}µs"}
    }
}
