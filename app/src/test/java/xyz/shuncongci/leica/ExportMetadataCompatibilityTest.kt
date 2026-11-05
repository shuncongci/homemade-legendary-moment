package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic metadata only. Degraded export is not metadata verification or original-delete consent. */
class ExportMetadataCompatibilityTest {
    @get:Rule val temp=TemporaryFolder()
    private fun file(b:ByteArray)=temp.newFile().apply{writeBytes(b)}
    private fun out()=File(temp.root,"out-${java.util.UUID.randomUUID()}")
    private fun bundle(e:ByteArray?=CaptureMetadataFixtures.tiff(),x:List<ByteArray> = emptyList(),p:List<PreservedMetadata.Packet> = emptyList())=
        PreservedMetadata.Bundle(p,e,x,"JPEG")
    private fun bad(order:ByteOrder=ByteOrder.BIG_ENDIAN,at:Int=156)=CaptureMetadataFixtures.tiff(order).also{ByteBuffer.wrap(it).order(order).putShort(at,0)}
    private fun segment(marker:Int,b:ByteArray)=byteArrayOf(-1,marker.toByte(),((b.size+2)/256).toByte(),((b.size+2)%256).toByte())+b
    private fun jpeg(e:ByteArray,extra:ByteArray=byteArrayOf()):ByteArray {val j=CaptureMetadataFixtures.jpeg;return j.copyOfRange(0,2)+segment(0xe1,"Exif\u0000\u0000".toByteArray()+e)+extra+j.copyOfRange(2,j.size)}
    private fun prepare(b:PreservedMetadata.Bundle,badge:Boolean=true)=ExportMetadataCompatibility.prepare(b,16,12,badge,Cancel())
    private fun exif(b:ByteArray)=CaptureExif(b)
    private fun assertBadge(b:ByteArray,enabled:Boolean){val flag=exif(b).exif().single{it.tag==0x88b0};assertEquals(1,flag.type);assertEquals(1L,flag.count);assertEquals(if(enabled)1 else 0,flag.value[0].toInt() and 1)}
    private fun fields(b:ByteArray){val e=exif(b);assertArrayEquals("Fixture\u0000".toByteArray(),e.value(false,0x10f));assertArrayEquals("FixtureLens\u0000".toByteArray(),e.value(true,0xa434));assertEquals(3,e.gps().size)}
    private fun write(b:PreservedMetadata.Bundle,encoded:ByteArray=CaptureMetadataFixtures.jpeg,badge:Boolean=true):File {
        val p=prepare(b,badge);val result=out();ExportMetadataCompatibility.write(file(encoded),result,p,16,12,badge,Cancel());assertArrayEquals(p.exif,CaptureMetadataContainer.readExif(result).exif);assertBadge(p.exif,badge);return result
    }
    private fun assertPixels(encoded:ByteArray,result:File){
        val input=file(encoded)
        if(encoded[0]==(-1).toByte()){
            fun tail(b:ByteArray):ByteArray {var at=2;while(true){while(b[at+1]==(-1).toByte())at++;if(b[at+1]==(-38).toByte())return b.copyOfRange(at,b.size);at+=2+((b[at+2].toInt() and 255)*256+(b[at+3].toInt() and 255))}}
            assertArrayEquals(tail(encoded),tail(result.readBytes()))
        }else{
            val png=encoded[0]==(-119).toByte()
            fun pixels(f:File)=RandomAccessFile(f,"r").use{r->CaptureMetadataContainer.chunks(r,png).filter{if(png)it.kind=="IDAT" else it.kind in setOf("VP8 ","VP8L","ALPH")}.map{c->r.seek(c.offset+8);ByteArray(c.size.toInt()).also{r.readFully(it)}.toList()}}
            assertEquals(pixels(input),pixels(result))
        }
    }
    private fun carrier(encoded:ByteArray){val b=bundle(bad(),p=listOf(PreservedMetadata.Packet("J:e7",byteArrayOf(9,8,7))));val r=write(b,encoded);assertPixels(encoded,r);assertArrayEquals(PreservedMetadata.archive(b),PreservedMetadata.readArchive(r,Cancel()))}
    private fun external(source:ByteArray):ExternalMetadataTransfer.Prepared=ExternalMetadataTransfer.prepareForExport(file(source),16,12,true,Cancel())
    @Test fun healthyPreparationIsNotDegraded(){val p=prepare(bundle());assertFalse(p.degraded);fields(p.exif);assertBadge(p.exif,true)}
    @Test fun healthyExifRemainsByteExactToStrictNormalization(){val e=CaptureExif(CaptureMetadataFixtures.tiff());assertArrayEquals(e.normalized(16,12,XiaomiLeicaBadge.patches(e,true)),prepare(bundle()).exif)}
    @Test fun noExifStillGetsEnabledBadge(){assertBadge(prepare(bundle(null)).exif,true)}
    @Test fun noExifStillGetsDisabledBadge(){assertBadge(prepare(bundle(null),false).exif,false)}
    @Test fun unknownExifTypeDoesNotBlockReadableFields(){val p=prepare(bundle(bad()));assertTrue(p.degraded);fields(p.exif);assertNull(exif(p.exif).value(true,0xc123))}
    @Test fun littleEndianUnknownTypePreservesReadableFields(){val p=prepare(bundle(bad(ByteOrder.LITTLE_ENDIAN)));assertTrue(p.degraded);fields(p.exif);assertEquals(ByteOrder.LITTLE_ENDIAN,exif(p.exif).order)}
    @Test fun unknownRootTypeIsSkipped(){val p=prepare(bundle(bad(at=60)));assertTrue(p.degraded);fields(p.exif)}
    @Test fun badGpsFieldRetainsOtherGpsFields(){val p=prepare(bundle(bad(at=348)));assertTrue(p.degraded);assertEquals(2,exif(p.exif).gps().size)}
    @Test fun makerNoteRawBytesAndOffsetsSurviveNonCompactRecovery(){val original=exif(CaptureMetadataFixtures.tiff()).exif().single{it.tag==0x927c};val actual=exif(prepare(bundle(bad())).exif).exif().single{it.tag==0x927c};assertArrayEquals(original.raw,actual.raw);assertArrayEquals(original.value,actual.value)}
    @Test fun invalidByteOrderGetsMinimalMetadata(){val p=prepare(bundle(byteArrayOf(0,0,0,0,0,0,0,0)));assertTrue(p.degraded);assertBadge(p.exif,true)}
    @Test fun unsupportedTiffVersionGetsMinimalMetadata(){val e=CaptureMetadataFixtures.tiff();e[3]=43;val p=prepare(bundle(e));assertTrue(p.degraded);assertBadge(p.exif,true)}
    @Test fun truncatedExifGetsMinimalMetadata(){val p=prepare(bundle(byteArrayOf(77)));assertTrue(p.degraded);assertBadge(p.exif,true)}
    @Test fun oversizedExifDoesNotExceedActiveCapacity(){val p=prepare(bundle(ByteArray(CaptureExif.MAX_BYTES+1)));assertTrue(p.degraded);assertTrue(p.exif.size<=65527)}
    @Test fun outOfRangeFieldIsNotDereferenced(){val e=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(e).putInt(162,Int.MAX_VALUE);val p=prepare(bundle(e));assertTrue(p.degraded);fields(p.exif)}
    @Test fun giantCountIsNotAllocated(){val e=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(e).putInt(158,-1);val p=prepare(bundle(e));assertTrue(p.degraded);fields(p.exif)}
    @Test fun cyclicDirectoryIsDisconnected(){val e=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(e).putInt(42,8);val p=prepare(bundle(e));assertTrue(p.degraded);assertBadge(p.exif,true);exif(p.exif).root()}
    @Test fun invalidInteropIsNotNormalizedTwice(){val e=CaptureMetadataFixtures.tiff();val b=ByteBuffer.wrap(e);b.putShort(80,8);b.putShort(166,0xa005.toShort());b.putShort(168,4);b.putInt(170,1);b.putInt(174,99999);val p=prepare(bundle(e));assertTrue(p.degraded);write(bundle(e))}
    @Test fun incompatibleBadgeTypeIsReplacedOnlyInActiveExif(){val e=CaptureMetadataFixtures.tiff();val b=ByteBuffer.wrap(e);b.putShort(154,0x88b0.toShort());b.putShort(156,3);b.putInt(158,1);val p=prepare(bundle(e));assertTrue(p.degraded);assertBadge(p.exif,true);assertEquals(3,ByteBuffer.wrap(e).getShort(156).toInt())}
    @Test fun capacityFallbackKeepsStandardCaptureValues(){val e=bad().copyOf(66000);val p=prepare(bundle(e));assertTrue(p.degraded);assertTrue(p.exif.size<=65527);fields(p.exif)}
    @Test fun badXmlIsArchivedButNotActivated(){val xml="<broken".toByteArray();val b=bundle(x=listOf(xml),p=listOf(PreservedMetadata.Packet("J:e1",PreservedMetadata.XMP+xml)));val p=prepare(b);assertTrue(p.degraded);assertTrue(p.active.xmp.isEmpty());assertArrayEquals(PreservedMetadata.archive(b),PreservedMetadata.readArchive(write(b),Cancel()))}
    @Test fun dtdIsNeverFetchedOrActivated(){val xml="<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///not-opened'>]><x>&e;</x>".toByteArray();val p=prepare(bundle(x=listOf(xml)));assertTrue(p.degraded);assertTrue(p.active.xmp.isEmpty())}
    @Test fun readableXmpRemainsActiveAlongsideMalformedPacket(){val good="<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description xmlns:dc='http://purl.org/dc/elements/1.1/' dc:title='fixture'/></rdf:RDF></x:xmpmeta>".toByteArray();val p=prepare(bundle(x=listOf(good,"invalid".toByteArray())));assertTrue(p.degraded);assertEquals(1,p.active.xmp.size);assertArrayEquals(good,p.active.xmp[0])}
    @Test fun malformedIptcIsArchivedOnly(){val iptc="Photoshop 3.0\u0000bad".toByteArray();val b=bundle(p=listOf(PreservedMetadata.Packet("J:ed",iptc)));val p=prepare(b);assertTrue(p.degraded);assertTrue(p.active.packets.isEmpty());assertArrayEquals(PreservedMetadata.archive(b),PreservedMetadata.readArchive(write(b),Cancel()))}
    @Test fun jpegCompressionIsUnchanged(){carrier(CaptureMetadataFixtures.jpeg)}
    @Test fun pngCompressionIsUnchanged(){carrier(CaptureMetadataFixtures.png)}
    @Test fun lossyWebpCompressionIsUnchanged(){carrier(CaptureMetadataFixtures.webp)}
    @Test fun losslessWebpCompressionIsUnchanged(){carrier(CaptureMetadataFixtures.losslessWebp)}
    @Test fun hdrBothCompressedJpegsAndGainIndexesRemainValid(){val b=ByteArrayOutputStream();UltraHdrContainer.write(b,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f));carrier(b.toByteArray())}
    @Test fun disabledBadgeSurvivesDegradedWrite(){val r=write(bundle(bad()),badge=false);assertBadge(CaptureMetadataContainer.readExif(r).exif!!,false)}
    @Test fun strictReaderStillRejectsBadSource(){assertThrows(IllegalStateException::class.java){ExternalMetadataTransfer.prepare(file(jpeg(bad())),16,12,true,Cancel())}}
    @Test fun externalDegradedSaveNeverEarnsDeletion(){val p=external(jpeg(bad()));assertFalse(p.complete);val target=out();ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),target,p,16,12,Cancel());assertTrue(target.isFile);assertFalse(p.verified);assertFalse(p.deletionReady);assertFalse(ExternalDeletionConsent.canRemove(ExternalOriginalPolicy.DELETE_AFTER_SAVE,ExternalDeletionConsent.VERSION,p.verified))}
    @Test fun externalHealthySaveStillEarnsExistingVerification(){val p=external(jpeg(CaptureMetadataFixtures.tiff()));ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),out(),p,16,12,Cancel());assertTrue(p.verified);assertTrue(p.complete);assertTrue(p.deletionReady)}
    @Test fun malformedSourceHeaderStillAllowsEncodedPhotoButBlocksDeletion(){val p=external(byteArrayOf(-1,-40,-1,-31,0,20,1));val target=out();ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),target,p,16,12,Cancel());assertTrue(target.exists());assertFalse(p.verified)}
    @Test fun unsupportedSourceStillGetsBadgeAndNoDeletion(){val p=external("opaque-readable-source".toByteArray());ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),out(),p,16,12,Cancel());assertFalse(p.verified);assertFalse(p.complete);assertBadge(p.exif,true)}
    @Test fun missingOriginalIsRealIoFailure(){assertThrows(FileNotFoundException::class.java){ExportMetadataCompatibility.prepare(out(),16,12,true,Cancel())}}
    @Test fun missingEncodedIsRealIoFailureAndCleansTarget(){val target=out();assertThrows(FileNotFoundException::class.java){ExportMetadataCompatibility.write(out(),target,prepare(bundle(bad())),16,12,true,Cancel())};assertFalse(target.exists())}
    @Test fun invalidEncodedContainerIsNotMetadataFallback(){val target=out();assertThrows(Exception::class.java){ExportMetadataCompatibility.write(file("not a photo".toByteArray()),target,prepare(bundle(bad())),16,12,true,Cancel())};assertFalse(target.exists())}
    @Test fun existingTargetIsNeverDeletedOrOverwritten(){val target=file("keep".toByteArray());assertThrows(IllegalStateException::class.java){ExportMetadataCompatibility.write(file(CaptureMetadataFixtures.jpeg),target,prepare(bundle()),16,12,true,Cancel())};assertEquals("keep",target.readText())}
    @Test fun cancelledPreparationDoesNotBecomeMinimalExif(){assertThrows(CancellationException::class.java){ExportMetadataCompatibility.prepare(bundle(bad()),16,12,true,Cancel().also{it.cancelled=true})}}
    @Test fun cancelledWriteLeavesNoTarget(){val target=out();val p=prepare(bundle(bad()));assertThrows(CancellationException::class.java){ExportMetadataCompatibility.write(file(CaptureMetadataFixtures.jpeg),target,p,16,12,true,Cancel().also{it.cancelled=true})};assertFalse(target.exists())}
    @Test fun optionalDoesNotSwallowCancellation(){assertThrows(CancellationException::class.java){ExportMetadataCompatibility.optional<Unit>{throw CancellationException()}}}
    @Test fun optionalDoesNotSwallowWrappedCancellation(){assertThrows(CancellationException::class.java){ExportMetadataCompatibility.optional<Unit>{throw IllegalStateException(CancellationException())}}}
    @Test fun optionalDoesNotSwallowRealIo(){assertThrows(IOException::class.java){ExportMetadataCompatibility.optional<Unit>{throw IOException()}}}
    @Test fun optionalDoesNotSwallowWrappedIo(){assertThrows(IOException::class.java){ExportMetadataCompatibility.optional<Unit>{throw IllegalStateException(IOException())}}}
    @Test fun optionalDoesNotSwallowVmErrors(){assertThrows(OutOfMemoryError::class.java){ExportMetadataCompatibility.optional<Unit>{throw OutOfMemoryError("synthetic")}}}
    @Test fun optionalDoesNotSwallowWrappedVmErrors(){assertThrows(OutOfMemoryError::class.java){ExportMetadataCompatibility.optional<Unit>{throw IllegalStateException(OutOfMemoryError("synthetic"))}}}
    @Test fun optionalDoesNotSwallowSecurity(){assertThrows(SecurityException::class.java){ExportMetadataCompatibility.optional<Unit>{throw SecurityException()}}}
    @Test fun invalidDimensionsRemainProgrammingError(){assertThrows(IllegalArgumentException::class.java){ExportMetadataCompatibility.prepare(bundle(),0,12,true,Cancel())}}
    @Test fun sourceAndEncodedFilesStayUnchanged(){val source=file(jpeg(bad()));val encoded=file(CaptureMetadataFixtures.jpeg);val a=source.readBytes();val b=encoded.readBytes();val p=ExportMetadataCompatibility.prepare(source,16,12,true,Cancel());ExportMetadataCompatibility.write(encoded,out(),p,16,12,true,Cancel());assertArrayEquals(a,source.readBytes());assertArrayEquals(b,encoded.readBytes())}
    @Test fun boundedArchiveRecoveryDoesNotClaimCompleteness(){val extras=ByteArrayOutputStream();repeat(71){extras.write(segment(0xe7,ByteArray(60000){(it%199).toByte()}))};val p=external(jpeg(CaptureMetadataFixtures.tiff(),extras.toByteArray()));assertFalse(p.complete);assertTrue(p.bundle!!.packets.sumOf{it.bytes.size}<=PreservedMetadata.MAX);ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),out(),p,16,12,Cancel());assertFalse(p.verified)}
}
