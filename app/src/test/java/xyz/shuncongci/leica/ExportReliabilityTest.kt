package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Synthetic 16x12 photographs only. No Android provider, user photo, or metadata-loss fallback. */
class ExportReliabilityTest {
    @get:Rule val temp=TemporaryFolder()
    private var index=0
    private val rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val empty get()="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$rdf\"/></x:xmpmeta>"
    private val described get()="<rdf:RDF xmlns:rdf=\"$rdf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><rdf:Description rdf:about=\"\" dc:creator=\"Fixture creator\"/></rdf:RDF>"
    private val jpeg get()=CaptureMetadataFixtures.jpeg
    private val exif get()=CaptureExif(CaptureMetadataFixtures.tiff()).normalized(16,12)
    private fun segment(marker:Int,b:ByteArray)=ByteArrayOutputStream().also{DataOutputStream(it).apply{writeByte(255);writeByte(marker);writeShort(b.size+2);write(b)}}.toByteArray()
    private fun inject(b:ByteArray,at:Int,extra:ByteArray)=b.copyOfRange(0,at)+extra+b.copyOfRange(at,b.size)
    private fun fill(b:ByteArray,at:Int=2,n:Int=3)=inject(b,at,ByteArray(n){-1})
    private fun original(xml:String?=null):ByteArray {
        val packets=segment(0xe1,"Exif\u0000\u0000".toByteArray()+CaptureMetadataFixtures.tiff())+
            (xml?.let{segment(0xe1,PreservedMetadata.XMP+it.toByteArray())}?:byteArrayOf())
        return inject(jpeg,2,packets)
    }
    // Independent marker oracle: not the production jpegHeaders being fixed.
    private data class Span(val at:Int,val marker:Int,val end:Int,val payload:ByteArray)
    private fun spans(b:ByteArray):List<Span> {
        var at=2;val list=mutableListOf<Span>()
        while(true){check((b[at].toInt() and 255)==255);while((b[at+1].toInt() and 255)==255)at++
            val marker=b[at+1].toInt() and 255;val n=((b[at+2].toInt() and 255) shl 8)+(b[at+3].toInt() and 255)
            if(marker==0xda)return list
            val end=at+n+2;list+=Span(at,marker,end,b.copyOfRange(at+4,end));at=end
        }
    }
    private fun sos(b:ByteArray)=spans(b).lastOrNull()?.end?:2
    private fun isExif(s:Span)=s.marker==0xe1 && PreservedMetadata.starts(s.payload,"Exif\u0000\u0000".toByteArray())
    private fun stripExif(b:ByteArray):ByteArray {
        val out=ByteArrayOutputStream();var at=0
        for(s in spans(b).filter(::isExif)){out.write(b,at,s.at-at);at=s.end}
        out.write(b,at,b.size-at);return out.toByteArray()
    }
    private fun paddedHeaders(b:ByteArray):ByteArray {
        var out=b;for(at in (spans(b).map{it.at}+sos(b)).distinct().sortedDescending())out=fill(out,at)
        return out
    }
    private fun hdr()=ByteArrayOutputStream().also{UltraHdrContainer.write(it,jpeg,jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray()
    private fun mpf(b:ByteArray)=spans(b).single{it.marker==0xe2 && String(it.payload,0,4)=="MPF\u0000"}
    private fun entries(s:Span):Pair<ByteBuffer,Int>{val t=CaptureExif(s.payload.copyOfRange(4,s.payload.size));val e=t.root().single{it.tag==0xb002};return ByteBuffer.wrap(s.payload).order(t.order) to (ByteBuffer.wrap(e.raw).order(t.order).getInt(8)+4)}
    private fun primary(b:ByteArray):Int {
        val s=mpf(b);val (data,at)=entries(s);val size=data.getInt(at+4);val relative=data.getInt(at+24)
        assertEquals(size,s.at+8+relative);assertEquals(255,b[size].toInt() and 255);assertEquals(216,b[size+1].toInt() and 255)
        return size
    }
    private fun padHdr(b:ByteArray,at:Int):ByteArray {
        val old=primary(b);val out=fill(b,at);val s=mpf(out);val (data,entry)=entries(s)
        data.putInt(entry+4,old+3);data.putInt(entry+24,old+3-(s.at+8));s.payload.copyInto(out,s.at+4)
        primary(out);return out
    }
    private fun files(bytes:ByteArray):Triple<File,File,File>{val d=temp.newFolder("case-${index++}");val source=File(d,"source").apply{writeBytes(bytes)};return Triple(source,File(d,"encoded"),File(d,"output"))}
    private fun copy(bytes:ByteArray):ByteArray {
        val (source,_,out)=files(bytes);CaptureMetadataContainer.write(source,out,exif,16,12,Cancel())
        assertArrayEquals(bytes,source.readBytes());assertArrayEquals(exif,CaptureMetadataContainer.readExif(out).exif)
        return out.readBytes()
    }
    private fun preserve(source:ByteArray,encoded:ByteArray=CaptureMetadataFixtures.png,check:(File)->Unit={}):File {
        val (original,plain,out)=files(source);plain.writeBytes(encoded)
        // Fresh disk read for every export, as after restarting the application.
        val bundle=PreservedMetadata.read(original,Cancel())
        CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,false,Cancel())
        assertArrayEquals(source,original.readBytes());assertArrayEquals(encoded,plain.readBytes())
        assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()))
        val e=CaptureExif(CaptureMetadataContainer.readExif(out).exif!!)
        assertEquals("FixtureLens\u0000",String(e.value(true,0xa434)!!));assertFalse(e.gps().isEmpty())
        check(out);return out
    }
    private fun merge(source:List<String>,dest:List<String> = emptyList())=PreservedMetadata.mergedXmp(source.map{it.toByteArray()},dest.map{it.toByteArray()},16,12)!!
    private fun reject(xml:String){assertThrows(Exception::class.java){merge(listOf(xml))}}
    private fun rejectJpeg(b:ByteArray){val (source,_,out)=files(b);assertThrows(IllegalArgumentException::class.java){CaptureMetadataContainer.write(source,out,exif,16,12,Cancel())};assertFalse(out.exists());assertArrayEquals(b,source.readBytes())}
    private fun hdrCheck(before:ByteArray,after:ByteArray){assertArrayEquals(before.copyOfRange(primary(before),before.size),after.copyOfRange(primary(after),after.size));assertArrayEquals(before.copyOfRange(sos(before),before.size),after.copyOfRange(sos(after),after.size))}

    @Test fun fillAfterSoiDoesNotHideCaptureExif(){val b=fill(original());val (s,_,_)=files(b);assertArrayEquals(CaptureMetadataFixtures.tiff(),CaptureMetadataContainer.readExif(s).exif)}
    @Test fun fillBetweenEveryHeaderIsAccepted(){val b=paddedHeaders(original());val (s,_,_)=files(b);assertArrayEquals(CaptureMetadataFixtures.tiff(),CaptureMetadataContainer.readExif(s).exif)}
    @Test fun fillBeforeSosDoesNotLookLikeHugeSegment(){val b=fill(original(),sos(original()));val (s,_,_)=files(b);assertTrue(CaptureMetadataContainer.readExif(s).supported)}
    @Test fun paddedSourceMetadataExportsWithoutLoss(){preserve(paddedHeaders(original()))}
    @Test fun rewrittenJpegRetainsFillAndEntropy(){val b=paddedHeaders(jpeg);assertArrayEquals(b,stripExif(copy(b)))}
    @Test fun replacingOldExifKeepsItsPrecedingFill(){val b=paddedHeaders(original());assertArrayEquals(stripExif(b),stripExif(copy(b)))}
    @Test fun onlyExifHeaderBeforeScanKeepsRemainingFill(){val b=byteArrayOf(-1,-40)+segment(0xe1,"Exif\u0000\u0000".toByteArray()+exif)+ByteArray(7){-1}+jpeg.copyOfRange(sos(jpeg),jpeg.size);assertArrayEquals(stripExif(b),stripExif(copy(b)))}
    @Test fun fillAcrossBufferedScanBoundaryIsCopied(){val b=fill(jpeg,n=20001);assertArrayEquals(b,stripExif(copy(b)))}
    @Test fun fillBeforeMpfKeepsBothIndexes(){val b=hdr();val padded=padHdr(b,mpf(b).at);hdrCheck(padded,copy(padded))}
    @Test fun fillBeforeHdrXmpKeepsRelativeMpfAnchor(){val padded=padHdr(hdr(),2);hdrCheck(padded,copy(padded))}
    @Test fun fillAfterMpfKeepsPrimaryGainIndex(){val b=hdr();val padded=padHdr(b,mpf(b).end);hdrCheck(padded,copy(padded))}
    @Test fun preservedArchiveAndEmptyXmpKeepPaddedHdr(){val b=hdr();val padded=padHdr(b,mpf(b).at);preserve(original(empty),padded){hdrCheck(padded,it.readBytes())}}
    @Test fun truncatedFillIsRejected(){rejectJpeg(byteArrayOf(-1,-40,-1,-1,-1))}
    @Test fun stuffedZeroOutsideScanIsRejected(){rejectJpeg(byteArrayOf(-1,-40,-1,0,0,2)+jpeg.copyOfRange(2,jpeg.size))}
    @Test fun segmentLengthLessThanTwoIsRejected(){rejectJpeg(byteArrayOf(-1,-40,-1,-31,0,1)+jpeg.copyOfRange(2,jpeg.size))}
    @Test fun truncatedAppPayloadIsRejected(){rejectJpeg(byteArrayOf(-1,-40,-1,-31,127,127,1))}
    @Test fun fakeMarkerInsideAppPayloadIsNotParsed(){val b=inject(jpeg,2,segment(0xe7,byteArrayOf(-1,-1,-38,0,0,-1,-39)));assertArrayEquals(b,stripExif(copy(b)))}
    @Test fun missingScanIsRejected(){rejectJpeg(byteArrayOf(-1,-40)+segment(0xe7,byteArrayOf(1,2))+byteArrayOf(-1,-39))}
    @Test fun fillCannotBypassExistingHeaderBudget(){val (s,_,_)=files(byteArrayOf(-1,-40));s.outputStream().use{it.write(byteArrayOf(-1,-40));val block=ByteArray(65536){-1};repeat(12*CaptureExif.MAX_BYTES/block.size+1){_->it.write(block)}};val e=assertThrows(IllegalArgumentException::class.java){CaptureMetadataContainer.readExif(s)};assertTrue(e.message!!.contains("超限"))}
    @Test fun truncatedSosHeaderIsRejected(){rejectJpeg(jpeg.copyOfRange(0,sos(jpeg))+byteArrayOf(-1,-38,0,8,1))}
    @Test fun emptyRdfIsValidNotMissingMetadata(){assertTrue(String(merge(listOf(empty))).contains("tiff:ImageWidth=\"16\""))}
    @Test fun emptyRdfWithWhitespaceCommentsAndPiIsValid(){val xml="<rdf:RDF xmlns:rdf=\"$rdf\">\n <!-- empty graph --><?packet test?>\t</rdf:RDF>";merge(listOf(xml))}
    @Test fun standaloneEmptyRdfRootIsValid(){merge(listOf("<rdf:RDF xmlns:rdf=\"$rdf\"/>"))}
    @Test fun emptySourceXmpExportsJpegAndArchivesRawPacket(){preserve(original(empty),jpeg)}
    @Test fun emptySourceXmpExportsPngAndArchivesRawPacket(){preserve(original(empty))}
    @Test fun emptySourceXmpExportsWebpAndArchivesRawPacket(){preserve(original(empty),CaptureMetadataFixtures.webp)}
    @Test fun emptySourceXmpExportsLosslessWebpAndArchivesRawPacket(){preserve(original(empty),CaptureMetadataFixtures.losslessWebp)}
    @Test fun emptySourceXmpDoesNotEraseDestinationHdr(){preserve(original(empty),hdr()){assertTrue(PreservedMetadata.read(it,Cancel(),PreservedMetadata.MAX*3).xmp.any{b->String(b).contains("hdrgm:Version=\"1.0\"")})}}
    @Test fun emptyAndDescriptivePacketsBothRemain(){val x=String(merge(listOf(empty,described,empty)));assertTrue(x.contains("Fixture creator"))}
    @Test fun emptyDestinationGraphDoesNotEraseSourceDescription(){assertTrue(String(merge(listOf(described),listOf(empty))).contains("Fixture creator"))}
    @Test fun missingRdfIsStillFailure(){reject("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")}
    @Test fun unsupportedTypedRdfIsNotMistakenForEmpty(){reject("<rdf:RDF xmlns:rdf=\"$rdf\" xmlns:v=\"urn:vendor\"><v:Camera v:serial=\"private\"/></rdf:RDF>")}
    @Test fun nestedDescriptionIsNotSilentlyLost(){reject("<rdf:RDF xmlns:rdf=\"$rdf\"><rdf:Bag><rdf:Description/></rdf:Bag></rdf:RDF>")}
    @Test fun nonWhitespaceRdfTextIsStillFailure(){reject("<rdf:RDF xmlns:rdf=\"$rdf\">unexpected metadata</rdf:RDF>")}
    @Test fun duplicateRdfRootsAreStillFailure(){reject("<x xmlns:rdf=\"$rdf\"><rdf:RDF/><rdf:RDF/></x>")}
    @Test fun malformedXmpIsStillFailure(){reject("<rdf:RDF xmlns:rdf=\"$rdf\">")}
    @Test fun dtdIsStillRejectedWithoutFetching(){reject("<!DOCTYPE rdf:RDF [<!ENTITY secret SYSTEM 'file:///never-open'>]><rdf:RDF xmlns:rdf=\"$rdf\">&secret;</rdf:RDF>")}
    @Test fun unknownRdfAttributeIsNotMistakenForEmpty(){reject("<rdf:RDF xmlns:rdf=\"$rdf\" xmlns:v=\"urn:vendor\" v:private=\"not discarded\"/>")}
    @Test fun serialExportsAndFreshSourceReadsDoNotPoisonLaterJobs(){repeat(3){for(b in listOf(original(),original(),paddedHeaders(original()),original(empty)))preserve(b)}}
    @Test fun invalidXmpNeverPublishesOrChangesOriginal(){val b=original("<bad/>");val (s,p,out)=files(b);p.writeBytes(jpeg);val bundle=PreservedMetadata.read(s,Cancel());assertThrows(IllegalArgumentException::class.java){CaptureMetadataContainer.writePreserved(p,out,bundle,16,12,false,Cancel())};assertFalse(out.exists());assertArrayEquals(b,s.readBytes())}
    @Test fun existingTargetIsProtectedWithEmptyXmp(){val (s,p,out)=files(original(empty));p.writeBytes(jpeg);out.writeText("keep");val bundle=PreservedMetadata.read(s,Cancel());assertThrows(IllegalStateException::class.java){CaptureMetadataContainer.writePreserved(p,out,bundle,16,12,false,Cancel())};assertEquals("keep",out.readText())}
    @Test fun cancelledPaddedExportLeavesNoPartial(){val b=paddedHeaders(original());val (s,p,out)=files(b);p.writeBytes(jpeg);val bundle=PreservedMetadata.read(s,Cancel());assertThrows(CancellationException::class.java){CaptureMetadataContainer.writePreserved(p,out,bundle,16,12,false,Cancel().apply{cancelled=true})};assertFalse(out.exists());assertArrayEquals(b,s.readBytes())}
}
