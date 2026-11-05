package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.xml.sax.SAXException

/** Optional source descriptions must not veto an otherwise encodable photo. Strict readers and
 * publication checks remain strict; recovery is explicit and never earns original-deletion approval. */
internal object ExportMetadataCompatibility {
    data class Prepared(val original:PreservedMetadata.Bundle,val active:PreservedMetadata.Bundle,
                        val exif:ByteArray,val degraded:Boolean)
    data class Written(val degraded:Boolean)
    /** Only source-metadata syntax failures are recoverable. Never swallow I/O, cancellation or VM errors. */
    internal inline fun <T> optional(block:()->T):T?=try{block()}
        catch(e:CancellationException){throw e}
        catch(e:IllegalArgumentException){nonOptionalCause(e);MetadataExportDiagnostics.record(e);null}
        catch(e:IllegalStateException){nonOptionalCause(e);MetadataExportDiagnostics.record(e);null}
        catch(e:SAXException){nonOptionalCause(e);MetadataExportDiagnostics.record(e);null}
        catch(e:java.util.zip.ZipException){nonOptionalCause(e);MetadataExportDiagnostics.record(e);null}
    internal fun nonOptionalCause(exception:Exception){
        propagateFatalCause(exception)
        var cause=exception.cause
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        while(cause!=null && seen.add(cause)){
            if(cause is CancellationException)throw cause
            if(cause is java.io.IOException)throw cause
            if(cause is SecurityException)throw cause
            cause=cause.cause
        }
    }
    fun prepare(source:File,width:Int,height:Int,badge:Boolean,cancel:Cancel,outputSrgb:Boolean=true):Prepared {
        cancel.check()
        // Opening a missing/unreadable original is a real I/O failure, not an optional EXIF field.
        val read=optional{PreservedMetadata.read(source,cancel)}
        return prepare(read?:recoverPackets(source,cancel),width,height,badge,cancel,outputSrgb,read==null)
    }
    fun prepare(original:PreservedMetadata.Bundle,width:Int,height:Int,badge:Boolean,cancel:Cancel,
                outputSrgb:Boolean=true,alreadyDegraded:Boolean=false):Prepared {
        require(width>0 && height>0);cancel.check()
        var degraded=alreadyDegraded
        var raw=original
        if(optional{PreservedMetadata.archive(raw)}==null){
            degraded=true;var total=0
            raw=raw.copy(packets=raw.packets.take(2048).filter{p->
                val fits=p.bytes.size<=PreservedMetadata.MAX-65536-total
                if(fits)total+=p.bytes.size;fits
            })
        }
        fun normalized(e:CaptureExif):ByteArray?=optional{
            e.normalized(width,height,XiaomiLeicaBadge.patches(e,badge),outputSrgb,onInvalidInterop={degraded=true})
                .also{require(it.size<=65527){"拍摄EXIF超过JPEG APP1容量，尝试兼容保留"}}
        }
        var source=optional{raw.exif?.let(::CaptureExif)?:CaptureExif.empty()}
        var exif=source?.let(::normalized)
        if(exif==null){
            degraded=true
            val recovery=raw.exif?.let{Recovery(it,cancel)}
            source=recovery?.rebuild(false)?.let{optional{CaptureExif(it)}}
            exif=source?.let(::normalized)
            if(exif==null){source=recovery?.rebuild(true)?.let{optional{CaptureExif(it)}};exif=source?.let(::normalized)}
            if(exif==null){source=CaptureExif.empty();exif=checkNotNull(normalized(source))}
        }
        val xmp=raw.xmp.filter{b->
            cancel.check();val valid=optional{PreservedMetadata.mergedXmp(listOf(b),emptyList(),width,height,outputSrgb)}!=null
            if(!valid)degraded=true;valid
        }
        val packets=raw.packets.filter{p->
            cancel.check()
            val valid=p.kind!="J:ed" || optional{PreservedMetadata.descriptiveIptc(p.bytes);true}==true
            if(!valid)degraded=true;valid
        }
        // Raw packets, including bad fields, stay in the bounded archive whenever they were readable.
        return Prepared(raw,raw.copy(packets=packets,exif=checkNotNull(source).bytes,xmp=xmp),checkNotNull(exif),degraded)
    }
    fun write(encoded:File,target:File,prepared:Prepared,width:Int,height:Int,badge:Boolean,cancel:Cancel,
              outputSrgb:Boolean=true):Written {
        cancel.check();check(!target.exists()){"导出目标已存在，未覆盖"}
        var complete=false
        try{
        // Destination ICC/HDR/container data are NOT optional source descriptions.
        val destination=PreservedMetadata.read(encoded,cancel)
        var active=prepared.active;var degraded=prepared.degraded
        if(optional{PreservedMetadata.mergedXmp(active.xmp,destination.xmp,width,height,outputSrgb);true}!=true){
            active=active.copy(xmp=emptyList());degraded=true
        }
        CaptureMetadataContainer.writePreserved(encoded,target,prepared.original,width,height,badge,cancel,outputSrgb,
            activeMetadata=active,activeExif=prepared.exif)
        cancel.check();complete=true;return Written(degraded)
        }finally{if(!complete)target.delete()}
    }
    private fun recoverPackets(source:File,cancel:Cancel):PreservedMetadata.Bundle {
        val packets=ArrayList<PreservedMetadata.Packet>();var total=0;var format="UNKNOWN"
        fun add(kind:String,b:ByteArray){if(packets.size<2048 && b.size<=PreservedMetadata.MAX-65536-total){packets.add(PreservedMetadata.Packet(kind,b));total+=b.size}}
        optional{RandomAccessFile(source,"r").use{f->
            val head=ByteArray(minOf(12L,f.length()).toInt());f.readFully(head)
            when{
                PreservedMetadata.starts(head,byteArrayOf(-1,-40))->{
                    format="JPEG";for(s in CaptureMetadataContainer.jpegHeaders(f)){cancel.check();if(s.marker in 0xe0..0xef || s.marker==0xfe)add("J:${s.marker.toString(16)}",s.payload)}
                }
                PreservedMetadata.starts(head,byteArrayOf(-119,80,78,71,13,10,26,10)) ||
                    (head.size>=12 && String(head,0,4)=="RIFF" && String(head,8,4)=="WEBP")->{
                    val png=head[0]==(-119).toByte();format=if(png)"PNG"else "WEBP"
                    for(c in CaptureMetadataContainer.chunks(f,png)){
                        cancel.check()
                        val metadata=if(png)c.kind[0].isLowerCase() && c.kind !in setOf("acTL","fcTL","fdAT")
                            else c.kind !in setOf("VP8X","VP8 ","VP8L","ALPH","ANIM","ANMF")
                        if(metadata && c.size<=PreservedMetadata.MAX-65536-total){
                            val b=ByteArray(c.size.toInt());f.seek(c.offset+8);f.readFully(b);add((if(png)"P:"else "W:")+c.kind,b)
                        }
                    }
                }
            }
        }}
        val signature=byteArrayOf(69,120,105,102,0,0)
        val exifs=packets.mapNotNull{p->when{
            p.kind=="J:e1" && PreservedMetadata.starts(p.bytes,signature)->p.bytes.copyOfRange(6,p.bytes.size)
            p.kind=="P:eXIf"->p.bytes
            p.kind=="W:EXIF"->if(PreservedMetadata.starts(p.bytes,signature))p.bytes.copyOfRange(6,p.bytes.size)else p.bytes
            else->null
        }}
        // Deterministic best readable directory, not the largest padding/thumbnail packet.
        val selected=exifs.maxByOrNull{Recovery(it,cancel).score()}
        val xmp=packets.mapNotNull{p->when{
            p.kind=="J:e1" && PreservedMetadata.starts(p.bytes,PreservedMetadata.XMP)->p.bytes.copyOfRange(PreservedMetadata.XMP.size,p.bytes.size)
            p.kind=="W:XMP "->p.bytes
            else->null
        }}
        cancel.check();return PreservedMetadata.Bundle(packets,selected,xmp,format)
    }
    /** Invalid fields are disconnected, never read past bounds. Valid value bytes/MakerNote offsets
     * remain where they were. Compact fallback only relocates standard descriptive fields. */
    private class Recovery(private val bytes:ByteArray,private val cancel:Cancel){
        private val valid=bytes.size in 8..CaptureExif.MAX_BYTES &&
            (String(bytes,0,2)=="II" || String(bytes,0,2)=="MM")
        private val order=if(valid && bytes[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        private val b=ByteBuffer.wrap(bytes).order(order)
        private val seen=HashSet<Long>()
        private fun range(at:Long,n:Long)=at>=8 && n>=0 && at<=bytes.size.toLong()-n
        private fun u16(at:Int)=b.getShort(at).toInt() and 65535
        private fun u32(at:Int)=b.getInt(at).toLong() and 0xffffffffL
        private fun directory(at:Long):List<CaptureExif.Entry>{
            if(!range(at,2) || !seen.add(at))return emptyList()
            val count=minOf(u16(at.toInt()),4096,((bytes.size-at-2)/12).toInt())
            val entries=LinkedHashMap<Int,CaptureExif.Entry>()
            for(i in 0 until count){
                cancel.check();val pos=(at+2+12L*i).toInt();val tag=u16(pos);val type=u16(pos+2);val n=u32(pos+4)
                val unit=when(type){1,2,6,7->1;3,8->2;4,9,11,13->4;5,10,12->8;else->continue}
                val size=n*unit;if(size>CaptureExif.MAX_BYTES)continue
                val valueAt=if(size<=4)pos+8L else u32(pos+8)
                if(!range(valueAt,size))continue
                if(tag==XiaomiLeicaBadge.TAG && (type!=1 || n!=1L))continue
                val selected=entries[tag]
                if(selected==null)entries[tag]=CaptureExif.Entry(tag,type,n,bytes.copyOfRange(pos,pos+12),bytes.copyOfRange(valueAt.toInt(),(valueAt+size).toInt()))
            }
            return entries.values.toList()
        }
        private fun pointer(entries:List<CaptureExif.Entry>,tag:Int)=entries.firstOrNull{it.tag==tag && it.type in setOf(4,13) && it.count==1L}?.let{ByteBuffer.wrap(it.value).order(order).int.toLong() and 0xffffffffL}?:0
        private fun parsed():List<List<CaptureExif.Entry>> {
            seen.clear();if(!valid || u16(2)!=42)return List(4){emptyList()}
            val root=directory(u32(4));val exif=directory(pointer(root,0x8769));val gps=directory(pointer(root,0x8825));val interop=directory(pointer(exif,0xa005))
            return listOf(root,exif,gps,interop)
        }
        fun score()=parsed().sumOf{it.size}
        fun rebuild(compact:Boolean):ByteArray?=optional{
            if(!valid || u16(2)!=42)return@optional null
            val (r,e,g,i)=parsed();val out=ByteArrayOutputStream()
            out.write(if(compact)byteArrayOf(bytes[0],bytes[1],0,0,0,0,0,0).also{ByteBuffer.wrap(it).order(order).putShort(2,42)}else bytes)
            fun align(){if(out.size()%2!=0)out.write(0)}
            fun entry(old:CaptureExif.Entry):ByteArray? {
                if(!compact)return old.raw
                if(out.size()+old.value.size>58000)return null
                val raw=ByteBuffer.allocate(12).order(order).putShort(old.tag.toShort()).putShort(old.type.toShort()).putInt(old.count.toInt())
                if(old.value.size<=4)raw.put(old.value).put(ByteArray(4-old.value.size))else{align();raw.putInt(out.size());out.write(old.value)}
                return raw.array()
            }
            fun link(tag:Int,at:Int)=ByteBuffer.allocate(12).order(order).putShort(tag.toShort()).putShort(4).putInt(1).putInt(at).array()
            fun dir(entries:List<CaptureExif.Entry>,links:List<ByteArray> = emptyList()):Int {
                val raw=entries.mapNotNull(::entry)+links;align();val at=out.size()
                val data=ByteBuffer.allocate(2+12*raw.size+4).order(order).putShort(raw.size.toShort());raw.forEach{data.put(it)};data.putInt(0);out.write(data.array());return at
            }
            val rootTags=setOf(0x10d,0x10e,0x10f,0x110,0x11a,0x11b,0x128,0x131,0x132,0x13b,0x13c,0x8298)
            val exifTags=setOf(0x829a,0x829d,0x8822,0x8827,0x8830,0x8831,0x8832,0x8833,0x8834,0x8835,0x9000,0x9003,0x9004,0x9010,0x9011,0x9012,0x9101,0x9102,0x9201,0x9202,0x9203,0x9204,0x9205,0x9206,0x9207,0x9208,0x9209,0x920a,0x9290,0x9291,0x9292,0xa000,0xa001,0xa004,0xa20b,0xa20e,0xa20f,0xa210,0xa214,0xa215,0xa217,0xa300,0xa301,0xa401,0xa402,0xa403,0xa404,0xa405,0xa406,0xa407,0xa408,0xa409,0xa40a,0xa40c,0xa420,0xa430,0xa431,0xa432,0xa433,0xa434,0xa435)
            val exif=e.filter{it.tag!=0xa005 && (!compact || it.tag in exifTags)}
            val gps=g.filter{!compact || it.tag in 0..31}
            val interop=if(compact)emptyList()else i
            val interopAt=if(interop.isEmpty())null else dir(interop)
            val exifAt=dir(exif,interopAt?.let{listOf(link(0xa005,it))}?:emptyList())
            val gpsAt=if(gps.isEmpty())null else dir(gps)
            val roots=r.filter{it.tag !in setOf(0x8769,0x8825) && (!compact || it.tag in rootTags)}
            val rootAt=dir(roots,listOf(link(0x8769,exifAt))+(gpsAt?.let{listOf(link(0x8825,it))}?:emptyList()))
            require(out.size()<=CaptureExif.MAX_BYTES)
            out.toByteArray().also{ByteBuffer.wrap(it).order(order).putInt(4,rootAt)}
        }
    }
}
