package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class SourceFormatMetadataTest {
    @get:Rule val temp=TemporaryFolder()
    private fun file(bytes:ByteArray)=temp.newFile().apply{writeBytes(bytes)}
    private fun read(bytes:ByteArray)=PreservedMetadata.read(file(bytes),Cancel())
    private fun heif(v:Int=0,idat:Boolean=false,le:Boolean=false,frag:Boolean=false,brand:String="heic",offset:Int=0,wide:Boolean=false)=SourceFormatFixtures.heif(brand,v,idat,le,frag,true,offset,wide)
    private fun capture(bundle:PreservedMetadata.Bundle){val b=CaptureExif(checkNotNull(bundle.exif));assertEquals("Fixture\u0000",b.value(false,0x10f)!!.toString(Charsets.UTF_8));assertEquals("FixtureLens\u0000",b.value(true,0xa434)!!.toString(Charsets.UTF_8));assertEquals(3,b.gps().size)}
    private fun at(b:ByteArray,kind:String)=b.toString(Charsets.ISO_8859_1).indexOf(kind).also{assertTrue(it>=4)}
    private fun reject(bytes:ByteArray){assertThrows(Exception::class.java){read(bytes)}}
    private fun outputs()=listOf(CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.png,CaptureMetadataFixtures.webp,CaptureMetadataFixtures.losslessWebp,java.io.ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray())
    private fun export(source:File,encoded:ByteArray,badge:Boolean=true):File {val out=File(temp.root,"out-${java.util.UUID.randomUUID()}");CaptureMetadataContainer.writePreserved(file(encoded),out,PreservedMetadata.read(source,Cancel()),16,12,badge,Cancel());return out}
    @Test fun heicIloc0(){capture(read(heif()))}
    @Test fun heicIloc1Absolute(){capture(read(heif(1)))}
    @Test fun heicIloc1Idat(){capture(read(heif(1,true)))}
    @Test fun heicIloc2Absolute(){capture(read(heif(2)))}
    @Test fun heicIloc2Idat(){capture(read(heif(2,true)))}
    @Test fun littleEndianExif(){capture(read(heif(le=true)))}
    @Test fun splitAbsoluteExtents(){capture(read(heif(1,frag=true)))}
    @Test fun splitIdatExtents(){capture(read(heif(2,true,frag=true)))}
    @Test fun nonzeroExifHeaderOffset(){capture(read(heif(offset=6)))}
    @Test fun extendedBoxSizes(){capture(read(heif(wide=true)))}
    @Test fun avifBrand(){val b=read(heif(brand="avif"));assertEquals("AVIF",b.format);capture(b)}
    @Test fun genericHeifBrand(){assertEquals("HEIF",read(heif(brand="mif1")).format)}
    @Test fun xmpIsActiveAndRaw(){val b=read(heif());assertTrue(b.xmp.single().toString(Charsets.UTF_8).contains("SYNTHETIC-HEIF"));assertTrue(b.packets.any{it.kind=="H:2:mime"})}
    @Test fun noExifDoesNotInventCapture(){val b=heif().copyOf();"hvc1".toByteArray().copyInto(b,at(b,"Exif"));assertNull(read(b).exif)}
    @Test fun unsupportedMediaBrandRejected(){val b=heif(brand="mp42");val i=at(b,"mif1");"mp42".toByteArray().copyInto(b,i);reject(b)}
    @Test fun truncatedBoxRejected(){reject(heif().dropLast(1).toByteArray())}
    @Test fun invalidExtentRejected(){val b=heif().copyOf();val i=at(b,"iloc");ByteBuffer.wrap(b).putInt(i+18,Int.MAX_VALUE);reject(b)}
    @Test fun externalDataReferenceRejected(){val b=heif().copyOf();val i=at(b,"iloc");ByteBuffer.wrap(b).putShort(i+14,1);reject(b)}
    @Test fun encryptedItemRejected(){val b=heif().copyOf();ByteBuffer.wrap(b).putShort(at(b,"infe")+10,1);reject(b)}
    @Test fun missingLocationsRejected(){val b=heif().copyOf();"free".toByteArray().copyInto(b,at(b,"iloc"));reject(b)}
    @Test fun duplicateMetaRejected(){val b=heif();val i=at(b,"meta")-4;val n=ByteBuffer.wrap(b).getInt(i);reject(b+b.copyOfRange(i,i+n))}
    @Test fun exifOffsetOutOfRangeRejected(){val b=heif().copyOf();ByteBuffer.wrap(b).putInt(at(b,"mdat")+4,Int.MAX_VALUE);reject(b)}
    @Test fun cancellationPropagates(){val source=file(heif());val cancel=Cancel().apply{cancelled=true};assertThrows(CancellationException::class.java){PreservedMetadata.read(source,cancel)}}
    @Test fun absentSourcePropagates(){assertThrows(java.io.FileNotFoundException::class.java){PreservedMetadata.read(File(temp.root,"absent"),Cancel())}}
    @Test fun unknownFormatStillRejected(){reject("UNKNOWN-SYNTHETIC".toByteArray())}
    @Test fun heifFiveFormatsKeepCaptureAndOriginal(){val source=file(heif(2,true,le=true));val original=source.readBytes();for(bytes in outputs()){
        val out=export(source,bytes);val ex=CaptureExif(checkNotNull(CaptureMetadataContainer.readExif(out).exif));assertEquals("Fixture\u0000",ex.value(false,0x10f)!!.toString(Charsets.UTF_8));assertEquals("FixtureLens\u0000",ex.value(true,0xa434)!!.toString(Charsets.UTF_8));assertEquals(3,ex.gps().size);assertArrayEquals(original,source.readBytes())
    }}
    @Test fun heifFiveFormatsArchiveExact(){val source=file(heif());val archive=PreservedMetadata.archive(PreservedMetadata.read(source,Cancel()));for(bytes in outputs())assertArrayEquals(archive,PreservedMetadata.readArchive(export(source,bytes),Cancel()))}
    @Test fun makerNoteOffsetsUnchanged(){val source=file(heif());val before=CaptureExif(checkNotNull(PreservedMetadata.read(source,Cancel()).exif));val out=export(source,CaptureMetadataFixtures.jpeg);val after=CaptureExif(checkNotNull(CaptureMetadataContainer.readExif(out).exif));val a=before.exif().single{it.tag==0x927c};val b=after.exif().single{it.tag==0x927c};assertArrayEquals(a.raw,b.raw);assertArrayEquals(a.value,b.value)}
    @Test fun badgeBothStates(){val source=file(heif());for(on in listOf(false,true)){val ex=CaptureExif(checkNotNull(CaptureMetadataContainer.readExif(export(source,CaptureMetadataFixtures.jpeg,on)).exif));assertEquals(if(on)1 else 0,ex.value(true,0x88b0)!!.single().toInt() and 1)}}
    @Test fun externalHeifArchivesButNeverEarnsDeletion(){val source=file(heif());val p=ExternalMetadataTransfer.prepare(source,16,12,true,Cancel());assertFalse(p.complete);val out=File(temp.root,"external");ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),out,p,16,12,Cancel());assertTrue(p.verified);assertFalse(p.deletionReady)}
    @Test fun tiffBigEndian(){val b=read(CaptureMetadataFixtures.tiff());assertEquals("TIFF",b.format);capture(b)}
    @Test fun tiffLittleEndian(){capture(read(CaptureMetadataFixtures.tiff(ByteOrder.LITTLE_ENDIAN)))}
    @Test fun tiffMakerNoteOffsets(){val source=file(CaptureMetadataFixtures.tiff());val before=CaptureExif(CaptureMetadataFixtures.tiff());val after=CaptureExif(checkNotNull(CaptureMetadataContainer.readExif(export(source,CaptureMetadataFixtures.jpeg)).exif));assertArrayEquals(before.exif().single{it.tag==0x927c}.raw,after.exif().single{it.tag==0x927c}.raw)}
    @Test fun tiffPixelDataNotArchivedAsMetadata(){val b=CaptureMetadataFixtures.tiff()+ByteArray(200000){77};val bundle=read(b);assertTrue(bundle.packets.sumOf{it.bytes.size}<1000);capture(bundle)}
    @Test fun tiffDirectoryCycleRejected(){val b=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(b).putInt(70,8);reject(b)}
    @Test fun tiffDistantMetadataRejectedNotRewritten(){val b=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(b).putInt(18,2000000);reject(b)}
    @Test fun bigTiffNotPretendedSupported(){reject(byteArrayOf(73,73,43,0,8,0,0,0))}
    @Test fun dngStructuralFieldsNotActive(){val original=CaptureExif(CaptureMetadataFixtures.tiff()).normalized(16,12,listOf(CaptureExif.Patch(false,0xc612,1,byteArrayOf(1,4,0,0)),CaptureExif.Patch(false,0xc614,2,"SYNTHETIC-CAMERA\u0000".toByteArray()),CaptureExif.Patch(false,0xc61a,3,byteArrayOf(0,1))));val b=read(original);assertEquals("DNG",b.format);val ex=CaptureExif(checkNotNull(b.exif));assertNull(ex.value(false,0xc612));assertNull(ex.value(false,0xc61a));assertNotNull(ex.value(false,0xc614));assertTrue(b.packets.single().bytes.size>0)}
    @Test fun gifCommentArchived(){val b=read(SourceFormatFixtures.gif());assertEquals("GIF",b.format);assertNull(b.exif);assertTrue(b.packets.any{it.kind=="G:extension:254" && it.bytes.toString(Charsets.ISO_8859_1).contains("SYNTHETIC")})}
    @Test fun gifTruncatedRejected(){reject(SourceFormatFixtures.gif().dropLast(1).toByteArray())}
    @Test fun bmpWithoutExifExports(){val source=file(SourceFormatFixtures.bmp());assertEquals("BMP",PreservedMetadata.read(source,Cancel()).format);assertNotNull(CaptureMetadataContainer.readExif(export(source,CaptureMetadataFixtures.jpeg)).exif)}
    @Test fun bmpV5ProfileArchived(){assertTrue(read(SourceFormatFixtures.bmp(true)).packets.any{it.kind=="B:profile" && it.bytes.toString(Charsets.UTF_8)=="SYNTHETIC-ICC"})}
    @Test fun bmpInvalidOffsetRejected(){val b=SourceFormatFixtures.bmp();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(10,Int.MAX_VALUE);reject(b)}
    @Test fun newFormatsReadExifSupported(){for(b in listOf(heif(),CaptureMetadataFixtures.tiff(),SourceFormatFixtures.gif(),SourceFormatFixtures.bmp()))assertTrue(CaptureMetadataContainer.readExif(file(b)).supported)}
    @Test fun newFormatsNeverEarnExternalDeletion(){for(b in listOf(CaptureMetadataFixtures.tiff(),SourceFormatFixtures.gif(),SourceFormatFixtures.bmp(),heif(brand="avif"))){val p=ExternalMetadataTransfer.prepare(file(b),16,12,false,Cancel());assertFalse(p.complete);assertFalse(p.deletionReady)}}
    @Test fun sequentialMixedImports(){val session=EditSession(temp.newFolder());for(bytes in listOf(heif(),CaptureMetadataFixtures.tiff(),SourceFormatFixtures.bmp(),heif(brand="avif"))){
        val source=file(bytes);session.importSource(source);val durable=checkNotNull(session.source());val out=export(durable,CaptureMetadataFixtures.jpeg);assertArrayEquals(PreservedMetadata.archive(PreservedMetadata.read(source,Cancel())),PreservedMetadata.readArchive(out,Cancel()))
    }}
    @Test fun externalNewExtensionsAdmitted(){for(ext in listOf("HEIC","heif","avif","TIFF","tif","DNG","gif","bmp"))assertTrue(ExternalModePolicy.image("SYNTHETIC.$ext"))}
    @Test fun externalNewExtensionsStillExcludeOwnOutput(){for(ext in listOf("avif","tiff","gif","bmp")){assertFalse(ExternalModePolicy.image(".hidden.$ext"));assertFalse(ExternalModePolicy.image(ExternalModePolicy.OUTPUT_PREFIX+"own.$ext"));assertFalse(ExternalModePolicy.image("IMG.$ext.part"))}}
    @Test fun externalOldPhotosNotBackfilledForNewExtensions(){val old=ExternalPhoto("old","file:///old.avif","old.avif",100,1);val gate=ExternalArrivalGate(listOf(old));assertTrue(gate.observe(listOf(old),0).isEmpty());assertTrue(gate.observe(listOf(old),5000).isEmpty())}
    @Test fun headerFragmentIsUnsupportedAndCannotExport(){val bytes="\u0000\u0000\u0000\u0018ftypheic".toByteArray();assertFalse(CaptureMetadataContainer.readExif(file(bytes)).supported);reject(bytes)}
    @Test fun limitedReadBudgetNotBypassed(){assertThrows(IllegalArgumentException::class.java){PreservedMetadata.read(file(heif()),Cancel(),10)}}
}
