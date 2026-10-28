package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Metadata-only container rewrite. JPEG entropy/gain JPEG, PNG IDAT and WebP image chunks are copied,
 * never decoded or re-encoded. All variable reads are bounded; large image payloads use a 64 KiB buffer.
 */
object CaptureMetadataContainer {
    data class Read(val exif:ByteArray?,val supported:Boolean,val otherMetadata:Boolean=false)
    internal data class Chunk(val kind:String,val offset:Long,val size:Long,val total:Long)
    internal data class Segment(val marker:Int,val offset:Long,val payload:ByteArray){val total get()=payload.size+4;val exif get()=marker==0xe1 && payload.starts(EXIF);val mpf get()=marker==0xe2 && payload.starts(MPF)}
    private val EXIF=byteArrayOf(69,120,105,102,0,0)
    private val MPF=byteArrayOf(77,80,70,0)
    private val PNG=byteArrayOf(-119,80,78,71,13,10,26,10)
    private fun ByteArray.starts(prefix:ByteArray)=size>=prefix.size && prefix.indices.all{this[it]==prefix[it]}
    private fun read(f:RandomAccessFile,offset:Long,n:Int):ByteArray {require(n>=0 && n<=CaptureExif.MAX_BYTES && offset>=0 && offset<=f.length()-n){"元数据容器越界"};f.seek(offset);return ByteArray(n).also{f.readFully(it)}}
    private fun le32(b:ByteArray,offset:Int=0)=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xffffffffL
    private fun le(n:Long)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n.toInt()).array()
    internal fun jpegHeaders(f:RandomAccessFile):List<Segment>{
        require(f.length()>=4 && read(f,0,2).contentEquals(byteArrayOf(-1,-40))){"JPEG容器无效"}
        val list=mutableListOf<Segment>();var at=2L;val length=f.length()
        // JPEG permits FF fill bytes before a marker. Keep offsets anchored to the final FF,
        // not the fill run: MPF offsets use the actual marker/TIFF location. Bound fill as well
        // as segment payloads, and buffer the scan rather than doing one disk read per fill byte.
        val buffer=ByteArray(8192);var start=-1L;var available=0
        fun byte(at:Long):Int {
            require(at>=2 && at<length){ "JPEG头截断" }
            require(at-2<12L*CaptureExif.MAX_BYTES){ "JPEG元数据头超限" }
            if(at<start || at>=start+available){f.seek(at);available=f.read(buffer);start=at;check(available>0){"JPEG在读取时改变"}}
            return buffer[(at-start).toInt()].toInt() and 255
        }
        while(true){
            require(byte(at)==255){"JPEG头损坏"}
            var markerAt=at;var marker=byte(markerAt+1)
            while(marker==255){markerAt++;marker=byte(markerAt+1)}
            require(marker!=0 && marker!=0xd9 && marker !in 0xd0..0xd8 && marker!=1){"JPEG没有图像扫描数据"}
            val n=(byte(markerAt+2) shl 8)+byte(markerAt+3);require(n>=2){"JPEG段长度无效"}
            val end=markerAt+n+2
            require(end<=length){ "JPEG段截断" }
            require(end-2<=12L*CaptureExif.MAX_BYTES){"JPEG元数据头超限"}
            if(marker==0xda)break
            require(list.size<2048){"JPEG元数据头超限"}
            list+=Segment(marker,markerAt,read(f,markerAt+4,n-2));at=end
        }
        return list
    }
    internal fun chunks(f:RandomAccessFile,png:Boolean):List<Chunk>{
        var at=if(png)8L else 12L;val list=mutableListOf<Chunk>()
        if(png)require(read(f,0,8).contentEquals(PNG)) else require(String(read(f,0,4),Charsets.US_ASCII)=="RIFF" && String(read(f,8,4),Charsets.US_ASCII)=="WEBP" && le32(read(f,4,4))+8==f.length()){"WebP RIFF长度无效"}
        while(at<f.length()){
            require(list.size<100000){"图像块数超限"};val h=read(f,at,8)
            val n=if(png)ByteBuffer.wrap(h).int.toLong() and 0xffffffffL else le32(h,4)
            val kind=String(h,if(png)4 else 0,4,Charsets.US_ASCII)
            val total=8+n+if(png)4 else n%2
            require(total>=8 && at<=f.length()-total){"图像块越界"};list+=Chunk(kind,at,n,total);at+=total
        }
        require(at==f.length());if(png)require(list.firstOrNull()?.kind=="IHDR" && list.lastOrNull()?.kind=="IEND")
        return list
    }
    fun readExif(file:File):Read=RandomAccessFile(file,"r").use{f->
        val first=read(f,0,minOf(12L,f.length()).toInt())
        when{
            first.starts(byteArrayOf(-1,-40))->{
                val headers=jpegHeaders(f);val packets=headers.filter{it.exif}.map{it.payload.copyOfRange(6,it.payload.size)}
                // Some camera JPEGs have a second tiny ExifVersion-only packet. Keep the rich packet;
                // nontrivial additional packets are disclosed and prevent source auto-deletion.
                val selected=packets.maxByOrNull{it.size}
                val extraExif=packets.any{it!==selected && it.size>128}
                val extra=headers.any{(it.marker==0xeb && it.payload.starts(PreservedMetadata.ARCHIVE)) || it.marker==0xed || it.marker==0xfe || (it.marker==0xe1 && !it.exif && it.payload.toString(Charsets.UTF_8).let{s->!s.contains("hdrgm:") || s.contains("dc:") || s.contains("photoshop:")})}
                Read(selected,true,extraExif || extra)
            }
            first.starts(PNG)->{
                val chunks=chunks(f,true);val exifs=chunks.filter{it.kind=="eXIf"};require(exifs.size<=1){"PNG存在重复EXIF块"}
                val packet=exifs.firstOrNull()?.let{c->
                    val payload=read(f,c.offset+8,c.size.toInt());val crc=CRC32();crc.update("eXIf".toByteArray());crc.update(payload)
                    val expected=ByteBuffer.wrap(read(f,c.offset+8+c.size,4)).int.toLong() and 0xffffffffL
                    require(crc.value==expected){"PNG EXIF校验和错误，原片保留"};payload
                }
                Read(packet,true,chunks.any{it.kind in setOf("tEXt","iTXt","zTXt","lmEt")})
            }
            first.size>=12 && String(first,0,4,Charsets.US_ASCII)=="RIFF" && String(first,8,4,Charsets.US_ASCII)=="WEBP"->{
                val chunks=chunks(f,false);val exifs=chunks.filter{it.kind=="EXIF"};require(exifs.size<=1){"WebP存在重复EXIF块"}
                val packet=exifs.firstOrNull()?.let{read(f,it.offset+8,it.size.toInt())}
                Read(packet?.let{if(it.starts(EXIF))it.copyOfRange(6,it.size)else it},true,chunks.any{it.kind in setOf("XMP ","LMET")})
            }
            else->Read(null,false)
        }
    }
    private fun copy(f:RandomAccessFile,out:OutputStream,from:Long,length:Long,cancel:Cancel){
        require(from>=0 && length>=0 && from<=f.length()-length)
        f.seek(from);var left=length;val buffer=ByteArray(64*1024)
        while(left>0){cancel.check();val n=f.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0){"图像在写入时改变"};out.write(buffer,0,n);left-=n}
    }
    private fun segment(marker:Int,payload:ByteArray):ByteArray{
        require(payload.size<=65533){"JPEG的EXIF超出APP1容量；未丢弃元数据或删除原片"}
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{it.writeByte(255);it.writeByte(marker);it.writeShort(payload.size+2);it.write(payload)};return bytes.toByteArray()
    }
    private data class Extra(val jpeg:List<Pair<Int,ByteArray>> = emptyList(),val png:List<Pair<String,ByteArray>> = emptyList(),val webp:List<Pair<String,ByteArray>> = emptyList(),val xmp:Boolean=false){val archive get()=jpeg.any{it.first==0xeb && it.second.starts(PreservedMetadata.ARCHIVE)}}
    private fun writeJpeg(f:RandomAccessFile,out:OutputStream,exif:ByteArray,cancel:Cancel,extra:Extra){
        val headers=jpegHeaders(f)
        fun replaced(s:Segment)=s.exif || (extra.archive && (s.marker in setOf(0xed,0xfe) || (s.marker==0xeb && s.payload.starts(PreservedMetadata.ARCHIVE)))) || (extra.xmp && s.marker==0xe1 && (PreservedMetadata.starts(s.payload,PreservedMetadata.XMP) || PreservedMetadata.starts(s.payload,"http://ns.adobe.com/xmp/extension/\u0000".toByteArray())))
        val added=ByteArrayOutputStream().also{b->b.write(segment(0xe1,EXIF+exif));extra.jpeg.forEach{(m,p)->b.write(segment(m,p))}}.toByteArray()
        val removed=headers.filter(::replaced).sumOf{it.total}
        val delta=added.size-removed;val indexes=headers.filter{it.mpf};require(indexes.size<=1){"不支持多个MPF索引"}
        out.write(byteArrayOf(-1,-40));out.write(added)
        var cursor=2L
        for(s in headers){
            // Preserve every inter-marker fill byte, even beside replaced metadata. Removing it
            // would invalidate both the unchanged tail and MPF's primary/relative size deltas.
            cancel.check();copy(f,out,cursor,s.offset-cursor,cancel);cursor=s.offset+s.total
            if(replaced(s))continue
            if(s.mpf){
                val bytes=s.payload.copyOf();val tiff=CaptureExif(bytes.copyOfRange(4,bytes.size));val entries=tiff.root()
                val count=entries.firstOrNull{it.tag==0xb001}?:error("MPF缺少图像计数")
                val images=ByteBuffer.wrap(count.value).order(tiff.order).int;require(images==2){"只支持本应用生成的双图Ultra HDR"}
                val entry=entries.firstOrNull{it.tag==0xb002}?:error("MPF缺少图像索引")
                require(entry.type==7 && entry.value.size==32)
                val offset=ByteBuffer.wrap(entry.raw).order(tiff.order).getInt(8);require(offset>=8 && offset<=bytes.size-4-32)
                val data=ByteBuffer.wrap(bytes).order(tiff.order);val base=offset+4
                val primary=data.getInt(base+4).toLong() and 0xffffffffL
                val gainOffset=data.getInt(base+24).toLong() and 0xffffffffL
                require(primary in (s.offset+s.total)..(f.length()-2) && read(f,primary,2).contentEquals(byteArrayOf(-1,-40))){"Ultra HDR主图长度/增益图索引无效"}
                require(gainOffset+s.offset+8==primary){"Ultra HDR增益图相对偏移无效"}
                val anchorDelta=added.size-headers.filter{replaced(it) && it.offset<s.offset}.sumOf{it.total}
                val newPrimary=primary+delta;val newGainOffset=gainOffset+delta-anchorDelta
                require(newPrimary in 1..Int.MAX_VALUE.toLong() && newGainOffset in 1..Int.MAX_VALUE.toLong())
                data.putInt(base+4,newPrimary.toInt());data.putInt(base+24,newGainOffset.toInt())
                out.write(segment(s.marker,bytes))
            }else out.write(segment(s.marker,s.payload))
        }
        copy(f,out,cursor,f.length()-cursor,cancel) // fill, SOS, entropy, EOI, complete gainmap JPEG and metadata: byte-exact
    }
    private fun pngChunk(out:OutputStream,kind:String,payload:ByteArray){
        val name=kind.toByteArray(Charsets.US_ASCII);val data=DataOutputStream(out);data.writeInt(payload.size);data.write(name);data.write(payload)
        val crc=CRC32();crc.update(name);crc.update(payload);data.writeInt(crc.value.toInt())
    }
    private fun writePng(f:RandomAccessFile,out:OutputStream,exif:ByteArray,width:Int,height:Int,cancel:Cancel,extra:Extra){
        val chunks=chunks(f,true);val header=chunks.first();require(header.size==13L)
        val size=ByteBuffer.wrap(read(f,header.offset+8,8));require(size.int==width && size.int==height){"成片PNG尺寸与元数据不符"}
        out.write(PNG)
        for(c in chunks){cancel.check();val oldXmp=extra.xmp && c.kind=="iTXt" && read(f,c.offset+8,minOf(22,c.size.toInt())).starts("XML:com.adobe.xmp\u0000".toByteArray())
            if(c.kind!="eXIf" && !oldXmp && !(extra.archive && c.kind=="lmEt") && !(c.kind in setOf("tEXt","zTXt","iTXt") && extra.png.any{it.first==c.kind}))copy(f,out,c.offset,c.total,cancel)
            if(c.kind=="IHDR"){pngChunk(out,"eXIf",exif);extra.png.forEach{(k,p)->pngChunk(out,k,p)}}
        }
    }
    private fun webpChunk(out:OutputStream,kind:String,payload:ByteArray){out.write(kind.toByteArray(Charsets.US_ASCII));out.write(le(payload.size.toLong()));out.write(payload);if(payload.size%2!=0)out.write(0)}
    private fun writeWebp(f:RandomAccessFile,out:OutputStream,exif:ByteArray,width:Int,height:Int,cancel:Cancel,extra:Extra){
        require(width in 1..16777216 && height in 1..16777216)
        val chunks=chunks(f,false);require(chunks.none{it.kind in setOf("ANIM","ANMF")}){"不导出动画元数据"}
        val extended=chunks.firstOrNull{it.kind=="VP8X"};require(extended==null || chunks.first()==extended && extended.size==10L)
        val newSize=f.length()-chunks.filter{it.kind=="EXIF" || (extra.archive && it.kind=="LMET") || (extra.xmp && it.kind=="XMP ")}.sumOf{it.total}+8+exif.size+exif.size%2+extra.webp.sumOf{8L+it.second.size+it.second.size%2}+if(extended==null)18 else 0
        require(newSize in 12..0xffffffffL+8)
        out.write("RIFF".toByteArray());out.write(le(newSize-8));out.write("WEBP".toByteArray())
        var flags=8 or if(extra.xmp)4 else 0
        if(chunks.any{it.kind=="ALPH"})flags=flags or 16
        chunks.firstOrNull{it.kind=="VP8L"}?.let{c->val b=read(f,c.offset+8,5);require(b[0].toInt() and 255==47);if(le32(b,1) and (1L shl 28)!=0L)flags=flags or 16}
        val vp8x=if(extended!=null)read(f,extended.offset+8,10).also{it[0]=(it[0].toInt() or flags).toByte()}else ByteArray(10).also{p->
            p[0]=flags.toByte();for(i in 0..2){p[4+i]=((width-1) ushr (8*i)).toByte();p[7+i]=((height-1) ushr (8*i)).toByte()}
        }
        webpChunk(out,"VP8X",vp8x)
        var written=false
        for(c in chunks){
            cancel.check();if(c.kind in setOf("EXIF","VP8X") || (extra.archive && c.kind=="LMET") || (extra.xmp && c.kind=="XMP "))continue
            if(c.kind=="XMP " && !written){webpChunk(out,"EXIF",exif);written=true}
            copy(f,out,c.offset,c.total,cancel)
        }
        if(!written)webpChunk(out,"EXIF",exif)
        extra.webp.forEach{(k,p)->webpChunk(out,k,p)}
    }
    fun write(source:File,target:File,exif:ByteArray,width:Int,height:Int,cancel:Cancel)=writeExtra(source,target,exif,width,height,cancel,Extra())
    internal fun writePreserved(encoded:File,target:File,original:PreservedMetadata.Bundle,width:Int,height:Int,badge:Boolean,cancel:Cancel,outputSrgb:Boolean=true){
        cancel.check();val dest=PreservedMetadata.read(encoded,cancel)
        val srcExif=original.exif?.let(::CaptureExif)?:CaptureExif.empty()
        val exif=srcExif.normalized(width,height,XiaomiLeicaBadge.patches(srcExif,badge),outputSrgb)
        val archive=PreservedMetadata.archive(original)
        val xml=PreservedMetadata.mergedXmp(original.xmp,dest.xmp,width,height,outputSrgb)
        val jpeg=ArrayList<Pair<Int,ByteArray>>();val png=ArrayList<Pair<String,ByteArray>>();val webp=ArrayList<Pair<String,ByteArray>>()
        PreservedMetadata.jpegXmp(xml,dest.xmp,width,height,outputSrgb).forEach{jpeg.add(0xe1 to it)}
        original.packets.forEach{p->when(p.kind){"J:ed"->PreservedMetadata.descriptiveIptc(p.bytes)?.let{jpeg.add(0xed to it)};"J:fe"->jpeg.add(0xfe to p.bytes)}}
        PreservedMetadata.archiveSegments(archive).forEach{jpeg.add(0xeb to it)}
        png.add("lmEt" to archive);webp.add("LMET" to archive)
        if(xml!=null){png.add("iTXt" to ("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000".toByteArray()+xml));webp.add("XMP " to xml)}
        if(original.format=="PNG")original.packets.filter{it.kind in setOf("P:iTXt","P:tEXt","P:zTXt") && !it.bytes.starts("XML:com.adobe.xmp\u0000".toByteArray())}.forEach{png.add(it.kind.substring(2) to it.bytes)}
        writeExtra(encoded,target,exif,width,height,cancel,Extra(jpeg,png,webp,xml!=null))
        try{check(PreservedMetadata.readArchive(target,cancel).contentEquals(archive)){"原始元数据完整性回读失败，未发布"}}catch(t:Throwable){target.delete();throw t}
    }
    private fun writeExtra(source:File,target:File,exif:ByteArray,width:Int,height:Int,cancel:Cancel,extra:Extra){
        CaptureExif(exif).let{it.root();it.exif();it.gps()};require(source.canonicalPath!=target.canonicalPath)
        check(!target.exists()){"元数据目标已存在，未覆盖"};check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        var complete=false
        try{
            RandomAccessFile(source,"r").use{f->target.outputStream().use{out->
                val head=read(f,0,minOf(12L,f.length()).toInt());cancel.check()
                when{head.starts(byteArrayOf(-1,-40))->writeJpeg(f,out,exif,cancel,extra);head.starts(PNG)->writePng(f,out,exif,width,height,cancel,extra);else->writeWebp(f,out,exif,width,height,cancel,extra)}
                cancel.check();out.fd.sync()
            }}
            val captured=readExif(target);check(captured.supported && captured.exif?.contentEquals(exif)==true){"成片元数据回读不一致，未发布"}
            cancel.check();complete=true
        }finally{if(!complete)target.delete()}
    }
}
