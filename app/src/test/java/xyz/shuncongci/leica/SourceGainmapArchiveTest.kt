package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class SourceGainmapArchiveTest {
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("gain-archive-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun hdr():ByteArray=ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray()
    private fun export(encoded:ByteArray)=work{d->
        val raw=hdr();val src=File(d,"source").apply{writeBytes(raw)};val end=JpegCompletion.primaryEnd(src)!!
        val plain=File(d,"plain").apply{writeBytes(encoded)};val out=File(d,"out")
        val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());assertTrue(p.complete);assertFalse(p.deletionReady)
        ExternalMetadataTransfer.write(plain,out,p,16,12,Cancel());assertTrue(p.deletionReady)
        val archive=PreservedMetadata.unarchive(PreservedMetadata.readArchive(out,Cancel()))
        val gain=archive.single{it.kind==SourceGainmapArchive.KIND}.bytes
        assertArrayEquals(raw.copyOfRange(end.toInt(),raw.size),gain)
        assertArrayEquals(raw,src.readBytes());assertArrayEquals(encoded,plain.readBytes())
        val packets=PreservedMetadata.read(src,Cancel()).packets
        packets.forEachIndexed{i,original->assertEquals(original.kind,archive[i].kind);assertArrayEquals(original.bytes,archive[i].bytes)}
        assertArrayEquals(byteArrayOf(1),CaptureExif(CaptureMetadataContainer.readExif(out).exif!!).value(true,0x88b0))
    }
    @Test fun sourceHdrToJpeg(){export(CaptureMetadataFixtures.jpeg)}
    @Test fun sourceHdrToPng(){export(CaptureMetadataFixtures.png)}
    @Test fun sourceHdrToWebp(){export(CaptureMetadataFixtures.webp)}
    @Test fun sourceHdrToLosslessWebp(){export(CaptureMetadataFixtures.losslessWebp)}
    @Test fun sourceHdrToNewHdr(){export(hdr())}
    @Test fun opaqueTrailerStillRetained()=work{d->val src=File(d,"source").apply{writeBytes(CaptureMetadataFixtures.jpeg+"private-video".toByteArray())};assertFalse(ExternalMetadataTransfer.prepare(src,16,12,true,Cancel()).complete)}
    @Test fun appendedUnknownBytesStillRetained()=work{d->val src=File(d,"source").apply{writeBytes(hdr()+byteArrayOf(1,2,3))};assertFalse(ExternalMetadataTransfer.prepare(src,16,12,true,Cancel()).complete)}
    @Test fun truncatedGainStillRetained()=work{d->val b=hdr();val src=File(d,"source").apply{writeBytes(b.copyOf(b.size-1))};assertFalse(ExternalMetadataTransfer.prepare(src,16,12,true,Cancel()).complete)}
    @Test fun plainSecondaryJpegNotEnough()=work{d->val src=File(d,"source").apply{writeBytes(CaptureMetadataFixtures.jpeg+CaptureMetadataFixtures.jpeg)};assertFalse(ExternalMetadataTransfer.prepare(src,16,12,true,Cancel()).complete)}
    @Test fun oldArchiveDoesNotAuthorizeNewUnrecognizedTail()=work{d->val src=File(d,"source").apply{writeBytes(hdr())};val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());val out=File(d,"out");ExternalMetadataTransfer.write(src,out,p,16,12,Cancel());out.appendBytes(byteArrayOf(1));assertFalse(ExternalMetadataTransfer.prepare(out,16,12,true,Cancel()).complete)}
    @Test fun overArchiveBudgetRetainsOriginal()=work{d->val src=File(d,"source").apply{writeBytes(hdr())};val bundle=PreservedMetadata.read(src,Cancel());val end=JpegCompletion.primaryEnd(src)!!;val full=bundle.copy(packets=listOf(PreservedMetadata.Packet("test",ByteArray(PreservedMetadata.MAX))));assertNull(SourceGainmapArchive.include(src,full,end,Cancel()))}
    @Test fun cancellationPropagates():Unit=work{d->val src=File(d,"source").apply{writeBytes(hdr())};assertThrows(java.util.concurrent.CancellationException::class.java){ExternalMetadataTransfer.prepare(src,16,12,true,Cancel().also{it.cancelled=true})}}
    @Test fun streamParserRequiresFullSecondaryFrame(){val b=CaptureMetadataFixtures.jpeg;assertEquals(b.size.toLong(),JpegCompletion.primaryEnd(b.inputStream(),b.size.toLong(),Cancel()))}
    @Test fun streamParserRejectsTruncation(){val b=CaptureMetadataFixtures.jpeg;assertNull(JpegCompletion.primaryEnd(b.copyOf(b.size-1).inputStream(),(b.size-1).toLong(),Cancel()))}
    @Test fun streamIoFailureNotSwallowed():Unit{assertThrows(IOException::class.java){JpegCompletion.primaryEnd(object:InputStream(){override fun read():Int=throw IOException("synthetic read failure")},100L,Cancel())}}
    @Test fun verifiedGainStillRequiresPublishedOutput(){var deleted=false;val r=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,false,Cancel(),{true},{true},{true},{deleted=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(deleted);assertEquals(OriginalAction.KEPT,r.action)}
    @Test fun verifiedGainStillRequiresOriginalFingerprint(){var deleted=false;val r=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,true,Cancel(),{true},{false},{true},{deleted=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(deleted);assertEquals(OriginalAction.CHANGED,r.action)}
    @Test fun verifiedGainStillRequiresOutputFingerprint(){var deleted=false;val r=ExternalOriginalGuard.afterPublished(ExternalOriginalPolicy.DELETE_AFTER_SAVE,true,Cancel(),{true},{true},{false},{deleted=true;OriginalOutcome(OriginalAction.DELETED,"")});assertFalse(deleted);assertEquals(OriginalAction.FAILED,r.action)}
}
