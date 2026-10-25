package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.File
import java.io.RandomAccessFile

/** Adobe gainmap JPEG + MPF. Both JPEG byte streams are already encoded; base is never recompressed after map derivation. */
object UltraHdrContainer {
    private fun segment(marker:Int,payload:ByteArray):ByteArray {
        require(payload.size+2<=65535)
        val out=ByteArrayOutputStream();DataOutputStream(out).use{it.writeByte(255);it.writeByte(marker);it.writeShort(payload.size+2);it.write(payload)};return out.toByteArray()
    }
    private fun xmp(attributes:String,body:String=""):ByteArray {
        val xml="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" $attributes>$body</rdf:Description></rdf:RDF></x:xmpmeta>"
        return segment(0xe1,"http://ns.adobe.com/xap/1.0/\u0000".toByteArray()+xml.toByteArray(Charsets.UTF_8))
    }
    private fun mpf(primary:Int,gain:Int,offset:Int):ByteArray {
        val out=ByteArrayOutputStream();DataOutputStream(out).use{
            it.writeBytes("MPF");it.writeByte(0);it.writeBytes("MM");it.writeShort(42);it.writeInt(8);it.writeShort(3)
            it.writeShort(0xb000);it.writeShort(7);it.writeInt(4);it.writeBytes("0100")
            it.writeShort(0xb001);it.writeShort(4);it.writeInt(1);it.writeInt(2)
            it.writeShort(0xb002);it.writeShort(7);it.writeInt(32);it.writeInt(50);it.writeInt(0)
            it.writeInt(0x030000);it.writeInt(primary);it.writeInt(0);it.writeShort(0);it.writeShort(0)
            it.writeInt(0);it.writeInt(gain);it.writeInt(offset);it.writeShort(0);it.writeShort(0)
        };return segment(0xe2,out.toByteArray())
    }
    private fun headers(baseLength:Int,gainLength:Int,map:HdrMath.Map):Pair<ByteArray,ByteArray> {
        fun log2(v:Float)=kotlin.math.ln(v.toDouble())/kotlin.math.ln(2.0)
        val fields="xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\" hdrgm:GainMapMin=\"${log2(map.minimum)}\" hdrgm:GainMapMax=\"${log2(map.maximum)}\" hdrgm:Gamma=\"1\" hdrgm:OffsetSDR=\"${HdrMath.EPSILON}\" hdrgm:OffsetHDR=\"${HdrMath.EPSILON}\" hdrgm:HDRCapacityMin=\"0\" hdrgm:HDRCapacityMax=\"${log2(map.display)}\" hdrgm:BaseRenditionIsHDR=\"False\""
        val gainXmp=xmp(fields);val gainSize=Math.addExact(gainLength,gainXmp.size)
        val primaryXmp=xmp("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"",
            "<Container:Directory><rdf:Seq><rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li><rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainSize\"/></rdf:li></rdf:Seq></Container:Directory>")
        val primarySize=Math.addExact(baseLength,primaryXmp.size+mpf(0,0,0).size)
        val index=mpf(primarySize,gainSize,primarySize-(2+primaryXmp.size+8))
        return (primaryXmp+index) to gainXmp
    }
    fun write(out:OutputStream,base:ByteArray,gain:ByteArray,map:HdrMath.Map) {
        for(bytes in listOf(base,gain))require(bytes.size>=4 && bytes[0]==(-1).toByte() && bytes[1]==(-40).toByte() && bytes[bytes.size-2]==(-1).toByte() && bytes.last()==(-39).toByte()){"无效JPEG数据"}
        val(primary,gainXmp)=headers(base.size,gain.size,map)
        out.write(base,0,2);out.write(primary);out.write(base,2,base.size-2)
        out.write(gain,0,2);out.write(gainXmp);out.write(gain,2,gain.size-2)
    }
    /** Same bytes, XMP and MPF offsets as the array API; compressed JPEGs stay on disk. */
    internal fun write(out:OutputStream,base:File,gain:File,map:HdrMath.Map,cancel:Cancel){
        fun length(file:File):Int {
            val n=file.length();require(n in 4..Int.MAX_VALUE.toLong()){"无效JPEG长度"}
            RandomAccessFile(file,"r").use{input->
                require(input.readUnsignedShort()==0xffd8){"无效JPEG数据"};input.seek(n-2)
                require(input.readUnsignedShort()==0xffd9){"无效JPEG数据"}
            };return n.toInt()
        }
        cancel.check();val baseLength=length(base);val gainLength=length(gain)
        val(primary,gainXmp)=headers(baseLength,gainLength,map)
        val bytes=ByteArray(65536)
        fun append(file:File,n:Int,metadata:ByteArray){
            file.inputStream().use{input->
                check(input.read()==255 && input.read()==216){"JPEG缓存在写入前改变"}
                out.write(255);out.write(216);out.write(metadata);var count=2L
                while(true){cancel.check();val got=input.read(bytes);if(got<0)break;out.write(bytes,0,got);count+=got}
                check(count==n.toLong()){"JPEG缓存长度改变"}
            }
        }
        append(base,baseLength,primary);append(gain,gainLength,gainXmp);cancel.check()
    }
}
