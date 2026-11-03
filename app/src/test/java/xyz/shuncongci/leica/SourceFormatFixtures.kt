package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Container structure fixtures only; dummy HEIF coded pixels are NOT decoder/phone evidence. */
object SourceFormatFixtures {
    fun box(type:String,bytes:ByteArray,wide:Boolean=false):ByteArray = ByteArrayOutputStream().also{out->DataOutputStream(out).apply{writeInt(if(wide)1 else bytes.size+8);writeBytes(type);if(wide)writeLong(bytes.size+16L);write(bytes)}}.toByteArray()
    private fun data(block:DataOutputStream.()->Unit)=ByteArrayOutputStream().also{DataOutputStream(it).apply(block)}.toByteArray()
    @JvmOverloads
    fun heif(brand:String="heic",version:Int=0,idat:Boolean=false,little:Boolean=false,fragmented:Boolean=false,xmp:Boolean=true,offset:Int=0,wide:Boolean=false):ByteArray {
        val exif=data{writeInt(offset);write(ByteArray(offset));write(CaptureMetadataFixtures.tiff(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN))}
        val xml="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:description=\"SYNTHETIC-HEIF\"/></rdf:RDF></x:xmpmeta>".toByteArray()
        val payloads=if(xmp)listOf(exif,xml)else listOf(exif)
        val ftyp=box("ftyp",data{writeBytes(brand);writeInt(0);writeBytes(if(brand=="avif")"avif"else "mif1")},wide)
        val info=box("iinf",data{writeInt(0);writeShort(payloads.size);payloads.forEachIndexed{i,_->write(box("infe",data{writeInt(if(version==2)3 shl 24 else 2 shl 24);if(version==2)writeInt(i+1)else writeShort(i+1);writeShort(0);writeBytes(if(i==0)"Exif"else "mime");writeByte(0);if(i>0){writeBytes("application/rdf+xml");writeByte(0);writeByte(0)}}))}})
        fun meta(base:Long):ByteArray {
            var start=base
            val loc=box("iloc",data{
                writeInt(version shl 24);writeByte(0x44);writeByte(0);if(version==2)writeInt(payloads.size)else writeShort(payloads.size)
                payloads.forEachIndexed{i,b->if(version==2)writeInt(i+1)else writeShort(i+1);if(version>0)writeShort(if(idat)1 else 0);writeShort(0);writeShort(if(fragmented)2 else 1)
                    if(fragmented){val n=b.size/2;writeInt(start.toInt());writeInt(n);writeInt((start+n).toInt());writeInt(b.size-n)}else{writeInt(start.toInt());writeInt(b.size)};start+=b.size}
            })
            return box("meta",data{writeInt(0);write(info);write(loc);if(idat)write(box("idat",payloads.fold(ByteArray(0)){a,b->a+b}))},wide)
        }
        val m=meta(0);return if(idat)ftyp+m else ftyp+meta((ftyp.size+m.size+8).toLong())+box("mdat",payloads.fold(ByteArray(0)){a,b->a+b})
    }
    fun gif():ByteArray = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH+CVNZTlRIRVRJQwAsAAAAAAEAAQAAAgJEAQA7")
    fun bmp(v5:Boolean=false):ByteArray {
        val dib=if(v5)124 else 40;val profile=if(v5)"SYNTHETIC-ICC".toByteArray()else ByteArray(0);val pixel=14+dib;val b=ByteArray(pixel+4+profile.size);val d=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        d.put('B'.code.toByte());d.put('M'.code.toByte());d.putInt(b.size);d.putInt(0);d.putInt(pixel);d.putInt(dib);d.putInt(1);d.putInt(1);d.putShort(1);d.putShort(24);d.putInt(0);d.putInt(4)
        if(v5){d.putInt(14+112,dib+4);d.putInt(14+116,profile.size);profile.copyInto(b,pixel+4)};return b
    }
}
