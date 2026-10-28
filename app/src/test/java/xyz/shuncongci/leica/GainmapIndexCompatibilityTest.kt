package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files

/** Synthetic MPF mismatch reproducer: real camera evidence has a stale first-image size,
 * but a correct secondary absolute offset and byte count. Never authorize an unknown tail. */
class GainmapIndexCompatibilityTest {
    private fun hdr()=ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray()
    private fun index(b:ByteArray)= (0..b.size-4).first{b[it]==77.toByte() && b[it+1]==80.toByte() && b[it+2]==70.toByte() && b[it+3]==0.toByte()}+4+50
    private fun field(b:ByteArray,n:Int,value:Int):ByteArray=b.copyOf().also{ByteBuffer.wrap(it).putInt(index(it)+n,value)}
    private fun stale():ByteArray=hdr().let{field(it,4,ByteBuffer.wrap(it).getInt(index(it)+4)-32)}
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("gain-index-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun export(encoded:ByteArray)=work{d->
        val raw=stale();val src=File(d,"source").apply{writeBytes(raw)};val plain=File(d,"plain").apply{writeBytes(encoded)};val out=File(d,"out")
        val end=JpegCompletion.primaryEnd(src)!!;assertTrue(ByteBuffer.wrap(raw).getInt(index(raw)+4)<end)
        val p=ExternalMetadataTransfer.prepare(src,16,12,true,Cancel());assertTrue(p.complete);assertFalse(p.deletionReady)
        ExternalMetadataTransfer.write(plain,out,p,16,12,Cancel());assertTrue(p.deletionReady)
        val a=PreservedMetadata.unarchive(PreservedMetadata.readArchive(out,Cancel()))
        assertArrayEquals(raw.copyOfRange(end.toInt(),raw.size),a.single{it.kind==SourceGainmapArchive.KIND}.bytes)
        val before=PreservedMetadata.read(src,Cancel()).packets
        before.forEachIndexed{i,v->assertEquals(v.kind,a[i].kind);assertArrayEquals(v.bytes,a[i].bytes)}
        assertArrayEquals(raw,src.readBytes());assertArrayEquals(encoded,plain.readBytes())
    }
    private fun retained(raw:ByteArray)=work{d->val src=File(d,"source").apply{writeBytes(raw)};assertFalse(ExternalMetadataTransfer.prepare(src,16,12,true,Cancel()).complete)}
    @Test fun stalePrimaryToJpeg(){export(CaptureMetadataFixtures.jpeg)}
    @Test fun stalePrimaryToPng(){export(CaptureMetadataFixtures.png)}
    @Test fun stalePrimaryToWebp(){export(CaptureMetadataFixtures.webp)}
    @Test fun stalePrimaryToLosslessWebp(){export(CaptureMetadataFixtures.losslessWebp)}
    @Test fun stalePrimaryToHdr(){export(hdr())}
    @Test fun zeroPrimaryStillRetained(){retained(field(stale(),4,0))}
    @Test fun oversizedPrimaryStillRetained(){val b=stale();retained(field(b,4,b.size))}
    @Test fun nonzeroPrimaryOffsetStillRetained(){retained(field(stale(),8,1))}
    @Test fun badGainOffsetStillRetained(){val b=stale();retained(field(b,24,ByteBuffer.wrap(b).getInt(index(b)+24)-1))}
    @Test fun badGainLengthStillRetained(){val b=stale();retained(field(b,20,ByteBuffer.wrap(b).getInt(index(b)+20)-1))}
    @Test fun extraTailStillRetained(){retained(stale()+byteArrayOf(1,2,3))}
    @Test fun truncatedGainStillRetained(){val b=stale();retained(b.copyOf(b.size-1))}
}
