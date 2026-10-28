package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Descriptive metadata is active EXIF/XMP/IPTC where the destination supports it.
 * Original packets (including unknown vendor metadata and source color/geometry descriptors) are
 * also retained losslessly in a bounded, checksummed archive. They are NOT active image structures:
 * never reattach source ICC, thumbnails, MPF offsets or gainmaps to different pixels.
 */
internal object PreservedMetadata {
    const val MAX=4*1024*1024
    data class Packet(val kind:String,val bytes:ByteArray)
    data class Bundle(val packets:List<Packet>,val exif:ByteArray?,val xmp:List<ByteArray>,val format:String)
    val XMP="http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val EXT="http://ns.adobe.com/xmp/extension/\u0000".toByteArray()
    val ARCHIVE="LMETA1\u0000".toByteArray()
    private val TIFF="http://ns.adobe.com/tiff/1.0/"
    private val EXIF="http://ns.adobe.com/exif/1.0/"
    private val RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val NOTE="http://ns.adobe.com/xmp/note/"
    fun starts(b:ByteArray,p:ByteArray)=b.size>=p.size && p.indices.all{b[it]==p[it]}
    private fun bytes(f:RandomAccessFile,at:Long,n:Long):ByteArray{require(n in 0..(MAX+65568).toLong() && at>=0 && at<=f.length()-n){"元数据超限或截断，未静默丢弃"};f.seek(at);return ByteArray(n.toInt()).also{f.readFully(it)}}
    private fun bounded(input:InputStream):ByteArray{val out=ByteArrayOutputStream();val b=ByteArray(8192);input.use{while(true){val n=it.read(b);if(n<0)break;require(out.size()+n<=MAX){"元数据解压超限"};out.write(b,0,n)}};return out.toByteArray()}
    private fun pngXmp(p:Packet):ByteArray? {
        if(p.kind!in setOf("P:iTXt","P:tEXt","P:zTXt"))return null
        val b=p.bytes;val end=b.indexOf(0);if(end<0)return null
        if(String(b,0,end,Charsets.ISO_8859_1)!="XML:com.adobe.xmp")return null
        var at=end+1
        if(p.kind=="P:tEXt")return b.copyOfRange(at,b.size)
        if(p.kind=="P:zTXt"){require(at<b.size && b[at++].toInt()==0);return bounded(InflaterInputStream(ByteArrayInputStream(b,at,b.size-at)))}
        require(at+2<=b.size);val compressed=b[at++].toInt();require(compressed in 0..1 && b[at++].toInt()==0)
        repeat(2){while(at<b.size && b[at]!=0.toByte())at++;require(at<b.size);at++}
        return if(compressed==0)b.copyOfRange(at,b.size)else bounded(InflaterInputStream(ByteArrayInputStream(b,at,b.size-at)))
    }
    fun read(file:File,cancel:Cancel,limit:Int=MAX):Bundle {
        val packets=ArrayList<Packet>();var total=0
        fun add(kind:String,b:ByteArray){total=Math.addExact(total,b.size);require(total<=limit){"原图元数据超过安全上限，未丢弃后导出"};packets.add(Packet(kind,b))}
        var format=""
        RandomAccessFile(file,"r").use{f->val head=bytes(f,0,minOf(12L,f.length()))
            when{
                starts(head,byteArrayOf(-1,-40))->{format="JPEG";for(s in CaptureMetadataContainer.jpegHeaders(f)){cancel.check();if(s.marker in 0xe0..0xef || s.marker==0xfe)add("J:${s.marker.toString(16)}",s.payload)}}
                starts(head,byteArrayOf(-119,80,78,71,13,10,26,10))->{format="PNG";for(c in CaptureMetadataContainer.chunks(f,true)){cancel.check();if(c.kind[0].isLowerCase() && c.kind !in setOf("acTL","fcTL","fdAT")){
                    val b=bytes(f,c.offset+8,c.size);val crc=java.util.zip.CRC32();crc.update(c.kind.toByteArray(Charsets.US_ASCII));crc.update(b)
                    require(crc.value==(ByteBuffer.wrap(bytes(f,c.offset+8+c.size,4)).int.toLong() and 0xffffffffL)){"PNG元数据CRC错误"};add("P:${c.kind}",b)
                }}}
                head.size>=12 && String(head,0,4)=="RIFF" && String(head,8,4)=="WEBP"->{format="WEBP";for(c in CaptureMetadataContainer.chunks(f,false)){cancel.check();if(c.kind !in setOf("VP8X","VP8 ","VP8L","ALPH","ANIM","ANMF"))add("W:${c.kind}",bytes(f,c.offset+8,c.size))}}
                else->error("此来源格式暂不能完整保留元数据；未导出。请选原始JPEG、PNG或WebP，原片不变。")
            }
        }
        val xmp=ArrayList<ByteArray>()
        packets.forEach{p->when{
            p.kind=="J:e1" && starts(p.bytes,XMP)->xmp.add(p.bytes.copyOfRange(XMP.size,p.bytes.size))
            p.kind=="W:XMP "->xmp.add(p.bytes)
            p.kind.startsWith("P:")->pngXmp(p)?.let{xmp.add(it)}
        }}
        val extensions=packets.filter{it.kind=="J:e1" && starts(it.bytes,EXT)}
        for(group in extensions.groupBy{p->require(p.bytes.size>=EXT.size+40);String(p.bytes,EXT.size,32,Charsets.US_ASCII)}.values){
            val size=ByteBuffer.wrap(group.first().bytes).getInt(EXT.size+32);require(size in 1..MAX)
            val data=ByteArray(size);var next=0
            for(p in group.sortedBy{ByteBuffer.wrap(it.bytes).getInt(EXT.size+36)}){val b=p.bytes;require(ByteBuffer.wrap(b).getInt(EXT.size+32)==size);val at=ByteBuffer.wrap(b).getInt(EXT.size+36);val n=b.size-EXT.size-40;require(at==next && n>0 && n<=size-at);b.copyInto(data,at,EXT.size+40);next+=n}
            require(next==size);val guid=MessageDigest.getInstance("MD5").digest(data).joinToString(""){"%02X".format(it)}
            require(guid==String(group.first().bytes,EXT.size,32,Charsets.US_ASCII).uppercase()){"扩展XMP校验错误"};xmp.add(data)
        }
        return Bundle(packets,CaptureMetadataContainer.readExif(file).exif,xmp,format)
    }
    fun archive(bundle:Bundle):ByteArray {
        val raw=ByteArrayOutputStream();DataOutputStream(raw).use{out->out.writeInt(0x4c4d4431);out.writeInt(bundle.packets.size);for(p in bundle.packets){out.writeUTF(p.kind);out.writeInt(p.bytes.size);out.write(p.bytes)}}
        val b=raw.toByteArray();require(b.size<=MAX+65536);return b+MessageDigest.getInstance("SHA-256").digest(b)
    }
    fun unarchive(b:ByteArray):List<Packet> {
        require(b.size in 40..MAX+65568);val data=b.copyOfRange(0,b.size-32)
        require(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(b.copyOfRange(data.size,b.size))){"原始元数据归档校验失败"}
        return DataInputStream(ByteArrayInputStream(data)).use{input->require(input.readInt()==0x4c4d4431);val count=input.readInt();require(count in 0..100000);List(count){val name=input.readUTF();val n=input.readInt();require(n in 0..MAX && n<=input.available());Packet(name,ByteArray(n).also{input.readFully(it)})}.also{require(input.read()==-1)}}
    }
    private fun parse(bytes:ByteArray):org.w3c.dom.Document {
        require(bytes.size<=MAX)
        // Reject DTDs even on Android parsers without the optional Xerces hardening feature.
        val text=bytes.toString(Charsets.UTF_8).replace("\u0000","")
        require(!text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)){"不接受含外部实体/DTD的XMP"}
        val f=DocumentBuilderFactory.newInstance();f.isNamespaceAware=true;f.isExpandEntityReferences=false
        val builder=f.newDocumentBuilder();builder.setEntityResolver{_,_->throw IllegalArgumentException("XMP禁止外部访问")}
        return builder.parse(ByteArrayInputStream(bytes))
    }
    private fun serialize(doc:org.w3c.dom.Document):ByteArray {
        val out=ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc),StreamResult(out));return out.toByteArray().also{require(it.size<=MAX)}
    }
    private fun forbidden(ns:String?,name:String)=ns in setOf("http://ns.adobe.com/hdr-gain-map/1.0/","http://ns.google.com/photos/1.0/container/","http://ns.google.com/photos/1.0/container/item/") ||
        (ns=="http://ns.google.com/photos/1.0/camera/" && (name.startsWith("MicroVideo") || name.startsWith("MotionPhoto"))) || (ns==NOTE && name=="HasExtendedXMP") ||
        (ns=="http://ns.adobe.com/xap/1.0/" && name=="Thumbnails") || (ns=="http://ns.adobe.com/photoshop/1.0/" && name=="ICCProfile")
    private fun geometry(ns:String?,name:String)=ns==TIFF && name in setOf("ImageWidth","ImageLength","Orientation","PhotometricInterpretation","BitsPerSample","SamplesPerPixel") || ns==EXIF && name in setOf("PixelXDimension","PixelYDimension","ColorSpace")
    /** Source descriptions are preserved; only stale layout/color/HDR/motion structure is normalized.
     * Destination HDR XMP is imported untouched and never overwritten by the source. */
    fun mergedXmp(source:List<ByteArray>,destination:List<ByteArray>,w:Int,h:Int,outputSrgb:Boolean=true):ByteArray? {
        if(source.isEmpty())return null
        val doc=parse("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$RDF\"/></x:xmpmeta>".toByteArray())
        val rdf=doc.getElementsByTagNameNS(RDF,"RDF").item(0)
        fun clean(e:Element){
            for(i in e.attributes.length-1 downTo 0){val a=e.attributes.item(i);if(forbidden(a.namespaceURI,a.localName?:a.nodeName) || geometry(a.namespaceURI,a.localName?:a.nodeName))e.removeAttributeNode(a as org.w3c.dom.Attr)}
            for(i in e.childNodes.length-1 downTo 0){val node=e.childNodes.item(i);if(node is Element){if(forbidden(node.namespaceURI,node.localName?:node.nodeName) || geometry(node.namespaceURI,node.localName?:node.nodeName))e.removeChild(node)else clean(node)}}
        }
        fun append(b:ByteArray,scrub:Boolean){
            val input=parse(b);val containers=input.getElementsByTagNameNS(RDF,"RDF")
            require(containers.length==1){"XMP缺少或重复RDF，未丢弃后导出"}
            val container=containers.item(0) as Element
            // An empty RDF graph is valid XMP, not a missing/damaged description. Its original
            // bytes still go into the checksummed archive. Do not mistake unsupported RDF nodes
            // or non-whitespace text for an empty graph and silently lose descriptive metadata.
            for(i in 0 until container.attributes.length){val a=container.attributes.item(i);require(a.namespaceURI=="http://www.w3.org/2000/xmlns/" || a.namespaceURI=="http://www.w3.org/XML/1998/namespace"){"XMP RDF属性不支持，未丢弃后导出"}}
            for(i in 0 until container.childNodes.length){val node=container.childNodes.item(i)
                if(node !is Element){require(node.nodeType!=Node.TEXT_NODE && node.nodeType!=Node.CDATA_SECTION_NODE || node.nodeValue.isNullOrBlank()){"XMP RDF文本无效"};continue}
                require(node.namespaceURI==RDF && node.localName=="Description"){"XMP RDF节点不支持，未丢弃后导出"}
                val original=node
                val e=doc.importNode(original,true) as Element
                var parent:Node?=original
                while(parent is Element){for(a in 0 until parent.attributes.length){val attr=parent.attributes.item(a);if(attr.namespaceURI=="http://www.w3.org/2000/xmlns/" && !e.hasAttribute(attr.nodeName))e.setAttributeNS(attr.namespaceURI,attr.nodeName,attr.nodeValue)};parent=parent.parentNode}
                if(scrub)clean(e);rdf.appendChild(e)
            }
        }
        source.forEach{append(it,true)};destination.forEach{append(it,false)}
        val dims=doc.createElementNS(RDF,"rdf:Description");dims.setAttributeNS(RDF,"rdf:about","")
        dims.setAttributeNS(TIFF,"tiff:ImageWidth",w.toString());dims.setAttributeNS(TIFF,"tiff:ImageLength",h.toString());dims.setAttributeNS(TIFF,"tiff:Orientation","1")
        dims.setAttributeNS(EXIF,"exif:PixelXDimension",w.toString());dims.setAttributeNS(EXIF,"exif:PixelYDimension",h.toString());dims.setAttributeNS(EXIF,"exif:ColorSpace",if(outputSrgb)"1"else "65535");rdf.appendChild(dims)
        return serialize(doc)
    }
    /** JPEG XMP is never truncated at 64KiB. Preserve destination gainmap descriptors in the primary packet. */
    fun jpegXmp(xml:ByteArray?,destination:List<ByteArray>,w:Int,h:Int,outputSrgb:Boolean=true):List<ByteArray>{
        if(xml==null)return emptyList();if(xml.size+XMP.size<=65533)return listOf(XMP+xml)
        val guid=MessageDigest.getInstance("MD5").digest(xml).joinToString(""){"%02X".format(it)}
        val reference="<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"$RDF\"><rdf:Description rdf:about=\"\" xmlns:xmpNote=\"$NOTE\" xmpNote:HasExtendedXMP=\"$guid\"/></rdf:RDF></x:xmpmeta>".toByteArray()
        val main=if(destination.isEmpty())reference else mergedXmp(listOf(reference),destination,w,h,outputSrgb)!!.let{b->
            // The primary reference must keep HasExtendedXMP; create it after normalization.
            val doc=parse(b);val e=doc.getElementsByTagNameNS(RDF,"Description").item(0) as Element;e.setAttributeNS(NOTE,"xmpNote:HasExtendedXMP",guid);serialize(doc)
        }
        require(main.size+XMP.size<=65533)
        val parts=ArrayList<ByteArray>();parts.add(XMP+main)
        val capacity=65533-EXT.size-40
        for(at in xml.indices step capacity){val n=minOf(capacity,xml.size-at);parts.add(EXT+guid.toByteArray(Charsets.US_ASCII)+ByteBuffer.allocate(8).putInt(xml.size).putInt(at).array()+xml.copyOfRange(at,at+n))}
        return parts
    }
    /** Keep IPTC/copyright/URL resources active, not Photoshop thumbnails or stale pixel geometry. */
    fun descriptiveIptc(b:ByteArray):ByteArray? {
        val header="Photoshop 3.0\u0000".toByteArray();if(!starts(b,header))return null
        val out=ByteArrayOutputStream();out.write(header);var at=header.size
        while(at<b.size){val begin=at;require(at+7<=b.size && String(b,at,4)=="8BIM"){"Photoshop元数据资源损坏"};at+=4
            val id=(b[at].toInt()and 255)*256+(b[at+1].toInt()and 255);at+=2;val name=(b[at].toInt()and 255)+1;at+=name+name%2
            require(at+4<=b.size);val n=ByteBuffer.wrap(b).getInt(at);at+=4;require(n>=0 && n<=b.size-at);at+=n+n%2;require(at<=b.size)
            if(id in setOf(0x404,0x40a,0x40b))out.write(b,begin,at-begin)
        }
        return out.toByteArray().takeIf{it.size>header.size}
    }
    fun archiveSegments(b:ByteArray):List<ByteArray> {val capacity=60000;val count=(b.size+capacity-1)/capacity;return (0 until count).map{i->ARCHIVE+ByteBuffer.allocate(12).putInt(i).putInt(count).putInt(b.size).array()+b.copyOfRange(i*capacity,minOf(b.size,(i+1)*capacity))}}
    fun readArchive(file:File,cancel:Cancel):ByteArray {
        val bundle=read(file,cancel,MAX*3)
        val plain=bundle.packets.firstOrNull{it.kind=="P:lmEt" || it.kind=="W:LMET"}?.bytes
        val b=plain?:run{val parts=bundle.packets.filter{it.kind=="J:eb" && starts(it.bytes,ARCHIVE)};require(parts.isNotEmpty());val head=ByteBuffer.wrap(parts.first().bytes,ARCHIVE.size,12);head.int;val count=head.int;val total=head.int;require(count==parts.size && total in 1..MAX+65568);val result=ByteArrayOutputStream()
            parts.forEachIndexed{i,p->val h=ByteBuffer.wrap(p.bytes,ARCHIVE.size,12);require(h.int==i && h.int==count && h.int==total);result.write(p.bytes,ARCHIVE.size+12,p.bytes.size-ARCHIVE.size-12)}
            result.toByteArray().also{require(it.size==total)}
        };unarchive(b);return b
    }
}
