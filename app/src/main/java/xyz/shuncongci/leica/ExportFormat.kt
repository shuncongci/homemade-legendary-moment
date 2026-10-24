package xyz.shuncongci.leica

/** Stable persistence IDs, not enum ordinals. Format changes never change rendered pixels. */
enum class ExportFormat(val id:String,val label:String,val extension:String,val mime:String,
                        val quality:Int,val lossless:Boolean,val minSdk:Int=26) {
    PNG("png-lossless","原图 PNG · 无损","png","image/png",100,true),
    JPEG("jpeg-95","JPEG · 高质量 Q95","jpg","image/jpeg",95,false),
    WEBP_SMALL("webp-90","WebP · 省空间 Q90","webp","image/webp",90,false),
    WEBP_HIGH("webp-95","WebP · 高质量 Q95","webp","image/webp",95,false),
    WEBP_LOSSLESS("webp-lossless","WebP · 无损（Android 11+）","webp","image/webp",75,true,30);

    /** Broad desktop-sample reference versus 2.1.25 full-size PNG, not a per-photo or Android promise. */
    val storageHint:String get()=when(this){
        PNG->"体积基准 · 预计节省 0%"
        JPEG->"预计省约 75–85% 存储空间"
        WEBP_SMALL->"预计省约 85–95% 存储空间"
        WEBP_HIGH->"预计省约 80–90% 存储空间"
        WEBP_LOSSLESS->"预计省约 20–35% 存储空间"
    }
    val qualityRating:String get()=when(this){
        PNG,WEBP_LOSSLESS->"品质：无损 · 精确保留成片像素"
        JPEG,WEBP_HIGH->"品质：高保真 · 有损重编码"
        WEBP_SMALL->"品质：均衡 · 有损重编码"
    }
    fun supports(sdk:Int)=sdk>=minSdk
    fun supportsDimensions(w:Int,h:Int):Boolean {
        require(w>0 && h>0)
        return this !in listOf(WEBP_SMALL,WEBP_HIGH,WEBP_LOSSLESS) || (w<=16383 && h<=16383)
    }
    /** Bitmap + conservative codec working-room; PNG stays strip/row bounded. This is export-only,
     * not the observational PhotoMemory monitor and never changes inference/output resolution. */
    fun estimatedWorkingBytes(w:Int,h:Int):Long {
        require(w>0 && h>0)
        val px=w.toLong()*h
        require(px<=Int.MAX_VALUE && w<=Int.MAX_VALUE/4){"导出尺寸过大"}
        return if(this==PNG)w.toLong()*64+4L*1024*1024 else px*(if(this==JPEG)8L else 16L)+32L*1024*1024
    }
    companion object {
        val DEFAULT=PNG
        fun available(sdk:Int)=entries.filter{it.supports(sdk)}
        fun fromId(id:String?,sdk:Int=Int.MAX_VALUE)=entries.firstOrNull{it.id==id && it.supports(sdk)}?:DEFAULT
    }
}
