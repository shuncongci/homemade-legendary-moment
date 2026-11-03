package xyz.shuncongci.leica

import java.io.*
import java.nio.ByteBuffer
import org.w3c.dom.Element
import org.xml.sax.SAXException
import javax.xml.parsers.DocumentBuilderFactory

/** Compatibility for this app's immutable, previously encoded SDR-base RGB gain JPEGs.
 * Only the known scalar Adobe profile is translated. Unknown/platform profiles and existing
 * ISO metadata are left alone, never guessed or overwritten. Pixels/ICC/EXIF/archives are copied.
 * This is not a display fix, a new render, or an HDR exposure operation.
 */
internal object HdrJpegCompatibility {
    private val iso="urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.US_ASCII)
    private const val GM="http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val ITEM="http://ns.google.com/photos/1.0/container/item/"
    private const val CONTAINER="http://ns.google.com/photos/1.0/container/"
    private const val RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private data class Header(val at:Long,val marker:Int,val payload:ByteArray){val total get()=4+payload.size}
    private data class Edit(val at:Long,val removed:Int,val bytes:ByteArray)
    private data class Plan(val length:Long,val edits:List<Edit>)
    private fun ByteArray.starts(prefix:ByteArray)=PreservedMetadata.starts(this,prefix)
    private fun packet(marker:Int,payload:ByteArray):ByteArray {
        require(payload.size<=65533)
        val out=ByteArrayOutputStream();DataOutputStream(out).use{it.writeByte(255);it.writeByte(marker);it.writeShort(payload.size+2);it.write(payload)}
        return out.toByteArray()
    }
    /** Bounded header reader for a JPEG at a nonzero container offset. Fill stays in the copy. */
    private fun headers(f:RandomAccessFile,start:Long,end:Long,cancel:Cancel):List<Header> {
        require(start>=0 && end<=f.length() && end-start>=4)
        f.seek(start);require(f.readUnsignedShort()==0xffd8)
        val result=ArrayList<Header>();var at=start+2
        while(true){
            cancel.check();require(at-start<=12L*CaptureExif.MAX_BYTES && at<=end-4)
            f.seek(at);require(f.readUnsignedByte()==255);var m=f.readUnsignedByte()
            while(m==255){at++;require(at-start<=12L*CaptureExif.MAX_BYTES && at<=end-4);if(at and 8191L==0L)cancel.check();m=f.readUnsignedByte()}
            require(m!=0 && m!=0xd9 && m !in 0xd0..0xd8 && m!=1)
            val n=f.readUnsignedShort();require(n>=2 && at+2+n<=end && at+2+n-start<=12L*CaptureExif.MAX_BYTES)
            if(m==0xda)return result
            require(result.size<2048);val p=ByteArray(n-2);f.readFully(p);result.add(Header(at,m,p));at+=n+2
        }
    }
    private fun xml(h:Header):org.w3c.dom.Document {
        require(h.marker==0xe1 && h.payload.starts(PreservedMetadata.XMP))
        val bytes=h.payload.copyOfRange(PreservedMetadata.XMP.size,h.payload.size)
        val text=String(bytes,Charsets.UTF_8)
        require('\u0000' !in text && text.toByteArray(Charsets.UTF_8).contentEquals(bytes) && !text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true))
        val factory=DocumentBuilderFactory.newInstance();factory.isNamespaceAware=true;factory.isExpandEntityReferences=false
        val builder=factory.newDocumentBuilder();builder.setEntityResolver{_,_->throw IllegalArgumentException("XMP禁止外部访问")}
        builder.setErrorHandler(object:org.xml.sax.helpers.DefaultHandler(){override fun error(e:org.xml.sax.SAXParseException){throw e};override fun fatalError(e:org.xml.sax.SAXParseException){throw e}})
        return builder.parse(ByteArrayInputStream(bytes))
    }
    private fun xmp(headers:List<Header>)=requireNotNull(headers.filter{it.marker==0xe1 && it.payload.starts(PreservedMetadata.XMP)}.singleOrNull())
    private fun scalarGain(h:Header):ByteArray {
        val doc=xml(h);val descriptions=doc.getElementsByTagNameNS(RDF,"Description");require(descriptions.length==1)
        val d=descriptions.item(0) as Element
        val names=setOf("Version","GainMapMin","GainMapMax","Gamma","OffsetSDR","OffsetHDR","HDRCapacityMin","HDRCapacityMax","BaseRenditionIsHDR")
        val found=HashSet<String>()
        for(i in 0 until d.attributes.length){val a=d.attributes.item(i)
            when(a.namespaceURI){GM->{require(a.localName in names);found.add(a.localName)};"http://www.w3.org/2000/xmlns/",RDF->{};else->require(false){"未知增益图属性"}}
        }
        require(found==names && (0 until d.childNodes.length).all{val n=d.childNodes.item(it);n.nodeType==org.w3c.dom.Node.TEXT_NODE && n.textContent.isBlank()})
        fun field(n:String)=d.getAttributeNS(GM,n)
        require(field("Version")=="1.0" && field("BaseRenditionIsHDR")=="False" && field("Gamma").toDouble()==1.0 &&
            field("OffsetSDR").toDouble()==HdrMath.EPSILON.toDouble() && field("OffsetHDR").toDouble()==HdrMath.EPSILON.toDouble() && field("HDRCapacityMin").toDouble()==0.0)
        return HdrIsoMetadata.gainLog2(field("GainMapMin").toDouble(),field("GainMapMax").toDouble(),field("HDRCapacityMax").toDouble())
    }
    private fun directory(h:Header,gainLength:Long,newLength:Long):ByteArray {
        val doc=xml(h);require(doc.getElementsByTagNameNS(CONTAINER,"Directory").length==1)
        val all=doc.getElementsByTagName("*");var versions=0
        for(i in 0 until all.length){val e=all.item(i) as Element;require(e.namespaceURI!=GM)
            for(j in 0 until e.attributes.length){val a=e.attributes.item(j);if(a.namespaceURI==GM){require(a.localName=="Version" && a.nodeValue=="1.0");versions++}}
        };require(versions==1)
        val items=doc.getElementsByTagNameNS(CONTAINER,"Item");require(items.length==2)
        val primary=items.item(0) as Element;val gain=items.item(1) as Element
        require(primary.getAttributeNS(ITEM,"Semantic")=="Primary" && primary.getAttributeNS(ITEM,"Mime")=="image/jpeg" &&
            gain.getAttributeNS(ITEM,"Semantic")=="GainMap" && gain.getAttributeNS(ITEM,"Mime")=="image/jpeg" && gain.getAttributeNS(ITEM,"Length").toLong()==gainLength)
        require(doc.getElementsByTagNameNS(GM,"GainMapMin").length==0)
        // Only change the unique writer-profile decimal attribute; all other XMP bytes stay exact.
        val text=String(h.payload,Charsets.UTF_8);val pattern=Regex("Item:Length=([\"'])([0-9]+)\\1")
        val match=requireNotNull(pattern.findAll(text).toList().singleOrNull());require(match.groupValues[2].toLong()==gainLength)
        return packet(0xe1,text.replaceRange(match.groups[2]!!.range,newLength.toString()).toByteArray(Charsets.UTF_8))
    }
    private fun inspect(f:RandomAccessFile,source:File,cancel:Cancel):Plan? {
        val length=f.length();val main=headers(f,0,length,cancel)
        val s=main.singleOrNull{it.marker==0xe2 && it.payload.starts(byteArrayOf(77,80,70,0))}?:return null
        val ex=CaptureExif(s.payload.copyOfRange(4,s.payload.size));val root=ex.root()
        val count=requireNotNull(root.singleOrNull{it.tag==0xb001});require(count.type==4 && count.value.size==4 && ByteBuffer.wrap(count.value).order(ex.order).int==2)
        val entry=requireNotNull(root.singleOrNull{it.tag==0xb002});require(entry.type==7 && entry.value.size==32)
        val index=ByteBuffer.wrap(entry.value).order(ex.order)
        fun uint(at:Int)=index.getInt(at).toLong() and 0xffffffffL
        val primary=uint(4);val gainLength=uint(20)
        require(uint(8)==0L && primary>=s.at+s.total && gainLength>=4 && primary==s.at+8+uint(24) && primary+gainLength==length)
        val gain=headers(f,primary,length,cancel)
        // Never add a competing ISO interpretation, including unknown/newer ISO versions.
        if(gain.any{it.marker==0xe2 && it.payload.starts(iso)})return null
        val primaryIso=main.filter{it.marker==0xe2 && it.payload.starts(iso)}
        require(primaryIso.size<=1 && primaryIso.all{it.payload.contentEquals(HdrIsoMetadata.primary())})
        // Caller is the app's generated-result cache, not an arbitrary imported source. Its known
        // Adobe scalar profile describes sRGB, unit gamma and identical 1/64 offsets.
        val gainAdded=packet(0xe2,scalarGain(xmp(gain)))
        val mainAdded=if(primaryIso.isEmpty())packet(0xe2,HdrIsoMetadata.primary())else ByteArray(0)
        val x=xmp(main);val newXmp=directory(x,gainLength,gainLength+gainAdded.size)
        val delta=mainAdded.size+newXmp.size-x.total
        val newPrimary=primary+delta
        val anchorDelta=mainAdded.size+if(x.at<s.at)newXmp.size-x.total else 0
        val relative=uint(24)+delta-anchorDelta
        require(newPrimary in 1..Int.MAX_VALUE.toLong() && relative in 1..Int.MAX_VALUE.toLong() && gainLength+gainAdded.size<=Int.MAX_VALUE)
        // Container framing only, not entropy decoding. Reject bad indices, extra trailing data,
        // or truncated primary/gain JPEG before making any compatibility copy.
        require(JpegCompletion.primaryEnd(source,cancel)==primary)
        source.inputStream().buffered(65536).use{input->
            var left=primary;while(left>0){cancel.check();val n=input.skip(left);require(n>0);left-=n}
            require(JpegCompletion.primaryEnd(input,gainLength,cancel)==gainLength)
        }
        val payload=s.payload.copyOf();val table=ByteBuffer.wrap(entry.raw).order(ex.order).getInt(8)+4
        require(table>=12 && table<=payload.size-32)
        ByteBuffer.wrap(payload).order(ex.order).apply{putInt(table+4,newPrimary.toInt());putInt(table+20,(gainLength+gainAdded.size).toInt());putInt(table+24,relative.toInt())}
        return Plan(length,listOf(Edit(2,0,mainAdded),Edit(x.at,x.total,newXmp),Edit(s.at,s.total,packet(0xe2,payload)),Edit(primary+2,0,gainAdded)).sortedBy{it.at})
    }
    /** [use] sees either the original immutable file or a scoped private copy. No public side effect
     * occurs until the caller's existing ExportCommit, and every failure/cancellation cleans the copy. */
    fun <T> withCompatible(source:File,directory:File,cancel:Cancel,use:(File)->T):T {
        cancel.check()
        val plan=RandomAccessFile(source,"r").use{f->
            try{inspect(f,source,cancel)}catch(_:IllegalArgumentException){null}catch(_:SAXException){null}
        }
        if(plan==null){cancel.check();return use(source)}
        val temporary=File.createTempFile("hdr-compat-",".jpg",directory)
        try {
            RandomAccessFile(source,"r").use{input->FileOutputStream(temporary).use{raw->
                check(input.length()==plan.length){"HDR缓存长度改变"}
                val out=BufferedOutputStream(raw,65536);val buffer=ByteArray(65536);var cursor=0L
                fun copy(to:Long){require(to>=cursor);input.seek(cursor);var left=to-cursor
                    while(left>0){cancel.check();val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0){"HDR缓存截断"};out.write(buffer,0,n);left-=n};cursor=to}
                for(e in plan.edits){copy(e.at);out.write(e.bytes);cursor+=e.removed}
                copy(plan.length);check(input.length()==plan.length){"HDR缓存长度改变"};cancel.check();out.flush();raw.fd.sync()
            }}
            cancel.check();return use(temporary)
        }finally{temporary.delete()}
    }
}
