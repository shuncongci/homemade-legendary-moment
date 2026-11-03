package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Additional INPUT containers, not output codecs. No platform tag whitelist or thumbnail fallback.
 * Item/IFD bytes are retained at their original TIFF offsets; unknown metadata is archived.
 * These containers may hold auxiliary images, animation or RAW data: external deletion is NOT earned.
 */
internal object SourceFormatMetadata {
    private const val MAX=PreservedMetadata.MAX
    private data class Box(val type:String,val start:Long,val data:Long,val end:Long)
    private class Reader(val f:RandomAccessFile,val cancel:Cancel) {
        var steps=0
        fun tick(){cancel.check();require(++steps<=100000){"来源元数据结构过多"}}
        fun bytes(at:Long,n:Long):ByteArray {tick();require(n in 0..MAX.toLong() && at>=0 && at<=f.length()-n){"来源元数据截断或超限，原片保留"};f.seek(at);return ByteArray(n.toInt()).also{f.readFully(it)}}
        fun u(at:Long,n:Int):Long {require(n in 0..8);val b=bytes(at,n.toLong());var v=0L;for(c in b){require(v<=Long.MAX_VALUE ushr 8){"来源偏移超限"};v=(v shl 8) or (c.toLong() and 255)};return v}
        fun boxes(start:Long,end:Long):List<Box>{
            require(start>=0 && start<=end && end<=f.length());val result=ArrayList<Box>();var p=start
            while(p<end){tick();require(end-p>=8){"HEIF盒头截断"};val short=u(p,4);val type=String(bytes(p+4,4),Charsets.ISO_8859_1)
                val header=if(short==1L)16L else 8L;require(end-p>=header)
                val size=when(short){0L->end-p;1L->u(p+8,8);else->short};require(size>=header && size<=end-p){"HEIF盒长度无效"}
                result+=Box(type,p,p+header,p+size);p+=size
            };return result
        }
    }
    private class Packets {
        val packets=ArrayList<PreservedMetadata.Packet>();var total=0
        fun add(kind:String,b:ByteArray){total=Math.addExact(total,b.size);require(total<=MAX){"来源元数据超过4MiB上限，未丢弃后导出"};packets+=PreservedMetadata.Packet(kind,b)}
        fun bundle(exif:ByteArray?,xmp:List<ByteArray>,format:String)=PreservedMetadata.Bundle(packets,exif,xmp,format)
    }
    fun read(file:File,cancel:Cancel):PreservedMetadata.Bundle? = RandomAccessFile(file,"r").use{f->
        val r=Reader(f,cancel);val head=r.bytes(0,minOf(16,f.length()));val text=String(head,Charsets.ISO_8859_1)
        when {
            head.size>=16 && text.substring(4,8)=="ftyp"->heif(r)
            head.size>=4 && (text.startsWith("II*\u0000") || text.startsWith("MM\u0000*"))->tiff(r)
            text.startsWith("GIF87a") || text.startsWith("GIF89a")->gif(r)
            text.startsWith("BM")->bmp(r)
            else->null
        }
    }
    private data class Item(val id:Long,val type:String,val content:String)
    private data class Location(val method:Int,val base:Long,val extents:List<Pair<Long,Long>>)
    private fun heif(r:Reader):PreservedMetadata.Bundle {
        val top=r.boxes(0,r.f.length());val ftyp=top.single{it.type=="ftyp"};val brand=r.bytes(ftyp.data,ftyp.end-ftyp.data)
        require(brand.size>=8 && brand.size%4==0);val brands=listOf(String(brand,0,4))+ (8 until brand.size step 4).map{String(brand,it,4)}
        require(brands.any{it in setOf("heic","heix","hevc","hevx","heim","heis","hevm","hevs","mif1","msf1","avif","avis")}){"此ISO媒体容器不是支持的HEIF/AVIF照片"}
        val p=Packets();p.add("H:ftyp",brand)
        val meta=top.singleOrNull{it.type=="meta"}?:error("HEIF缺少或重复meta目录")
        require(r.u(meta.data,4)==0L){"不支持此HEIF meta版本"}
        val children=r.boxes(meta.data+4,meta.end)
        fun only(type:String)=children.singleOrNull{it.type==type}?:error("HEIF缺少或重复${type}目录")
        val info=only("iinf");val iv=r.u(info.data,1).toInt();require(iv in 0..1)
        val count=r.u(info.data+4,if(iv==0)2 else 4);val entries=r.boxes(info.data+if(iv==0)6 else 8,info.end);require(count==entries.size.toLong())
        val items=LinkedHashMap<Long,Item>()
        for(b in entries){require(b.type=="infe");val v=r.u(b.data,1).toInt();require(v in 2..3){"HEIF项目描述版本暂不支持，未忽略元数据"}
            val data=r.bytes(b.data,b.end-b.data);val bb=ByteBuffer.wrap(data);bb.position(4);val id=if(v==2)bb.short.toLong() and 65535 else bb.int.toLong() and 0xffffffffL
            require(bb.short.toInt()==0){"加密HEIF不支持"};val typeBytes=ByteArray(4);bb.get(typeBytes);val type=String(typeBytes,Charsets.ISO_8859_1)
            fun string():String {val out=ByteArrayOutputStream();while(bb.hasRemaining()){val c=bb.get();if(c==0.toByte())return out.toString("UTF-8");out.write(c.toInt())};error("HEIF项目字符串截断")}
            string();val content=if(type=="mime")string()else "";if(type=="mime" && bb.hasRemaining())require(string().isEmpty()){"压缩HEIF元数据暂不支持"}
            require(items.put(id,Item(id,type,content))==null){"HEIF项目ID重复"}
        }
        val location=only("iloc");val data=r.bytes(location.data,location.end-location.data);val bb=ByteBuffer.wrap(data)
        fun u(n:Int):Long {require(n in 0..8 && bb.remaining()>=n);var value=0L;repeat(n){require(value<=Long.MAX_VALUE ushr 8);value=(value shl 8) or (bb.get().toLong() and 255)};return value}
        val version=u(1).toInt();require(version in 0..2);require(u(3)==0L)
        val sizes=u(1).toInt();val offsetSize=sizes ushr 4;val lengthSize=sizes and 15;val sizes2=u(1).toInt();val baseSize=sizes2 ushr 4;val indexSize=if(version>0)sizes2 and 15 else 0
        require(listOf(offsetSize,lengthSize,baseSize,indexSize).all{it in setOf(0,4,8)}){"HEIF位置字段宽度不支持"}
        val n=u(if(version<2)2 else 4);require(n<=100000);val locations=HashMap<Long,Location>()
        repeat(n.toInt()){
            val id=u(if(version<2)2 else 4);val method=if(version>0)u(2).toInt()else 0;val reference=u(2);val base=u(baseSize);val extentCount=u(2);require(extentCount<=4096)
            val extents=ArrayList<Pair<Long,Long>>();repeat(extentCount.toInt()){if(version>0 && indexSize>0)u(indexSize);extents+=u(offsetSize) to u(lengthSize)}
            // Other image locations may be derived. Only dereference metadata locations below.
            require(locations.put(id,Location(if(reference==0L)method else -1,base,extents))==null)
        };require(!bb.hasRemaining())
        val idats=children.filter{it.type=="idat"}
        fun payload(item:Item):ByteArray {
            val l=locations[item.id]?:error("HEIF元数据缺少位置");require(l.method in 0..1 && l.extents.isNotEmpty()){"HEIF元数据引用方式暂不支持，原片保留"}
            val region=if(l.method==1)idats.singleOrNull()?:error("HEIF缺少或重复idat")else null
            val begin=region?.data?:0L;val end=region?.end?:r.f.length();val out=ByteArrayOutputStream()
            for((offset,length) in l.extents){r.tick();val at=Math.addExact(begin,Math.addExact(l.base,offset));val len=if(lengthSize==0)end-at else length
                require(at>=begin && len>=0 && at<=end-len && len<=MAX-out.size()){ "HEIF元数据项目越界或超限" };out.write(r.bytes(at,len))
            };return out.toByteArray()
        }
        val exifs=ArrayList<ByteArray>();val xmps=ArrayList<ByteArray>()
        for(item in items.values){if(item.type in setOf("Exif","mime","uri ")){
            val b=payload(item);p.add("H:${item.id}:${item.type}",b)
            if(item.type=="Exif"){
                require(b.size>=4);val offset=ByteBuffer.wrap(b).int.toLong() and 0xffffffffL;require(offset<=b.size-4L)
                val exif=b.copyOfRange((4+offset).toInt(),b.size);CaptureExif(exif).apply{root();exif();gps()};exifs+=exif
            }else if(item.type=="mime" && item.content.lowercase() in setOf("application/rdf+xml","application/xmp+xml"))xmps+=b
        }}
        // Preserve box descriptors (item info, references and color/geometry properties), never copy
        // mdat/idat coded images into metadata or reuse them as active HDR/depth/thumbnail structures.
        for(b in children)if(b.type!="idat")p.add("H:box:${b.type}",r.bytes(b.start,b.end-b.start))
        for(b in top)if(b.type !in setOf("ftyp","meta","mdat","free","skip","moov"))p.add("H:top:${b.type}",r.bytes(b.start,b.end-b.start))
        return p.bundle(ExifPacketSelection.select(exifs),xmps,if(brands.any{it in setOf("avif","avis")})"AVIF"else "HEIF")
    }
    private fun tiff(r:Reader):PreservedMetadata.Bundle {
        val little=String(r.bytes(0,2))=="II";val order=if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        fun number(at:Long,n:Int):Long {val b=r.bytes(at,n.toLong());val bb=ByteBuffer.wrap(b).order(order);return if(n==2)bb.short.toLong() and 65535 else bb.int.toLong() and 0xffffffffL}
        val ranges=ArrayList<Pair<Long,Int>>();ranges+=0L to 8;val visited=HashSet<Long>();var end=8L
        fun keep(at:Long,n:Long){require(at>=0 && n in 0..CaptureExif.MAX_BYTES.toLong() && at<=r.f.length()-n && at<=CaptureExif.MAX_BYTES-n){"TIFF拍摄字段偏移超过无损EXIF上限，原片保留"};ranges+=at to n.toInt();end=maxOf(end,at+n)}
        fun walk(offset:Long,image:Boolean){
            if(offset==0L)return;require(visited.add(offset) && visited.size<=128){"TIFF目录循环或过多"}
            val n=number(offset,2);require(n<=4096);keep(offset,2+n*12+if(image)4 else 0)
            for(i in 0 until n.toInt()){
                val at=offset+2+i*12;val tag=number(at,2).toInt();val type=number(at+2,2).toInt();val count=number(at+4,4)
                val unit=when(type){1,2,6,7->1;3,8->2;4,9,11,13->4;5,10,12->8;else->error("TIFF字段类型${type}暂不支持")};val size=Math.multiplyExact(count,unit.toLong())
                val value=if(size>4)number(at+8,4)else at+8;keep(value,size)
                if(tag in setOf(0x8769,0x8825,0xa005,0x14a)){require(type in setOf(4,13) && count<=128);repeat(count.toInt()){walk(number(value+it*4,4),tag==0x14a)}}
            }
            if(image)walk(number(offset+2+n*12,4),true)
        }
        walk(number(4,4),true)
        val raw=ByteArray(end.toInt());for((at,n) in ranges)r.bytes(at,n.toLong()).copyInto(raw,at.toInt())
        // Gaps (usually source image pixels) are zero, but every directory/value, including unknown
        // tags and MakerNote offsets, stays in its original TIFF coordinate space.
        val original=CaptureExif(raw);val p=Packets();p.add("T:metadata",raw)
        val root=original.root();val xmp=root.filter{it.tag==700}.map{it.value}
        // TIFF/DNG pixel-processing instructions must not describe our new JPEG/PNG/WebP pixels.
        val active=root.filterNot{it.tag in setOf(700,0x83bb,0x8649,0x828d,0x828e) || (it.tag in 0xc612..0xcdff && it.tag !in setOf(0xc614,0xc615,0xc62f,0xc630))}
        val out=ByteArrayOutputStream();out.write(raw);if(out.size()%2!=0)out.write(0);val at=out.size();val dir=ByteBuffer.allocate(2+12*active.size+4).order(order)
        dir.putShort(active.size.toShort());active.forEach{dir.put(it.raw)};dir.putInt(0);out.write(dir.array());val exif=out.toByteArray();require(exif.size<=CaptureExif.MAX_BYTES);ByteBuffer.wrap(exif).order(order).putInt(4,at)
        return p.bundle(exif,xmp,if(root.any{it.tag==0xc612})"DNG"else "TIFF")
    }
    private fun gif(r:Reader):PreservedMetadata.Bundle {
        val p=Packets();val h=r.bytes(0,13);p.add("G:header",h);var at=13L
        if(h[10].toInt() and 128!=0)at+=3L*(1 shl ((h[10].toInt() and 7)+1))
        var images=0
        fun blocks(store:Boolean):ByteArray{val out=ByteArrayOutputStream();while(true){val n=r.u(at++,1).toInt();require(at<=r.f.length()-n);if(store){require(out.size()+n+1<=MAX);out.write(n);out.write(r.bytes(at,n.toLong()))};at+=n;if(n==0)return out.toByteArray();r.tick()}}
        while(true){when(r.u(at++,1).toInt()){
            0x21->{val label=r.u(at++,1).toInt();val b=blocks(true);p.add("G:extension:$label",b)}
            0x2c->{val d=r.bytes(at,9);at+=9;if(d[8].toInt() and 128!=0)at+=3L*(1 shl ((d[8].toInt() and 7)+1));require(r.u(at++,1) in 2..8);blocks(false);images++}
            0x3b->{require(at==r.f.length() && images>0){"GIF尾随数据或没有图像，未忽略后导出"};return p.bundle(null,emptyList(),"GIF")}
            else->error("GIF块结构无效")
        }}
    }
    private fun bmp(r:Reader):PreservedMetadata.Bundle {
        val p=Packets();val header=r.bytes(0,14);val bb=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val size=bb.getInt(2).toLong() and 0xffffffffL;val pixels=bb.getInt(10).toLong() and 0xffffffffL
        require(size==r.f.length() && pixels>=26 && pixels<=size){"BMP长度或像素偏移无效"}
        val dib=ByteBuffer.wrap(r.bytes(14,4)).order(ByteOrder.LITTLE_ENDIAN).int
        require(dib in setOf(12,40,52,56,108,124) && pixels>=14L+dib){"BMP头版本不支持"}
        p.add("B:header",r.bytes(0,pixels))
        if(dib==124){val d=ByteBuffer.wrap(r.bytes(14,124)).order(ByteOrder.LITTLE_ENDIAN);val offset=d.getInt(112).toLong() and 0xffffffffL;val length=d.getInt(116).toLong() and 0xffffffffL
            if(length>0){require(offset>=124 && offset+14<=size-length);p.add("B:profile",r.bytes(offset+14,length))}}
        return p.bundle(null,emptyList(),"BMP")
    }
}
