package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Synthetic fixtures only. Multiple EXIF packets are compatibility inputs, never real capture claims. */
class ExifPacketSelectionTest {
    @get:Rule val temp=TemporaryFolder()
    private fun rich(order:ByteOrder=ByteOrder.BIG_ENDIAN)=CaptureMetadataFixtures.tiff(order)
    private fun empty(order:ByteOrder=ByteOrder.BIG_ENDIAN,size:Int=2048):ByteArray = ByteArray(size).also {
        ByteBuffer.wrap(it).order(order).apply{put(if(order==ByteOrder.BIG_ENDIAN)byteArrayOf(77,77)else byteArrayOf(73,73));putShort(42);putInt(8);putShort(0);putInt(0)}
    }
    private fun placeholder(vararg patches:CaptureExif.Patch)=CaptureExif(empty()).normalized(16,12,patches.toList())+ByteArray(2048)
    private fun source(vararg packets:ByteArray):File = temp.newFile().apply {
        val base=CaptureMetadataFixtures.jpeg
        writeBytes(ByteArrayOutputStream().also{out->out.write(base,0,2);packets.forEach{p->DataOutputStream(out).apply{writeShort(0xffe1);writeShort(p.size+8);write("Exif\u0000\u0000".toByteArray());write(p)}};out.write(base,2,base.size-2)}.toByteArray())
    }
    private fun selected(vararg packets:ByteArray)=CaptureMetadataContainer.readExif(source(*packets)).exif
    private fun checkOrder(order:ByteOrder,reverse:Boolean){val r=rich(order);val p=empty(order);assertArrayEquals(r,if(reverse)selected(p,r)else selected(r,p))}
    private fun encoded():List<ByteArray> = listOf(CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.png,CaptureMetadataFixtures.webp,CaptureMetadataFixtures.losslessWebp,ByteArrayOutputStream().also{UltraHdrContainer.write(it,CaptureMetadataFixtures.jpeg,CaptureMetadataFixtures.jpeg,HdrMath.Map(16,12,IntArray(192),1f,4f,4f))}.toByteArray())
    private fun output(original:File,bytes:ByteArray=CaptureMetadataFixtures.jpeg,badge:Boolean=true):File {
        val plain=temp.newFile().apply{writeBytes(bytes)};val dest=File(temp.root,"out-${java.util.UUID.randomUUID()}")
        CaptureMetadataContainer.writePreserved(plain,dest,PreservedMetadata.read(original,Cancel()),16,12,badge,Cancel());return dest
    }
    private fun capture(f:File)=CaptureExif(checkNotNull(CaptureMetadataContainer.readExif(f).exif))
    @Test fun bigEndianCaptureFirst(){checkOrder(ByteOrder.BIG_ENDIAN,false)}
    @Test fun bigEndianCaptureLast(){checkOrder(ByteOrder.BIG_ENDIAN,true)}
    @Test fun littleEndianCaptureFirst(){checkOrder(ByteOrder.LITTLE_ENDIAN,false)}
    @Test fun littleEndianCaptureLast(){checkOrder(ByteOrder.LITTLE_ENDIAN,true)}
    @Test fun paddedVersionFirst(){val r=rich();assertArrayEquals(r,selected(placeholder(CaptureExif.Patch(true,0x9000,7,"0232".toByteArray())),r))}
    @Test fun paddedVersionLast(){val r=rich();assertArrayEquals(r,selected(r,placeholder(CaptureExif.Patch(true,0x9000,7,"0232".toByteArray()))))}
    @Test fun geometryOnlyCannotBeatCapture(){val r=rich();val p=placeholder();assertTrue(p.size>r.size);assertArrayEquals(r,selected(p,r,p))}
    @Test fun identicalRichDuplicatesRemainByteExact(){val r=rich();assertArrayEquals(r,selected(r,r.copyOf(),empty()))}
    @Test fun conflictingRichPacketsAreExplicitFailure(){val r=rich();val other=r.copyOf().also{it[200]='X'.code.toByte()};assertThrows(IllegalArgumentException::class.java){selected(r,other)}}
    @Test fun complementaryPacketsAreNotSilentlyDiscarded(){val p=CaptureExif.empty().normalized(16,12,listOf(CaptureExif.Patch(false,0x10e,2,"SYNTHETIC\u0000".toByteArray())));assertThrows(IllegalArgumentException::class.java){selected(rich(),p)}}
    @Test fun unknownRootFieldIsNotPlaceholder(){val p=placeholder(CaptureExif.Patch(false,0xc111,7,byteArrayOf(1)));assertThrows(IllegalArgumentException::class.java){selected(rich(),p)}}
    @Test fun unknownExifFieldIsNotPlaceholder(){val p=placeholder(CaptureExif.Patch(true,0xc111,7,byteArrayOf(1)));assertThrows(IllegalArgumentException::class.java){selected(rich(),p)}}
    @Test fun gpsOnlyPacketIsNotPlaceholder(){val g=rich().copyOf();ByteBuffer.wrap(g).apply{putShort(8,1);position(10);putShort(0x8825.toShort());putShort(4);putInt(1);putInt(320);putInt(0)};assertThrows(IllegalArgumentException::class.java){selected(rich(),g)}}
    @Test fun malformedSecondaryIsNotIgnored(){assertThrows(IllegalArgumentException::class.java){selected(rich(),byteArrayOf(77,77))}}
    @Test fun noExifIsNotInvented(){assertNull(selected())}
    @Test fun singlePacketIsByteExact(){val r=rich();assertArrayEquals(r,selected(r))}
    @Test fun allEmptyPacketsChooseFirstNotLongest(){val p=empty(size=16);assertArrayEquals(p,selected(p,empty()))}
    @Test fun extraMetadataDisclosureIsPreserved(){assertTrue(CaptureMetadataContainer.readExif(source(rich(),empty())).otherMetadata)}
    @Test fun fiveFormatsKeepCaptureFields(){val r=rich();val s=source(r,empty());for(bytes in encoded())for(badge in listOf(false,true)){
        val ex=capture(output(s,bytes,badge));val original=CaptureExif(r)
        for(tag in listOf(0x10f))assertArrayEquals(original.value(false,tag),ex.value(false,tag))
        for(tag in listOf(0xa434,0x829a,0x920a,0xc123))assertArrayEquals(original.value(true,tag),ex.value(true,tag))
        assertEquals(if(badge)1 else 0,ex.value(true,0x88b0)!!.single().toInt()and 1)
        assertArrayEquals(original.gps().first{it.tag==2}.value,ex.gps().first{it.tag==2}.value)
    }}
    @Test fun fiveFormatsArchiveEveryOriginalPacket(){val s=source(empty(),rich());val before=s.readBytes();val bundle=PreservedMetadata.read(s,Cancel());for(bytes in encoded()){
        val out=output(s,bytes);assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()));assertArrayEquals(before,s.readBytes())
    }}
    @Test fun makerNoteBytesAndOffsetSurvive(){val s=source(empty(),rich());val before=CaptureExif(rich());val after=capture(output(s));val a=before.exif().first{it.tag==0x927c};val b=after.exif().first{it.tag==0x927c};assertArrayEquals(a.raw,b.raw);assertArrayEquals(a.value,b.value)}
    @Test fun cancellationDoesNotPublish(){val original=source(rich(),empty());val plain=temp.newFile().apply{writeBytes(CaptureMetadataFixtures.jpeg)};val dest=File(temp.root,"cancelled");val cancel=Cancel().apply{cancelled=true};assertThrows(CancellationException::class.java){CaptureMetadataContainer.writePreserved(plain,dest,PreservedMetadata.read(original,Cancel()),16,12,true,cancel)};assertFalse(dest.exists())}
    @Test fun existingOutputIsNotOverwritten(){val original=source(rich(),empty());val plain=temp.newFile().apply{writeBytes(CaptureMetadataFixtures.jpeg)};val dest=temp.newFile().apply{writeText("keep")};assertThrows(IllegalStateException::class.java){CaptureMetadataContainer.writePreserved(plain,dest,PreservedMetadata.read(original,Cancel()),16,12,true,Cancel())};assertEquals("keep",dest.readText())}
    @Test fun sequentialImportsKeepEachCaptureAfterRestart(){var session=EditSession(temp.newFolder());val dir=session.store.directory
        for(i in 0..5){if(i==3)session=EditSession(dir);val ex=CaptureExif(rich()).normalized(16,12,listOf(CaptureExif.Patch(false,0x10e,2,"SYNTHETIC-$i\u0000".toByteArray())));val s=if(i%2==0)source(ex)else source(empty(),ex);session.importSource(s);val durable=checkNotNull(session.source());val out=output(durable);assertArrayEquals("SYNTHETIC-$i\u0000".toByteArray(),capture(out).value(false,0x10e));assertArrayEquals(ex,CaptureMetadataContainer.readExif(durable).exif)}
    }
}
