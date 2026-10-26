package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureExifTest {
    private fun value(packet:CaptureExif,exif:Boolean,tag:Int)=checkNotNull(packet.value(exif,tag))
    private fun integer(packet:CaptureExif,exif:Boolean,tag:Int)=ByteBuffer.wrap(value(packet,exif,tag)).order(packet.order).let{if(it.capacity()==2)it.short.toInt() and 65535 else it.int}
    private fun normalized(order:ByteOrder=ByteOrder.BIG_ENDIAN)=CaptureExif(CaptureExif(CaptureMetadataFixtures.tiff(order)).normalized(320,240))
    @Test fun bigEndianCaptureSurvives(){assertEquals("Fixture\u0000",String(value(normalized(),false,0x10f)))}
    @Test fun littleEndianCaptureSurvives(){assertEquals("Fixture\u0000",String(value(normalized(ByteOrder.LITTLE_ENDIAN),false,0x10f)))}
    @Test fun originalPacketIsNeverModified(){val original=CaptureMetadataFixtures.tiff();val copy=original.copyOf();CaptureExif(original).normalized(320,240);assertArrayEquals(copy,original)}
    @Test fun transformedPixelsUseNormalOrientation(){assertEquals(1,integer(normalized(),false,0x112))}
    @Test fun dimensionsAreUpdatedInBothDirectories(){val p=normalized();assertEquals(320,integer(p,false,0x100));assertEquals(240,integer(p,false,0x101));assertEquals(320,integer(p,true,0xa002));assertEquals(240,integer(p,true,0xa003))}
    @Test fun encodedSrgbDoesNotClaimSourceWideGamut(){assertEquals(1,integer(normalized(),true,0xa001))}
    @Test fun exposureRationalStaysExact(){val before=CaptureExif(CaptureMetadataFixtures.tiff());assertArrayEquals(value(before,true,0x829a),value(normalized(),true,0x829a))}
    @Test fun focalLengthRationalStaysExact(){val before=CaptureExif(CaptureMetadataFixtures.tiff());assertArrayEquals(value(before,true,0x920a),value(normalized(),true,0x920a))}
    @Test fun lensIdentityStaysOriginal(){assertEquals("FixtureLens\u0000",String(value(normalized(),true,0xa434)))}
    @Test fun makerNoteBytesAreNotReencoded(){val before=CaptureExif(CaptureMetadataFixtures.tiff());assertArrayEquals(value(before,true,0x927c),value(normalized(),true,0x927c))}
    @Test fun makerNoteAddressStaysAtSourceTiffOffset(){val before=CaptureExif(CaptureMetadataFixtures.tiff()).exif().first{it.tag==0x927c};val after=normalized().exif().first{it.tag==0x927c};assertArrayEquals(before.raw,after.raw)}
    @Test fun privateCaptureFieldsSurvive(){assertArrayEquals(byteArrayOf(9,8,7,6,5,4),value(normalized(),true,0xc123))}
    @Test fun gpsDirectorySurvivesByteExactly(){val a=CaptureExif(CaptureMetadataFixtures.tiff()).gps();val b=normalized().gps();assertEquals(a.size,b.size);a.zip(b).forEach{(x,y)->assertArrayEquals(x.raw,y.raw);assertArrayEquals(x.value,y.value)}}
    @Test fun oldPixelStripReferencesAreNotExported(){assertNull(normalized().value(false,0x111))}
    @Test fun newRootDoesNotReferenceOldThumbnail(){val p=normalized();val b=ByteBuffer.wrap(p.bytes).order(p.order);val start=b.getInt(4);val count=b.getShort(start).toInt() and 65535;assertEquals(0,b.getInt(start+2+12*count))}
    @Test fun emptyMetadataDoesNotInventCameraOrLocation(){val p=CaptureExif(CaptureExif.empty().normalized(16,12));assertNull(p.value(false,0x10f));assertNull(p.value(false,0x110));assertTrue(p.gps().isEmpty())}
    @Test fun badgeOnWritesOnlySamplePrivateFlag(){val source=CaptureExif(CaptureMetadataFixtures.tiff());val patches=XiaomiLeicaBadge.patches(source,true);assertEquals(1,patches.size);assertTrue(patches.single().exifIfd);assertEquals(0x88b0,patches.single().tag);val p=CaptureExif(source.normalized(320,240,patches));assertArrayEquals(byteArrayOf(1),value(p,true,0x88b0));assertArrayEquals(value(source,false,0x10f),value(p,false,0x10f));assertArrayEquals(value(source,true,0x927c),value(p,true,0x927c))}
    @Test fun badgeOffClearsAnAlreadyMarkedSource(){val initial=CaptureExif(CaptureMetadataFixtures.tiff());val marked=CaptureExif(initial.normalized(16,12,XiaomiLeicaBadge.patches(initial,true)));val cleared=CaptureExif(marked.normalized(16,12,XiaomiLeicaBadge.patches(marked,false)));assertArrayEquals(byteArrayOf(0),value(cleared,true,0x88b0))}
    @Test fun badgeKeepsUnknownFlagBits(){val source=CaptureExif(CaptureExif.empty().normalized(16,12,listOf(CaptureExif.Patch(true,0x88b0,1,byteArrayOf(128.toByte())))));val on=CaptureExif(source.normalized(16,12,XiaomiLeicaBadge.patches(source,true)));assertArrayEquals(byteArrayOf(129.toByte()),value(on,true,0x88b0));val off=CaptureExif(on.normalized(16,12,XiaomiLeicaBadge.patches(on,false)));assertArrayEquals(byteArrayOf(128.toByte()),value(off,true,0x88b0))}
    @Test fun badgeDoesNotOverwriteOtherFilterFields(){val source=CaptureExif(CaptureExif.empty().normalized(16,12,listOf(CaptureExif.Patch(true,0x889d,2,"Original-filter\u0000".toByteArray()))));val p=CaptureExif(source.normalized(16,12,XiaomiLeicaBadge.patches(source,true)));assertEquals("Original-filter\u0000",String(value(p,true,0x889d)));assertNull(p.value(false,0x110))}
    @Test fun badgeRejectsUnknownPrivateFieldEncoding(){val source=CaptureExif(CaptureExif.empty().normalized(16,12,listOf(CaptureExif.Patch(true,0x88b0,2,"bad\u0000".toByteArray()))));assertThrows(IllegalArgumentException::class.java){XiaomiLeicaBadge.patches(source,true)}}
    @Test fun badByteOrderFailsClosed(){assertThrows(IllegalStateException::class.java){CaptureExif(ByteArray(8))}}
    @Test fun bigTiffIsNotMisreadAsClassicExif(){val bytes=byteArrayOf(73,73,43,0,0,0,0,0);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes)}}
    @Test fun rootOutsidePacketFailsClosed(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putInt(4,-1);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun privateValueOutsidePacketFailsClosed(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putInt(18,-1);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun oversizedCountsDoNotAllocate(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putInt(14,Int.MAX_VALUE);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun tooManyEntriesFailClosed(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putShort(8,5000);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun circularDirectoryPointersFailClosed(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putInt(42,8);assertThrows(IllegalArgumentException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun unknownExifTypeIsNotSilentlyDropped(){val bytes=CaptureMetadataFixtures.tiff();ByteBuffer.wrap(bytes).putShort(156,18);assertThrows(IllegalStateException::class.java){CaptureExif(bytes).normalized(16,12)}}
    @Test fun invalidOutputSizeFailsClosed(){assertThrows(IllegalArgumentException::class.java){CaptureExif.empty().normalized(0,12)}}
}
