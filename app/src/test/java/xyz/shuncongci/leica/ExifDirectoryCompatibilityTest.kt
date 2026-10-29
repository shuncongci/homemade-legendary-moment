package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Synthetic tight attribute IFDs plus frozen independent piexif 1.1.3 output. */
class ExifDirectoryCompatibilityTest {
    private enum class Kind { EXIF, GPS, INTEROP }
    private val be=ByteOrder.BIG_ENDIAN
    private val le=ByteOrder.LITTLE_ENDIAN
    private fun start(kind:Kind)=if(kind==Kind.INTEROP)40 else 26
    private fun entry(b:ByteBuffer,at:Int,tag:Int,type:Int=7,count:Int=4,value:Int=0x01020304){
        b.position(at);b.putShort(tag.toShort());b.putShort(type.toShort());b.putInt(count);b.putInt(value)
    }
    private fun tight(kind:Kind,order:ByteOrder=be,tail:Int=0,count:Int=1):ByteArray {
        val at=start(kind);val bytes=ByteArray(at+2+12*count+tail);val b=ByteBuffer.wrap(bytes).order(order)
        b.put(if(order==be)byteArrayOf(77,77)else byteArrayOf(73,73));b.putShort(42);b.putInt(8);b.putShort(8,1)
        entry(b,10,if(kind==Kind.GPS)0x8825 else 0x8769,4,1,26) // root next-image slot at 22 is present
        if(kind==Kind.INTEROP){b.putShort(26,1);entry(b,28,0xa005,4,1,40)}
        b.putShort(at,count.toShort());repeat(count){entry(b,at+2+it*12,0xc123+it)}
        return bytes
    }
    private fun integer(p:CaptureExif,e:CaptureExif.Entry)=ByteBuffer.wrap(e.value).order(p.order).let{if(e.value.size==2)it.short.toInt() and 65535 else it.int}
    private fun sub(p:CaptureExif,kind:Kind):List<CaptureExif.Entry> = when(kind){
        Kind.EXIF->p.exif();Kind.GPS->p.gps();Kind.INTEROP->p.entries(integer(p,p.exif().single{it.tag==0xa005}).toLong())
    }
    private fun checkTight(kind:Kind,order:ByteOrder,tail:Int=0,count:Int=1){
        val bytes=tight(kind,order,tail,count);val copy=bytes.copyOf();val src=CaptureExif(bytes);val result=CaptureExif(src.normalized(16,12))
        assertArrayEquals(copy,bytes);assertArrayEquals(copy.copyOfRange(8,copy.size),result.bytes.copyOfRange(8,copy.size))
        for(i in 0 until count){val a=sub(result,kind).single{it.tag==0xc123+i};assertArrayEquals(copy.copyOfRange(start(kind)+2+12*i,start(kind)+14+12*i),a.raw)}
        assertEquals(16,integer(result,result.root().single{it.tag==0x100}));assertEquals(12,integer(result,result.exif().single{it.tag==0xa003}))
    }
    @Test fun bigEndianExifAtEof(){checkTight(Kind.EXIF,be)}
    @Test fun littleEndianExifAtEof(){checkTight(Kind.EXIF,le)}
    @Test fun bigEndianGpsAtEof(){checkTight(Kind.GPS,be)}
    @Test fun littleEndianGpsAtEof(){checkTight(Kind.GPS,le)}
    @Test fun bigEndianInteropAtEof(){checkTight(Kind.INTEROP,be)}
    @Test fun littleEndianInteropAtEof(){checkTight(Kind.INTEROP,le)}
    @Test fun emptyExifAtEof(){checkTight(Kind.EXIF,be,count=0)}
    @Test fun emptyGpsAtEof(){checkTight(Kind.GPS,le,count=0)}
    @Test fun emptyInteropAtEof(){checkTight(Kind.INTEROP,be,count=0)}
    @Test fun attributeDirectoryDoesNotReadFollowingDataAsLink(){for(k in Kind.values())for(o in listOf(be,le))for(t in 1..4)checkTight(k,o,t,2)}
    @Test fun allCompleteEntriesArePreserved(){for(k in Kind.values())checkTight(k,be,count=4)}
    private fun independent(name:String)=checkNotNull(javaClass.getResourceAsStream("/exif-directory/$name.tiff")).use{it.readBytes()}
    @Test fun independentPiexifInterop(){val b=independent("interop");val p=CaptureExif(CaptureExif(b).normalized(16,12));assertTrue(p.exif().any{it.tag==0xa005});assertEquals("R98\u0000",String(sub(p,Kind.INTEROP).single{it.tag==1}.value))}
    @Test fun independentPiexifGps(){val b=independent("gps");val a=CaptureExif(b);val p=CaptureExif(a.normalized(16,12));a.gps().zip(p.gps()).forEach{(x,y)->assertArrayEquals(x.raw,y.raw);assertArrayEquals(x.value,y.value)}}
    @Test fun independentPiexifExif(){val b=independent("exif");val p=CaptureExif(CaptureExif(b).normalized(16,12));assertNotNull(p.value(true,0xa434))}
    @Test fun nonSrgbInteropDoesNotClaimR98(){val p=CaptureExif(CaptureExif(independent("interop")).normalized(16,12,outputSrgb=false));assertFalse(sub(p,Kind.INTEROP).any{it.tag==1});assertEquals(65535,integer(p,p.exif().single{it.tag==0xa001}))}
    @Test fun repeatedNormalizationKeepsUnknownFields(){var b=tight(Kind.INTEROP,le);repeat(4){b=CaptureExif(b).normalized(16+it,12+it)};assertEquals(0x01020304,integer(CaptureExif(b),sub(CaptureExif(b),Kind.INTEROP).single{it.tag==0xc123}))}
    private fun withMakerNote(order:ByteOrder):ByteArray {
        val bytes=CaptureMetadataFixtures.tiff(order).copyOf(526);val b=ByteBuffer.wrap(bytes).order(order)
        b.putShort(80,8);entry(b,166,0xa005,4,1,512);b.putShort(512,1);entry(b,514,1,2,4,if(order==be)0x52393800 else 0x00383952)
        return bytes
    }
    @Test fun makerNoteOffsetAndBytesRemainExact(){for(o in listOf(be,le)){val bytes=withMakerNote(o);val before=CaptureExif(bytes);val after=CaptureExif(before.normalized(16,12));val a=before.exif().single{it.tag==0x927c};val b=after.exif().single{it.tag==0x927c};assertArrayEquals(a.raw,b.raw);assertArrayEquals(a.value,b.value);assertArrayEquals(before.value(true,0xc123),after.value(true,0xc123));assertArrayEquals(bytes.copyOfRange(8,bytes.size),after.bytes.copyOfRange(8,bytes.size))}}
    private fun reject(bytes:ByteArray){assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun rootStillRequiresNextImageSlot(){for(t in 0..3){val b=ByteArray(10+t);ByteBuffer.wrap(b).put(byteArrayOf(77,77,0,42,0,0,0,8));reject(b)}}
    @Test fun rootEntriesApiIsStillStrict(){val p=CaptureExif(tight(Kind.EXIF));assertThrows(IllegalArgumentException::class.java){p.entries(26)}}
    @Test fun everyTruncatedEntryIsRejected(){for(k in Kind.values())for(o in listOf(be,le)){val b=tight(k,o);for(n in 1..12)reject(b.copyOf(b.size-n))}}
    @Test fun truncatedCountIsRejected(){for(k in Kind.values())reject(tight(k).copyOf(start(k)+1))}
    @Test fun subdirectoryPointerOutsidePacketIsRejected(){for(k in Kind.values()){val b=tight(k);ByteBuffer.wrap(b).putInt(if(k==Kind.INTEROP)36 else 18,-1);reject(b)}}
    @Test fun subdirectoryCountsRemainBounded(){for(k in Kind.values()){val b=tight(k);ByteBuffer.wrap(b).putShort(start(k),5000);reject(b)}}
    @Test fun missingIndirectValueIsRejected(){for(k in Kind.values()){val b=tight(k);ByteBuffer.wrap(b).putInt(start(k)+6,5).putInt(start(k)+10,b.size);reject(b)}}
    @Test fun hugeValueCountIsRejectedBeforeAllocation(){for(k in Kind.values()){val b=tight(k);ByteBuffer.wrap(b).putInt(start(k)+6,Int.MAX_VALUE);reject(b)}}
    @Test fun unknownTypeIsNotSilentlyDropped(){for(k in Kind.values()){val b=tight(k);ByteBuffer.wrap(b).putShort(start(k)+4,18);assertThrows(IllegalStateException::class.java){CaptureExif(b).normalized(16,12)}}}
    @Test fun duplicateTagsStillFailClosed(){for(k in Kind.values()){val b=tight(k,count=2);ByteBuffer.wrap(b).putShort(start(k)+14,0xc123.toShort());reject(b)}}
    @Test fun wrongPointerTypeStillFailsClosed(){val b=tight(Kind.INTEROP);ByteBuffer.wrap(b).putShort(30,3);reject(b)}
    @Test fun circularInteropStillFailsClosed(){val b=tight(Kind.INTEROP);ByteBuffer.wrap(b).putInt(36,26);reject(b)}
    @Test fun rootAliasStillFailsClosed(){val b=tight(Kind.INTEROP);ByteBuffer.wrap(b).putInt(36,8);reject(b)}
    private fun source(tiff:ByteArray):ByteArray {val p="Exif\u0000\u0000".toByteArray()+tiff;val j=CaptureMetadataFixtures.jpeg;return j.copyOfRange(0,2)+byteArrayOf(-1,-31,((p.size+2) ushr 8).toByte(),(p.size+2).toByte())+p+j.copyOfRange(2,j.size)}
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("exif-attribute-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun export(encoded:ByteArray){work{d->
        val raw=source(independent("interop"));val original=File(d,"original").apply{writeBytes(raw)};val plain=File(d,"encoded").apply{writeBytes(encoded)};val out=File(d,"out");val bundle=PreservedMetadata.read(original,Cancel())
        CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,false,Cancel());assertArrayEquals(raw,original.readBytes());assertArrayEquals(encoded,plain.readBytes());assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()));assertTrue(CaptureExif(CaptureMetadataContainer.readExif(out).exif!!).exif().any{it.tag==0xa005})
    }}
    @Test fun jpegExportPreservesIndependentMetadata(){export(CaptureMetadataFixtures.jpeg)}
    @Test fun pngExportPreservesIndependentMetadata(){export(CaptureMetadataFixtures.png)}
    @Test fun webpExportPreservesIndependentMetadata(){export(CaptureMetadataFixtures.webp)}
    @Test fun losslessWebpExportPreservesIndependentMetadata(){export(CaptureMetadataFixtures.losslessWebp)}
    @Test fun hdrExportPreservesIndependentMetadata(){val encoded=ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray();export(encoded)}
    @Test fun cancellationDoesNotPublish(){work{d->val encoded=File(d,"plain").apply{writeBytes(CaptureMetadataFixtures.png)};val original=File(d,"source").apply{writeBytes(source(independent("interop")))};val bundle=PreservedMetadata.read(original,Cancel());val out=File(d,"out");assertThrows(CancellationException::class.java){CaptureMetadataContainer.writePreserved(encoded,out,bundle,16,12,false,Cancel().also{it.cancelled=true})};assertFalse(out.exists())}}
    @Test fun realIoFailureIsNotSwallowed(){work{d->val original=File(d,"source").apply{writeBytes(source(independent("interop")))};val bundle=PreservedMetadata.read(original,Cancel());val out=File(d,"out");assertThrows(FileNotFoundException::class.java){CaptureMetadataContainer.writePreserved(File(d,"missing"),out,bundle,16,12,false,Cancel())};assertFalse(out.exists())}}
    @Test fun manualInteropOutOfRangeIsDetachedWithoutChangingOriginal(){
        for(o in listOf(be,le))for(pointer in listOf(-1,1024)){
            val b=withMakerNote(o);ByteBuffer.wrap(b).order(o).putInt(174,pointer)
            val copy=b.copyOf();var warnings=0
            val p=CaptureExif(CaptureExif(b).normalized(16,12,onInvalidInterop={warnings++}))
            assertEquals(1,warnings);assertFalse(p.exif().any{it.tag==0xa005})
            assertArrayEquals(copy,b);assertArrayEquals(copy.copyOfRange(8,copy.size),p.bytes.copyOfRange(8,copy.size))
            assertArrayEquals(CaptureExif(b).value(true,0x927c),p.value(true,0x927c))
        }
    }
    @Test fun manualTruncatedInteropIsDetached(){
        for(o in listOf(be,le)){val b=withMakerNote(o).dropLast(1).toByteArray();var warnings=0
            val p=CaptureExif(CaptureExif(b).normalized(16,12,onInvalidInterop={warnings++}))
            assertEquals(1,warnings);assertFalse(p.exif().any{it.tag==0xa005})
        }
    }
    @Test fun manualValidInteropIsNotSkipped(){var warnings=0;val p=CaptureExif(CaptureExif(withMakerNote(be)).normalized(16,12,onInvalidInterop={warnings++}));assertEquals(0,warnings);assertTrue(p.exif().any{it.tag==0xa005})}
    @Test fun manualDoesNotIgnoreRootOrExifOrGpsBounds(){
        for(k in listOf(Kind.EXIF,Kind.GPS)){val b=tight(k);ByteBuffer.wrap(b).putInt(18,-1)
            assertThrows(IllegalArgumentException::class.java){CaptureExif(b).normalized(16,12,onInvalidInterop={fail("Not Interop")})}
        }
    }
    @Test fun manualDoesNotIgnoreInteropTypeOrCycle(){
        val type=tight(Kind.INTEROP);ByteBuffer.wrap(type).putShort(44,18)
        assertThrows(IllegalStateException::class.java){CaptureExif(type).normalized(16,12,onInvalidInterop={fail("Not bounds")})}
        val cycle=tight(Kind.INTEROP);ByteBuffer.wrap(cycle).putInt(36,26)
        assertThrows(IllegalArgumentException::class.java){CaptureExif(cycle).normalized(16,12,onInvalidInterop={fail("Cycle")})}
    }
    @Test fun manualFiveFormatsKeepRawArchiveAndEncodedBytes(){work{d->
        val b=withMakerNote(be);ByteBuffer.wrap(b).putInt(174,-1);val raw=source(b)
        val original=File(d,"source").apply{writeBytes(raw)};val bundle=PreservedMetadata.read(original,Cancel())
        val hdr=ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray()
        listOf(CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.png,CaptureMetadataFixtures.webp,CaptureMetadataFixtures.losslessWebp,hdr).forEachIndexed{i,bytes->
            val plain=File(d,"plain$i").apply{writeBytes(bytes)};val out=File(d,"out$i");var warnings=0
            CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,false,Cancel(),onInvalidInterop={warnings++})
            assertEquals(1,warnings);assertArrayEquals(raw,original.readBytes());assertArrayEquals(bytes,plain.readBytes())
            assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()))
            val p=CaptureExif(CaptureMetadataContainer.readExif(out).exif!!);assertFalse(p.exif().any{it.tag==0xa005})
        }
    }}
    @Test fun strictExternalStillRejectsInvalidInterop(){val b=withMakerNote(be);ByteBuffer.wrap(b).putInt(174,-1);reject(b)}
    @Test fun damagedSubdirectoryDoesNotPublish(){work{d->val b=tight(Kind.INTEROP).dropLast(1).toByteArray();val original=File(d,"source").apply{writeBytes(source(b))};val bundle=PreservedMetadata.read(original,Cancel());val plain=File(d,"plain").apply{writeBytes(CaptureMetadataFixtures.png)};val out=File(d,"out");assertThrows(IllegalArgumentException::class.java){CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,false,Cancel())};assertFalse(out.exists());assertArrayEquals(source(b),original.readBytes())}}
}
