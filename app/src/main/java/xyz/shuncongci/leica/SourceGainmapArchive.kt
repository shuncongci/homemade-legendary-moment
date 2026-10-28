package xyz.shuncongci.leica

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Retain the SOURCE gain JPEG as inactive, checksummed archive data, never as the new
 * rendered pixels' active gainmap. Unknown trailers/motion/animations remain ineligible. */
internal object SourceGainmapArchive {
    const val KIND="J:original-gainmap"
    fun include(file:File,bundle:PreservedMetadata.Bundle,end:Long,cancel:Cancel):PreservedMetadata.Bundle? {
        val n=file.length()-end
        if(bundle.format!="JPEG" || n<=0 || n>PreservedMetadata.MAX-bundle.packets.sumOf{it.bytes.size})return null
        val xml=bundle.xmp.map{it.toString(Charsets.UTF_8)}
        if(xml.none{it.contains("http://ns.adobe.com/hdr-gain-map/1.0/")} || xml.any{it.contains("MotionPhoto") || it.contains("MicroVideo")})return null
        val gain=RandomAccessFile(file,"r").use{f->
            val indexes=CaptureMetadataContainer.jpegHeaders(f).filter{it.mpf}
            if(indexes.size!=1)return null
            val segment=indexes.single();val exif=CaptureExif(segment.payload.copyOfRange(4,segment.payload.size));val root=exif.root()
            val count=root.singleOrNull{it.tag==0xb001}?:return null
            if(count.type!=4 || count.count!=1L || ByteBuffer.wrap(count.value).order(exif.order).int!=2)return null
            val entry=root.singleOrNull{it.tag==0xb002}?:return null
            if(entry.type!=7 || entry.value.size!=32)return null
            val b=ByteBuffer.wrap(entry.value).order(exif.order)
            fun uint(at:Int)=b.getInt(at).toLong() and 0xffffffffL
            // Vendor post-processing can leave the first MP image size stale while updating
            // the secondary offset correctly. The parsed main EOI is authoritative: every
            // byte after it must still be exactly the indexed, complete secondary JPEG.
            // Keep the original MPF bytes in the archive; never rewrite the source index.
            if(uint(4)==0L || uint(4)>end || uint(8)!=0L || uint(20)!=n || uint(24)+segment.offset+8!=end)return null
            f.seek(end);ByteArray(n.toInt()).also{f.readFully(it)}
        }
        cancel.check()
        if(JpegCompletion.primaryEnd(gain.inputStream(),gain.size.toLong(),cancel)!=gain.size.toLong())return null
        // include ALL secondary bytes; archive readback is separately required before deletion.
        return bundle.copy(packets=bundle.packets+PreservedMetadata.Packet(KIND,gain))
    }
}
