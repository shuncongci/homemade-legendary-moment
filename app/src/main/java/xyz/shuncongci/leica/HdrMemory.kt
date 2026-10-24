package xyz.shuncongci.leica

/** Legacy diagnostic/test estimates only; production HDR paths do not call these admission checks. */
object HdrMemory {
    const val MIB=1024L*1024
    private fun pixels(w:Int,h:Int):Long {
        require(w>0 && h>0 && w.toLong()*h*3<=Int.MAX_VALUE){"HDR像素过多，未缩图"}
        return w.toLong()*h
    }
    // Two float RGB planes. Native Bitmap + file-backed raw snapshots are NOT Java arrays.
    fun decodeHeap(w:Int,h:Int)=pixels(w,h)*24+16*MIB
    fun decodeNative(sw:Int,sh:Int):Long {
        require(sw>0 && sh>0 && sw.toLong()*sh<=Int.MAX_VALUE)
        return sw.toLong()*sh*16+32*MIB
    }
    // Additional companions/CoC/alpha/multiscale/scatter scratch; sharp and SDR are already live.
    fun renderHeap(w:Int,h:Int)=pixels(w,h)*96+32*MIB
    // SDR result plus optional HDR deficit/source/color/foreground remain live during HDR excess optics.
    fun pairedRenderHeap(w:Int,h:Int)=renderHeap(w,h)+pixels(w,h)*48
    // Crop/base/map/JPEG/readback scratch; no RGB float log-ratio array is needed.
    fun encodeHeap(w:Int,h:Int)=pixels(w,h)*48+32*MIB
    fun check(stage:String,need:Long,available:Long,reserve:Long=16*MIB){
        require(need>=0 && available>=0 && reserve>=0)
        require(need<=maxOf(0L,available-reserve)){
            "HDR内存不足[$stage]：本阶段估算${(need+MIB-1)/MIB}MiB，可用${available/MIB}MiB，预留${reserve/MIB}MiB；未缩图/转SDR，原片与已有成片保留"
        }
    }
}
