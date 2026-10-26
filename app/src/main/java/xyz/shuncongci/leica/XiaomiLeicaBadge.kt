package xyz.shuncongci.leica

/** Sample-compatible private gallery flag, NOT visible pixels, Make/Model spoofing or a standardized EXIF
 * watermark. The user-supplied marked JPEG contains ExifIFD 0x88B0, BYTE[1] = 1. Other private fields
 * (Madrid, filterId=66048 etc.) describe capture/filter settings and are NOT copied from that sample.
 * Actual Xiaomi Gallery recognition is OEM/version-specific and still requires device acceptance.
 */
object XiaomiLeicaBadge {
    const val TAG=0x88b0
    fun patches(source:CaptureExif,enabled:Boolean):List<CaptureExif.Patch> {
        // Always explicitly clear this flag when disabled, including an already marked original.
        // One-byte value is endian independent. Everything else stays sourced from THIS input.
        val existing=source.exif().firstOrNull{it.tag==TAG}
        require(existing==null || existing.type==1 && existing.count==1L){"相册私有标识字段类型不兼容，未猜测或丢弃原片元数据"}
        val bits=existing?.value?.first()?.toInt()?.and(255)?:0
        val next=if(enabled)bits or 1 else bits and 254
        return listOf(CaptureExif.Patch(true,TAG,1,byteArrayOf(next.toByte())))
    }
}

/** Editor export: the encoded file is never decoded again. Only an EXIF packet carrying the badge and
 * the output geometry is inserted; pixels, gainmap JPEG and quality stay byte-exact. Readback must match. */
object LeicaBadgeExport {
    fun exif(width:Int,height:Int):ByteArray {val e=CaptureExif.empty();return e.normalized(width,height,XiaomiLeicaBadge.patches(e,true))}
    fun mark(source:java.io.File,target:java.io.File,width:Int,height:Int,cancel:Cancel)=
        CaptureMetadataContainer.write(source,target,exif(width,height),width,height,cancel)
    /** Returns a new private marked copy beside the cache; caller deletes it. */
    fun marked(context:android.content.Context,source:java.io.File,width:Int,height:Int,cancel:Cancel):java.io.File {
        val target=java.io.File(context.cacheDir,"badge-${java.util.UUID.randomUUID()}.jpg")
        try{mark(source,target,width,height,cancel);return target}catch(t:Throwable){target.delete();throw t}
    }
}
