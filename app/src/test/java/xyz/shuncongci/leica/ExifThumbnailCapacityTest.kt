package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Entirely synthetic; no private photograph, GPS or vendor metadata fixture. */
class ExifThumbnailCapacityTest {
    private val be=ByteOrder.BIG_ENDIAN
    private val le=ByteOrder.LITTLE_ENDIAN
    private val cut=1024
    private val thumb=512
    private fun e(b:ByteBuffer,at:Int,tag:Int,type:Int,count:Int,value:Int){
        b.position(at);b.putShort(tag.toShort());b.putShort(type.toShort());b.putInt(count)
        if(type==3 && count==1){b.putShort(value.toShort());b.putShort(0)}else b.putInt(value)
    }
    private fun fixture(o:ByteOrder=be,size:Int=65520):ByteArray {
        val bytes=CaptureMetadataFixtures.tiff(o).copyOf(size);val b=ByteBuffer.wrap(bytes).order(o)
        // Remove source strip-pointer namespace. The fifth IFD0 entry is now source width.
        e(b,58,0x100,4,1,640);b.putInt(70,thumb);b.putShort(thumb,3)
        e(b,514,0x103,3,1,6);e(b,526,0x201,4,1,cut);e(b,538,0x202,4,1,size-cut)
        val jpeg=CaptureMetadataFixtures.jpeg;val padding=size-cut-jpeg.size-4
        require(padding>=0 && padding<=65531)
        val image=ByteArrayOutputStream().also{out->
            out.write(jpeg,0,2);DataOutputStream(out).apply{writeShort(0xfffe);writeShort(padding+2);write(ByteArray(padding){(it%251).toByte()})};out.write(jpeg,2,jpeg.size-2)
        }.toByteArray();assertEquals(size-cut,image.size);image.copyInto(bytes,cut)
        return bytes
    }
    private fun buffer(a:ByteArray)=ByteBuffer.wrap(a).order(CaptureExif(a).order)
    private fun normalize(a:ByteArray,badge:Boolean=false):ByteArray {val p=CaptureExif(a);return p.normalized(16,12,XiaomiLeicaBadge.patches(p,badge))}
    private fun preserved(a:ByteArray,z:ByteArray){
        val x=CaptureExif(a);val y=CaptureExif(z)
        for(tag in listOf(0x927c,0xa434,0xc123,0x829a,0x920a)){
            val u=x.exif().single{it.tag==tag};val v=y.exif().single{it.tag==tag};assertArrayEquals(u.raw,v.raw);assertArrayEquals(u.value,v.value)
        }
        x.gps().zip(y.gps()).forEach{(u,v)->assertArrayEquals(u.raw,v.raw);assertArrayEquals(u.value,v.value)}
        assertArrayEquals(x.value(false,0x10f),y.value(false,0x10f));assertArrayEquals(x.root().single{it.tag==0x8825}.raw,y.root().single{it.tag==0x8825}.raw)
    }
    private fun compact(o:ByteOrder,badge:Boolean=false){
        val a=fixture(o);val copy=a.copyOf();val z=normalize(a,badge)
        assertTrue(z.size<3000);preserved(a,z);assertArrayEquals(copy,a)
        val expected=a.copyOf(cut);buffer(expected).putInt(70,0)
        assertArrayEquals(expected.copyOfRange(8,cut),z.copyOfRange(8,cut))
        val p=CaptureExif(z);val b=buffer(z);val root=b.getInt(4);assertEquals(0,b.getInt(root+2+p.root().size*12))
        assertEquals(if(badge)1 else 0,p.value(true,XiaomiLeicaBadge.TAG)!![0].toInt() and 1)
    }
    @Test fun bigEndianLargeThumbnail(){compact(be)}
    @Test fun littleEndianLargeThumbnail(){compact(le)}
    @Test fun badgeOnBigEndian(){compact(be,true)}
    @Test fun badgeOnLittleEndian(){compact(le,true)}
    private fun padded(o:ByteOrder=be):ByteArray=fixture(o).also{a->val j=CaptureMetadataFixtures.jpeg;j.copyInto(a,cut);a.fill(0,cut+j.size,a.size)}
    @Test fun bigEndianZeroPaddedThumbnail(){val a=padded();val z=normalize(a);assertTrue(z.size<3000);preserved(a,z)}
    @Test fun littleEndianZeroPaddedThumbnail(){val a=padded(le);val z=normalize(a,true);assertTrue(z.size<3000);preserved(a,z)}
    @Test fun nonzeroByteInThumbnailPaddingPreventsTrim(){val a=padded();a[a.lastIndex]=1;assertTrue(normalize(a).size>=a.size)}
    @Test fun secondJpegInThumbnailAllocationPreventsTrim(){val a=padded();CaptureMetadataFixtures.jpeg.copyInto(a,cut+2000);assertTrue(normalize(a).size>=a.size)}
    @Test fun sharedValueInZeroPaddingPreventsTrim(){val a=padded();buffer(a).putInt(162,cut+2000);assertTrue(normalize(a).size>=a.size)}
    @Test fun sharedLongReferenceInZeroPaddingPreventsTrim(){val a=padded();e(buffer(a),154,0xc123,4,1,cut+2000);assertTrue(normalize(a).size>=a.size)}
    @Test fun nonSrgbStillPreservesCaptureBytes(){val a=fixture();val z=CaptureExif(a).normalized(16,12,outputSrgb=false);preserved(a,z);assertEquals(65535,ByteBuffer.wrap(CaptureExif(z).value(true,0xa001)!!).order(be).short.toInt() and 65535)}
    @Test fun smallPacketsKeepOriginalPrefix(){val a=fixture(size=3000);val z=normalize(a);assertTrue(z.size>a.size);assertArrayEquals(a.copyOfRange(8,a.size),z.copyOfRange(8,a.size))}
    @Test fun noThumbnailDoesNotTrimUnknownTail(){val a=fixture();buffer(a).putInt(70,0);val z=normalize(a);assertTrue(z.size>=a.size);preserved(a,z)}
    @Test fun secondNormalizationRetainsFields(){val a=fixture();val z=normalize(normalize(a));assertTrue(z.size<4000);preserved(a,z)}
    private fun noTrim(change:(ByteArray)->Unit){val a=fixture();change(a);val z=normalize(a);assertTrue("Must retain ambiguous source bytes",z.size>=a.size);assertArrayEquals(a.copyOfRange(8,a.size),z.copyOfRange(8,a.size))}
    @Test fun trailingBytePreventsTrim(){val a=fixture().copyOf(65521);assertTrue(normalize(a).size>=a.size)}
    @Test fun thumbnailNextImagePreventsTrim(){noTrim{buffer(it).putInt(550,8)}}
    @Test fun thumbnailIfdCyclePreventsTrim(){noTrim{buffer(it).putInt(70,8)}}
    @Test fun unknownThumbnailTagPreventsTrim(){noTrim{buffer(it).putShort(514,0xc777.toShort())}}
    @Test fun missingCompressionPreventsTrim(){noTrim{buffer(it).putShort(514,0x112)}}
    @Test fun nonJpegCompressionPreventsTrim(){noTrim{buffer(it).putShort(522,1)}}
    @Test fun wrongOffsetTypePreventsTrim(){noTrim{buffer(it).putShort(528,9)}}
    @Test fun unsignedLengthOverflowPreventsTrim(){noTrim{buffer(it).putInt(546,-1)}}
    @Test fun truncatedThumbnailDirectoryPreventsTrim(){noTrim{buffer(it).putInt(70,it.size-1)}}
    @Test fun outOfBoundsThumbnailPointerPreventsTrim(){noTrim{buffer(it).putInt(70,-1)}}
    @Test fun corruptThumbnailCountPreventsTrim(){noTrim{buffer(it).putShort(512,5000)}}
    @Test fun duplicateThumbnailTagPreventsTrim(){noTrim{buffer(it).putShort(538,0x201)}}
    @Test fun missingSoiPreventsTrim(){noTrim{it[cut]=0}}
    @Test fun missingEoiPreventsTrim(){noTrim{it[it.lastIndex]=0}}
    @Test fun earlierEoiAndUnknownTrailerPreventsTrim(){noTrim{val j=CaptureMetadataFixtures.jpeg;j.copyInto(it,cut)}}
    @Test fun thumbnailPointerBeforeOwnDirectoryEndPreventsTrim(){noTrim{buffer(it).putInt(534,520).putInt(546,it.size-520)}}
    @Test fun sharedUnknownExifValuePreventsTrim(){noTrim{buffer(it).putInt(162,cut+20)}}
    @Test fun sharedMakerNotePreventsTrim(){noTrim{buffer(it).putInt(126,cut+20)}}
    @Test fun sharedGpsValuePreventsTrim(){noTrim{buffer(it).putInt(354,cut+20)}}
    @Test fun sharedRootStringPreventsTrim(){noTrim{buffer(it).putInt(18,cut+20)}}
    @Test fun sharedOldImageLinkPreventsTrim(){noTrim{buffer(it).putInt(162,68)}}
    @Test fun unknownInlineLongTailReferencePreventsTrim(){noTrim{e(buffer(it),154,0xc123,4,1,cut+20)}}
    @Test fun unknownLongArrayTailReferencePreventsTrim(){noTrim{e(buffer(it),154,0xc123,4,2,280);buffer(it).putInt(280,cut+20).putInt(284,0)}}
    @Test fun exifDirectoryInTailPreventsTrim(){noTrim{it.copyOfRange(80,166).copyInto(it,cut+100);buffer(it).putInt(42,cut+100)}}
    @Test fun gpsDirectoryInTailPreventsTrim(){noTrim{it.copyOfRange(320,358).copyInto(it,cut+100);buffer(it).putInt(54,cut+100)}}
    @Test fun interopDirectoryInTailPreventsTrim(){noTrim{val b=buffer(it);b.putShort(80,8);e(b,166,0xa005,4,1,cut+100);b.putShort(cut+100,1);e(b,cut+102,1,2,4,0x52393800)}}
    @Test fun unknownIfdPointerTypePreventsTrim(){noTrim{e(buffer(it),154,0xc123,13,1,cut)}}
    @Test fun subIfdNamespacePreventsTrim(){noTrim{e(buffer(it),58,0x14a,4,1,cut)}}
    @Test fun overlappingThumbnailAndRootDirectoryPreventsTrim(){noTrim{buffer(it).putInt(70,8)}}
    @Test fun sharedThumbnailResolutionPreventsTrim(){noTrim{val b=buffer(it);b.putShort(512,4);e(b,550,0x11a,5,1,cut+30)}}
    @Test fun validInteropIsRetained(){val a=fixture();val b=buffer(a);b.putShort(80,8);e(b,166,0xa005,4,1,600);b.putShort(600,1);e(b,602,1,2,4,0x52393800);val z=normalize(a);assertTrue(z.size<3000);preserved(a,z);assertNotNull(CaptureExif(z).value(true,0xa005))}
    @Test fun invalidInteropStillStrict(){val a=fixture();val b=buffer(a);b.putShort(80,8);e(b,166,0xa005,4,1,-1);assertThrows(IllegalArgumentException::class.java){normalize(a)}}
    @Test fun invalidInteropManualWarningDoesNotEnableUnsafeTrim(){val a=fixture();val b=buffer(a);b.putShort(80,8);e(b,166,0xa005,4,1,-1);var warnings=0;val z=CaptureExif(a).normalized(16,12,onInvalidInterop={warnings++});assertEquals(1,warnings);assertTrue(z.size>=a.size)}
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("exif-thumbnail-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun source(t:ByteArray):ByteArray {val j=CaptureMetadataFixtures.jpeg;val p="Exif\u0000\u0000".toByteArray()+t;require(p.size<=65533);return j.copyOfRange(0,2)+ByteArrayOutputStream().also{DataOutputStream(it).apply{writeShort(0xffe1);writeShort(p.size+2);write(p)}}.toByteArray()+j.copyOfRange(2,j.size)}
    private fun hdr()=ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray()
    private fun tail(f:File)=RandomAccessFile(f,"r").use{r->val s=CaptureMetadataContainer.jpegHeaders(r).last();val at=s.offset+s.total;r.seek(at);ByteArray((r.length()-at).toInt()).also{r.readFully(it)}}
    private fun export(encoded:ByteArray,badge:Boolean=false){work{d->
        val t=fixture();val raw=source(t);val origin=File(d,"source").apply{writeBytes(raw)};val plain=File(d,"plain").apply{writeBytes(encoded)};val out=File(d,"out");val bundle=PreservedMetadata.read(origin,Cancel())
        CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,badge,Cancel());val z=CaptureMetadataContainer.readExif(out).exif!!
        assertTrue(z.size<3000);preserved(t,z);assertArrayEquals(raw,origin.readBytes());assertArrayEquals(encoded,plain.readBytes());assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()))
        if(encoded[0]==(-1).toByte())assertArrayEquals(tail(plain),tail(out))
    }}
    @Test fun jpegExportAndOriginalArchive(){export(CaptureMetadataFixtures.jpeg)}
    @Test fun pngExportAndOriginalArchive(){export(CaptureMetadataFixtures.png)}
    @Test fun webpExportAndOriginalArchive(){export(CaptureMetadataFixtures.webp)}
    @Test fun losslessWebpExportAndOriginalArchive(){export(CaptureMetadataFixtures.losslessWebp)}
    @Test fun hdrExportRetainsEntropyGainAndMpf(){export(hdr())}
    @Test fun jpegBadgeEnabledExport(){export(CaptureMetadataFixtures.jpeg,true)}
    @Test fun externalModeVerifiesBothBadgeChoices(){work{d->val a=fixture();val raw=source(a);val origin=File(d,"source").apply{writeBytes(raw)};val plain=File(d,"plain").apply{writeBytes(CaptureMetadataFixtures.jpeg)};for(badge in listOf(false,true)){val p=ExternalMetadataTransfer.prepare(origin,16,12,badge,Cancel());assertFalse(p.verified);assertTrue(p.complete);val out=File(d,"out-$badge");ExternalMetadataTransfer.write(plain,out,p,16,12,Cancel());assertTrue(p.verified);assertTrue(p.deletionReady);preserved(a,CaptureMetadataContainer.readExif(out).exif!!)};assertArrayEquals(raw,origin.readBytes())}}
    @Test fun unresolvedCapacityFailsAndCleansOutput(){work{d->val a=fixture();buffer(a).putInt(70,0);val original=File(d,"source").apply{writeBytes(source(a))};val plain=File(d,"plain").apply{writeBytes(CaptureMetadataFixtures.jpeg)};val p=ExternalMetadataTransfer.prepare(original,16,12,false,Cancel());val out=File(d,"out");val x=assertThrows(IllegalArgumentException::class.java){ExternalMetadataTransfer.write(plain,out,p,16,12,Cancel())};assertTrue(x.message!!.contains("APP1"));assertFalse(out.exists());assertFalse(p.verified);assertArrayEquals(source(a),original.readBytes())}}
    @Test fun existingOutputIsNeverOverwritten(){work{d->val origin=File(d,"source").apply{writeBytes(source(fixture()))};val p=ExternalMetadataTransfer.prepare(origin,16,12,false,Cancel());val out=File(d,"out").apply{writeText("keep")};assertThrows(IllegalStateException::class.java){ExternalMetadataTransfer.write(origin,out,p,16,12,Cancel())};assertEquals("keep",out.readText());assertFalse(p.verified)}}
    @Test fun cancellationDoesNotPublish(){work{d->val origin=File(d,"source").apply{writeBytes(source(fixture()))};val p=ExternalMetadataTransfer.prepare(origin,16,12,false,Cancel());val out=File(d,"out");assertThrows(CancellationException::class.java){ExternalMetadataTransfer.write(origin,out,p,16,12,Cancel().also{it.cancelled=true})};assertFalse(out.exists());assertFalse(p.verified)}}
    @Test fun realIoErrorPropagatesAndClearsVerified(){work{d->val origin=File(d,"source").apply{writeBytes(source(fixture()))};val p=ExternalMetadataTransfer.prepare(origin,16,12,false,Cancel());val out=File(d,"out");assertThrows(FileNotFoundException::class.java){ExternalMetadataTransfer.write(File(d,"missing"),out,p,16,12,Cancel())};assertFalse(out.exists());assertFalse(p.verified)}}
    @Test fun app1ExactCapacityAndOneByteOverRemainEnforced(){work{d->val plain=File(d,"plain").apply{writeBytes(CaptureMetadataFixtures.jpeg)};val t=CaptureExif.empty().normalized(16,12);val atLimit=t.copyOf(65527);val out=File(d,"limit");CaptureMetadataContainer.write(plain,out,atLimit,16,12,Cancel());assertEquals(65527,CaptureMetadataContainer.readExif(out).exif!!.size);val bad=File(d,"over");assertThrows(IllegalArgumentException::class.java){CaptureMetadataContainer.write(plain,bad,atLimit.copyOf(65528),16,12,Cancel())};assertFalse(bad.exists())}}
}
