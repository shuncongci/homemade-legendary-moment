package xyz.shuncongci.leica

/** Guards specifically for the source/geometry path. Edited outputs use HdrPairExport, never the original gainmap. */
object HdrExportPolicy {
    const val EDITED = "原片增益图仅适用于未调色/未虚化的几何导出；已编辑成片必须走双成片重算HDR，不能贴回原增益图。"
    fun unavailable(sdk:Int, edited:Boolean):String? = when {
        sdk < 34 -> "保留HDR需要Android 14或更高版本；当前SDR导出不受影响。"
        edited -> EDITED
        else -> null
    }
    fun workingBytes(sw:Int,sh:Int,ow:Int,oh:Int):Long {
        require(sw>0 && sh>0 && ow>0 && oh>0)
        val source=sw.toLong()*sh;val output=ow.toLong()*oh
        require(source<=Int.MAX_VALUE && output<=Int.MAX_VALUE){"照片像素过多，无法安全导出HDR；不会自动缩图"}
        // Full decode, paired gainmaps, one geometry copy, encoder/readback allowance.
        return source*16+output*16+32L*1024*1024
    }
    fun checkSize(boxW:Int,boxH:Int,w:Int,h:Int) {
        require(w in 1..boxW && h in 1..boxH){"HDR输出不得放大或改变来源区域"}
        require(kotlin.math.abs(w.toDouble()/h-boxW.toDouble()/boxH)<=2.0/minOf(w,h)) {"HDR输出比例与裁剪不一致"}
    }
    data class Metadata(val width:Int,val height:Int,val mapWidth:Int,val mapHeight:Int,val values:List<Float>) {
        init {
            require(width>0 && height>0 && mapWidth>0 && mapHeight>0)
            require(values.size==17 && values.all{it.isFinite()}){"HDR参数无效"}
            for(c in 0..2){require(values[c]>0 && values[3+c]>=values[c] && values[6+c]>0);require(values[9+c]>=0 && values[12+c]>=0)}
            require(values[15]>=1f && values[16]>values[15]){"不支持的HDR显示范围"}
        }
    }
    fun checkRoundTrip(expected:Metadata,actual:Metadata) {
        require(expected.width==actual.width && expected.height==actual.height){"HDR编码回读尺寸不一致，已停止发布"}
        require(expected.mapWidth==actual.mapWidth && expected.mapHeight==actual.mapHeight){"HDR增益图尺寸改变，已停止发布"}
        require(expected.values.zip(actual.values).all{(a,b)->kotlin.math.abs(a-b)<=maxOf(1e-4f,kotlin.math.abs(a)*1e-3f)}){"HDR编码回读参数改变，已停止发布"}
    }
}

/** A fresh menu has a fresh opt-in; not a persisted export format. */
class HdrExportChoice(val unavailableReason:String?) {
    var enabled=false; private set
    fun setEnabled(value:Boolean) { require(!value || unavailableReason==null){unavailableReason?:"HDR不可用"};enabled=value }
}
