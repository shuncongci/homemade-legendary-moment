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
    private data class Chunk(val kind:String,val offset:Long,val size:Long,val total:Long)
    private data class Segment(val marker:Int,val offset:Long,val payload:ByteArray){val total get()=payload.size+4;val exif get()=marker==0xe1 && payload.starts(EXIF);val mpf get()=marker==0xe2 && payload.starts(MPF)}
    private val EXIF=byteArrayOf(69,120,105,102,0,0)
    private val MPF=byteArrayOf(77,80,70,0)
    private val PNG=byteArrayOf(-119,80,78,71,13,10,26,10)
    private fun ByteArray.starts(prefix:ByteArray)=size>=prefix.size && prefix.indices.all{this[it]==prefix[it]}
    private fun read(f:RandomAccessFile,offset:Long,n:Int):ByteArray {require(n>=0 && n<=CaptureExif.MAX_BYTES && offset>=0 && offset<=f.length()-n){"元数据容器越界"};f.seek(offset);return ByteArray(n).also{f.readFully(it)}}
    private fun le32(b:ByteArray,offset:Int=0)=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xffffffffL
    private fun le(n:Long)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n.toInt()).array()
    private fun jpegHeaders(f:RandomAccessFile):List<Segment>{
        require(f.length()>=4 && read(f,0,2).contentEquals(byteArrayOf(-1,-40))){"JPEG容器无效"}
        val list=mutableListOf<Segment>();var at=2L;var bytes=0
        while(true){
            val header=read(f,at,4);require(header[0]==(-1).toByte()){"JPEG头损坏"};val marker=header[1].toInt() and 255
            if(marker==0xda)break
            require(marker!=0xd9 && marker !in 0xd0..0xd8 && marker!=1){"JPEG没有图像扫描数据"}
            val n=((header[2].toInt() and 255) shl 8)+(header[3].toInt() and 255);require(n>=2)
            bytes=Math.addExact(bytes,n+2);require(bytes<=4*CaptureExif.MAX_BYTES && list.size<1024){"JPEG元数据头超限"}
            list+=Segment(marker,at,read(f,at+4,n-2));at+=n+2
        }
        return list
    }
    private fun chunks(f:RandomAccessFile,png:Boolean):List<Chunk>{
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
                val extra=headers.any{it.marker==0xed || it.marker==0xfe || (it.marker==0xe1 && !it.exif && it.payload.toString(Charsets.UTF_8).let{s->!s.contains("hdrgm:") || s.contains("dc:") || s.contains("photoshop:")})}
                Read(selected,true,extraExif || extra)
            }
            first.starts(PNG)->{
                val chunks=chunks(f,true);val exifs=chunks.filter{it.kind=="eXIf"};require(exifs.size<=1){"PNG存在重复EXIF块"}
                val packet=exifs.firstOrNull()?.let{c->
                    val payload=read(f,c.offset+8,c.size.toInt());val crc=CRC32();crc.update("eXIf".toByteArray());crc.update(payload)
                    val expected=ByteBuffer.wrap(read(f,c.offset+8+c.size,4)).int.toLong() and 0xffffffffL
                    require(crc.value==expected){"PNG EXIF校验和错误，原片保留"};payload
                }
                Read(packet,true,chunks.any{it.kind in setOf("tEXt","iTXt","zTXt")})
            }
            first.size>=12 && String(first,0,4,Charsets.US_ASCII)=="RIFF" && String(first,8,4,Charsets.US_ASCII)=="WEBP"->{
                val chunks=chunks(f,false);val exifs=chunks.filter{it.kind=="EXIF"};require(exifs.size<=1){"WebP存在重复EXIF块"}
                val packet=exifs.firstOrNull()?.let{read(f,it.offset+8,it.size.toInt())}
                Read(packet?.let{if(it.starts(EXIF))it.copyOfRange(6,it.size)else it},true,chunks.any{it.kind=="XMP "})
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
    private fun writeJpeg(f:RandomAccessFile,out:OutputStream,exif:ByteArray,cancel:Cancel){
        val headers=jpegHeaders(f);val added=segment(0xe1,EXIF+exif);val removed=headers.filter{it.exif}.sumOf{it.total}
        val delta=added.size-removed;val indexes=headers.filter{it.mpf};require(indexes.size<=1){"不支持多个MPF索引"}
        out.write(byteArrayOf(-1,-40));out.write(added)
        for(s in headers){
            cancel.check();if(s.exif)continue
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
                val anchorDelta=added.size-headers.filter{it.exif && it.offset<s.offset}.sumOf{it.total}
                val newPrimary=primary+delta;val newGainOffset=gainOffset+delta-anchorDelta
                require(newPrimary in 1..Int.MAX_VALUE.toLong() && newGainOffset in 1..Int.MAX_VALUE.toLong())
                data.putInt(base+4,newPrimary.toInt());data.putInt(base+24,newGainOffset.toInt())
                out.write(segment(s.marker,bytes))
            }else out.write(segment(s.marker,s.payload))
        }
        val tail=headers.lastOrNull()?.let{it.offset+it.total}?:2L
        copy(f,out,tail,f.length()-tail,cancel) // SOS, entropy, EOI, complete gainmap JPEG and metadata: byte-exact
    }
    private fun pngChunk(out:OutputStream,kind:String,payload:ByteArray){
        val name=kind.toByteArray(Charsets.US_ASCII);val data=DataOutputStream(out);data.writeInt(payload.size);data.write(name);data.write(payload)
        val crc=CRC32();crc.update(name);crc.update(payload);data.writeInt(crc.value.toInt())
    }
    private fun writePng(f:RandomAccessFile,out:OutputStream,exif:ByteArray,width:Int,height:Int,cancel:Cancel){
        val chunks=chunks(f,true);val header=chunks.first();require(header.size==13L)
        val size=ByteBuffer.wrap(read(f,header.offset+8,8));require(size.int==width && size.int==height){"成片PNG尺寸与元数据不符"}
        out.write(PNG)
        for(c in chunks){cancel.check();if(c.kind!="eXIf")copy(f,out,c.offset,c.total,cancel);if(c.kind=="IHDR")pngChunk(out,"eXIf",exif)}
    }
    private fun webpChunk(out:OutputStream,kind:String,payload:ByteArray){out.write(kind.toByteArray(Charsets.US_ASCII));out.write(le(payload.size.toLong()));out.write(payload);if(payload.size%2!=0)out.write(0)}
    private fun writeWebp(f:RandomAccessFile,out:OutputStream,exif:ByteArray,width:Int,height:Int,cancel:Cancel){
        require(width in 1..16777216 && height in 1..16777216)
        val chunks=chunks(f,false);require(chunks.none{it.kind in setOf("ANIM","ANMF")}){"不导出动画元数据"}
        val extended=chunks.firstOrNull{it.kind=="VP8X"};require(extended==null || chunks.first()==extended && extended.size==10L)
        val newSize=f.length()-chunks.filter{it.kind=="EXIF"}.sumOf{it.total}+8+exif.size+exif.size%2+if(extended==null)18 else 0
        require(newSize in 12..0xffffffffL+8)
        out.write("RIFF".toByteArray());out.write(le(newSize-8));out.write("WEBP".toByteArray())
        var flags=8
        if(chunks.any{it.kind=="ALPH"})flags=flags or 16
        chunks.firstOrNull{it.kind=="VP8L"}?.let{c->val b=read(f,c.offset+8,5);require(b[0].toInt() and 255==47);if(le32(b,1) and (1L shl 28)!=0L)flags=flags or 16}
        val vp8x=if(extended!=null)read(f,extended.offset+8,10).also{it[0]=(it[0].toInt() or 8).toByte()}else ByteArray(10).also{p->
            p[0]=flags.toByte();for(i in 0..2){p[4+i]=((width-1) ushr (8*i)).toByte();p[7+i]=((height-1) ushr (8*i)).toByte()}
        }
        webpChunk(out,"VP8X",vp8x)
        var written=false
        for(c in chunks){
            cancel.check();if(c.kind in setOf("EXIF","VP8X"))continue
            if(c.kind=="XMP " && !written){webpChunk(out,"EXIF",exif);written=true}
            copy(f,out,c.offset,c.total,cancel)
        }
        if(!written)webpChunk(out,"EXIF",exif)
    }
    fun write(source:File,target:File,exif:ByteArray,width:Int,height:Int,cancel:Cancel){
        CaptureExif(exif).let{it.root();it.exif();it.gps()};require(source.canonicalPath!=target.canonicalPath)
        check(!target.exists()){"元数据目标已存在，未覆盖"};check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        var complete=false
        try{
            RandomAccessFile(source,"r").use{f->target.outputStream().use{out->
                val head=read(f,0,minOf(12L,f.length()).toInt());cancel.check()
                when{head.starts(byteArrayOf(-1,-40))->writeJpeg(f,out,exif,cancel);head.starts(PNG)->writePng(f,out,exif,width,height,cancel);else->writeWebp(f,out,exif,width,height,cancel)}
                cancel.check();out.fd.sync()
            }}
            val captured=readExif(target);check(captured.supported && captured.exif?.contentEquals(exif)==true){"成片元数据回读不一致，未发布"}
            cancel.check();complete=true
        }finally{if(!complete)target.delete()}
    }
}
