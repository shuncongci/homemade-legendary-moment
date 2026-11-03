package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.ln
import kotlin.math.pow

/** Synthetic carriers only. Format/metadata regression, not an Android display test. */
class HdrIsoMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ns="urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.US_ASCII)
    private val jpeg get()=CaptureMetadataFixtures.jpeg
    private fun map(low:Float=.25f,high:Float=4f,display:Float=8f)=HdrMath.Map(16,12,IntArray(0),low,high,display)
    private fun iso(m:HdrMath.Map=map())=ByteBuffer.wrap(HdrIsoMetadata.gain(m),ns.size,77).slice()
    private fun encoded(m:HdrMath.Map=map(),base:ByteArray=jpeg,gain:ByteArray=jpeg):ByteArray {
        val out=ByteArrayOutputStream();UltraHdrContainer.write(out,base,gain,m);return out.toByteArray()
    }
    private data class Header(val at:Int,val end:Int,val marker:Int,val payload:ByteArray)
    private fun headers(bytes:ByteArray,start:Int=0):List<Header> {
        assertEquals(0xff,bytes[start].toInt()and 255);assertEquals(0xd8,bytes[start+1].toInt()and 255)
        val out=ArrayList<Header>();var at=start+2
        while(true){
            assertEquals(0xff,bytes[at].toInt()and 255);val marker=bytes[at+1].toInt()and 255
            if(marker==0xda || marker==0xd9)return out
            val size=((bytes[at+2].toInt()and 255)shl 8)+(bytes[at+3].toInt()and 255)
            val end=at+2+size;assertTrue(size>=2 && end<=bytes.size)
            out.add(Header(at,end,marker,bytes.copyOfRange(at+4,end)));at=end
        }
    }
    private fun isIso(s:Header)=s.marker==0xe2 && s.payload.take(ns.size).toByteArray().contentEquals(ns)
    private fun index(bytes:ByteArray):Pair<Int,Int> {
        val s=headers(bytes).single{it.marker==0xe2 && it.payload.take(4).toByteArray().contentEquals(byteArrayOf(77,80,70,0))}
        val p=ByteBuffer.wrap(s.payload);val primary=p.getInt(58);val gain=p.getInt(74)
        assertEquals(primary,s.at+8+p.getInt(78));assertEquals(bytes.size,primary+gain)
        assertEquals(0xff,bytes[primary].toInt()and 255);assertEquals(0xd8,bytes[primary+1].toInt()and 255)
        return primary to gain
    }
    private fun segment(marker:Int,payload:ByteArray):ByteArray {
        val out=ByteArrayOutputStream();DataOutputStream(out).use{it.writeByte(255);it.writeByte(marker);it.writeShort(payload.size+2);it.write(payload)};return out.toByteArray()
    }
    private fun preserve(bytes:ByteArray,badge:Boolean=false):ByteArray {
        val dir=temporary.newFolder();val source=File(dir,"source.jpg").apply{writeBytes(jpeg.copyOfRange(0,2)+segment(0xe1,"Exif\u0000\u0000".toByteArray()+CaptureMetadataFixtures.tiff())+jpeg.copyOfRange(2,jpeg.size))}
        val plain=File(dir,"encoded.jpg").apply{writeBytes(bytes)};val out=File(dir,"output.jpg")
        val before=source.readBytes();val bundle=PreservedMetadata.read(source,Cancel())
        CaptureMetadataContainer.writePreserved(plain,out,bundle,16,12,badge,Cancel())
        assertArrayEquals(before,source.readBytes());assertArrayEquals(bytes,plain.readBytes())
        assertArrayEquals(PreservedMetadata.archive(bundle),PreservedMetadata.readArchive(out,Cancel()))
        val ex=CaptureExif(CaptureMetadataContainer.readExif(out).exif!!)
        assertEquals("Fixture\u0000",String(ex.value(false,0x10f)!!));assertEquals("FixtureLens\u0000",String(ex.value(true,0xa434)!!))
        return out.readBytes()
    }
    @Test fun mainPacketIsNamespaceAndVersionOnly(){assertArrayEquals(ns+byteArrayOf(0,0,0,0),HdrIsoMetadata.primary())}
    @Test fun gainPacketVersionFlagsLength(){val p=HdrIsoMetadata.gain(map());assertEquals(ns.size+77,p.size);assertArrayEquals(ns,p.copyOfRange(0,ns.size));val b=iso();assertEquals(0,b.short.toInt());assertEquals(0,b.short.toInt());assertEquals(0xc8,b.get().toInt()and 255);assertEquals(1 shl 23,b.int)}
    @Test fun negativeLogGainsRemainSigned(){val b=iso();b.position(17);val denominator=(1 shl 23).toDouble();assertEquals(-2.0,b.int/denominator,0.0);assertEquals(2.0,b.int/denominator,0.0)}
    @Test fun allThreeRgbParameterSetsAreWritten(){val b=iso();val p=ByteArray(b.remaining()).also{b.get(it)};assertArrayEquals(p.copyOfRange(17,37),p.copyOfRange(37,57));assertArrayEquals(p.copyOfRange(17,37),p.copyOfRange(57,77))}
    @Test fun gammaAndBothOffsetsAreExact(){val b=iso();val d=(1 shl 23).toDouble();for(c in 0..2){b.position(25+c*20);assertEquals(1.0,b.int/d,0.0);assertEquals(1.0/64,b.int/d,0.0);assertEquals(1.0/64,b.int/d,0.0)}}
    @Test fun headroomIsNotReplacedByMaximumGain(){val b=iso(map(.5f,2f,16f));b.position(9);assertEquals(0,b.int);assertEquals(4*(1 shl 23),b.int);b.position(21);assertEquals(1 shl 23,b.int)}
    @Test fun nonPowerOfTwoRoundTripWithinBound(){for(v in listOf(.0001f,.1483005f,.47248f,.9999999f,1.000001f,2.511234f,4.927642f,64.3f)){val b=iso(map(v,v,100f));b.position(17);val log=b.int/(1 shl 23).toDouble();assertEquals(ln(v.toDouble())/ln(2.0),log,6e-8);assertEquals(1.0,2.0.pow(log)/v,5e-8)}}
    @Test fun everyFiniteFloatGainRangeFitsNumerators(){val b=iso(map(Float.MIN_VALUE,Float.MAX_VALUE,Float.MAX_VALUE));b.position(17);assertEquals(-149*(1 shl 23),b.int);assertTrue(b.int>0)}
    @Test fun unityMapAndHeadroomStayUnity(){val b=iso(map(1f,1f,1f));b.position(9);assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(0,b.int);assertEquals(0,b.int)}
    @Test fun invalidMinimumFails(){for(v in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){HdrIsoMetadata.gain(map(v))}}
    @Test fun invalidMaximumFails(){for(v in listOf(-1f,.125f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){HdrIsoMetadata.gain(map(.25f,v))}}
    @Test fun invalidHeadroomFails(){for(v in listOf(0f,.99f,Float.NaN,Float.POSITIVE_INFINITY))assertThrows(IllegalArgumentException::class.java){HdrIsoMetadata.gain(map(display=v))}}
    @Test fun invalidMetadataArrayWritesNothing(){val out=ByteArrayOutputStream();assertThrows(IllegalArgumentException::class.java){UltraHdrContainer.write(out,jpeg,jpeg,map(Float.NaN))};assertEquals(0,out.size())}
    @Test fun invalidMetadataFileWritesNothing(){val f=temporary.newFile().apply{writeBytes(jpeg)};val out=ByteArrayOutputStream();assertThrows(IllegalArgumentException::class.java){UltraHdrContainer.write(out,f,f,map(display=Float.NaN),Cancel())};assertEquals(0,out.size());assertArrayEquals(jpeg,f.readBytes())}
    @Test fun arrayAndFileApisProduceSameBytes(){val a=temporary.newFile().apply{writeBytes(jpeg)};val b=temporary.newFile().apply{writeBytes(jpeg)};val out=ByteArrayOutputStream();UltraHdrContainer.write(out,a,b,map(),Cancel());assertArrayEquals(encoded(),out.toByteArray());assertArrayEquals(jpeg,a.readBytes());assertArrayEquals(jpeg,b.readBytes())}
    @Test fun primaryAndGainCompressedJpegsAreUntouched(){val bytes=encoded();val n=index(bytes).first;val mp=headers(bytes).single{it.payload.take(4).toByteArray().contentEquals(byteArrayOf(77,80,70,0))};val g=headers(bytes,n).single{isIso(it)};assertArrayEquals(jpeg.copyOfRange(2,jpeg.size),bytes.copyOfRange(mp.end,n));assertArrayEquals(jpeg.copyOfRange(2,jpeg.size),bytes.copyOfRange(g.end,bytes.size))}
    @Test fun existingIccIsKeptByteForByte(){val icc="ICC_PROFILE\u0000\u0001\u0001synthetic-sRGB-profile".toByteArray();val base=jpeg.copyOfRange(0,2)+segment(0xe2,icc)+jpeg.copyOfRange(2,jpeg.size);val bytes=encoded(base=base);assertArrayEquals(icc,headers(bytes).single{it.payload.take(12).toByteArray().contentEquals(icc.take(12).toByteArray())}.payload)}
    @Test fun mpfOffsetsAndLengthsIncludeBothNewSegments(){val bytes=encoded();val n=index(bytes).first;assertEquals(1,headers(bytes).count{isIso(it)});assertEquals(1,headers(bytes,n).count{isIso(it)});assertArrayEquals(HdrIsoMetadata.primary(),headers(bytes).single{isIso(it)}.payload);assertArrayEquals(HdrIsoMetadata.gain(map()),headers(bytes,n).single{isIso(it)}.payload)}
    @Test fun xmpGainDirectoryLengthIncludesIso(){val bytes=encoded();val (_,n)=index(bytes);val text=String(bytes,Charsets.ISO_8859_1);assertTrue(text.contains("Item:Length=\"$n\""));assertTrue(text.contains("hdrgm:GainMapMin=\"-2.0\""));assertTrue(text.contains("hdrgm:HDRCapacityMax=\"3.0\""));assertTrue(text.contains("hdrgm:BaseRenditionIsHDR=\"False\""))}
    @Test fun metadataRewritePreservesIsoAndWholeGain(){for(badge in listOf(false,true)){val before=encoded();val after=preserve(before,badge);val a=index(before).first;val b=index(after).first;assertArrayEquals(before.copyOfRange(a,before.size),after.copyOfRange(b,after.size));assertArrayEquals(headers(before).single{isIso(it)}.payload,headers(after).single{isIso(it)}.payload)}}
    @Test fun secondMetadataRewriteKeepsGainAndIso(){val first=preserve(encoded());val second=preserve(first,true);val a=index(first).first;val b=index(second).first;assertArrayEquals(first.copyOfRange(a,first.size),second.copyOfRange(b,second.size));assertEquals(1,headers(second).count{isIso(it)})}
    @Test fun cancelledBeforeFileWriteWritesNothing(){val out=ByteArrayOutputStream();val missing=File(temporary.root,"missing");assertThrows(CancellationException::class.java){UltraHdrContainer.write(out,missing,missing,map(),Cancel().apply{cancelled=true})};assertEquals(0,out.size())}
    @Test fun cancellationDuringStreamingPropagatesAndSourcesStay(){val a=temporary.newFile().apply{writeBytes(jpeg)};val c=Cancel();val out=object:OutputStream(){override fun write(v:Int){c.cancelled=true};override fun write(b:ByteArray,o:Int,n:Int){c.cancelled=true}};assertThrows(CancellationException::class.java){UltraHdrContainer.write(out,a,a,map(),c)};assertArrayEquals(jpeg,a.readBytes())}
    @Test fun realOutputIoFailurePropagates(){val out=object:OutputStream(){override fun write(v:Int){throw IOException("fixture IO")}};assertThrows(IOException::class.java){UltraHdrContainer.write(out,jpeg,jpeg,map())}}
    @Test fun missingInputFailsLengthValidationBeforeWriting(){val out=ByteArrayOutputStream();assertThrows(IllegalArgumentException::class.java){UltraHdrContainer.write(out,File(temporary.root,"missing"),File(temporary.root,"missing2"),map(),Cancel())};assertEquals(0,out.size())}
    @Test fun readOpenIoFailurePropagates(){val out=ByteArrayOutputStream();val unavailable=object:File(temporary.root,"unavailable"){override fun length():Long=jpeg.size.toLong()};assertThrows(FileNotFoundException::class.java){UltraHdrContainer.write(out,unavailable,unavailable,map(),Cancel())};assertEquals(0,out.size());assertFalse(unavailable.exists())}
    @Test fun malformedJpegIsNotAccepted(){val out=ByteArrayOutputStream();assertThrows(IllegalArgumentException::class.java){UltraHdrContainer.write(out,byteArrayOf(1,2,3,4),jpeg,map())};assertEquals(0,out.size())}
    @Test fun streamingCrosses64KiBWithoutChanges(){val payload=ByteArray(65520){(it%251).toByte()};val base=jpeg.copyOfRange(0,2)+segment(0xfe,payload)+jpeg.copyOfRange(2,jpeg.size);val a=temporary.newFile().apply{writeBytes(base)};val b=temporary.newFile().apply{writeBytes(jpeg)};val out=ByteArrayOutputStream();UltraHdrContainer.write(out,a,b,map(),Cancel());assertArrayEquals(encoded(base=base),out.toByteArray());assertArrayEquals(base,a.readBytes())}
    @Test fun metadataDoesNotMutateMapOrPixels(){val pixels=intArrayOf(1,2,3);val m=HdrMath.Map(111,222,pixels,.25f,4f,8f);val before=pixels.copyOf();HdrIsoMetadata.gain(m);assertArrayEquals(before,m.pixels);assertEquals(111,m.w);assertEquals(222,m.h);assertEquals(.25f,m.minimum,0f);assertEquals(4f,m.maximum,0f);assertEquals(8f,m.display,0f)}
}
