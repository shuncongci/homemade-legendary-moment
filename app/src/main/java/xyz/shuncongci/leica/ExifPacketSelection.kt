package xyz.shuncongci.leica

/** Select one intact TIFF, never relocate/merge MakerNotes from different offset spaces.
 * Byte length (padding/thumbnails) says nothing about the presence of capture metadata.
 * A unique descriptive packet may coexist with empty/version/geometry-only placeholders.
 * Multiple distinct descriptive packets are ambiguous: fail explicitly instead of losing fields.
 * The caller archives ALL original packets, including placeholders and duplicate TIFFs. */
internal object ExifPacketSelection {
    private val rootStructure=setOf(0x100,0x101,0x112,0x8769,0x8825)
    private val exifStructure=setOf(0x9000,0xa000,0xa001,0xa002,0xa003)
    fun select(packets:List<ByteArray>):ByteArray? {
        if(packets.size<2)return packets.firstOrNull() // Preserve the existing single-packet path.
        val descriptive=packets.filter{bytes->
            val tiff=CaptureExif(bytes)
            // Parse every secondary packet too. Malformed input is not a disposable placeholder.
            val root=tiff.root();val exif=tiff.exif();val gps=tiff.gps()
            root.any{it.tag !in rootStructure} || exif.any{it.tag !in exifStructure} || gps.any{it.tag!=0}
        }
        val chosen=descriptive.firstOrNull()?:packets.first()
        require(descriptive.all{it.contentEquals(chosen)}){
            "照片含多段不同的拍摄元数据，无法无损确定主段；未丢弃信息导出，原片保留"
        }
        return chosen
    }
}
