package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded classic-TIFF/EXIF editing. Existing value bytes stay at their ORIGINAL TIFF offsets:
 * relocating arbitrary MakerNote blobs breaks vendor-relative/absolute references. New IFDs are appended,
 * old thumbnails/image offsets are not linked into the exported image, and no source HDR/ICC is reused.
 */
class CaptureExif(val bytes:ByteArray) {
    val order:ByteOrder
    private val buffer:ByteBuffer
    data class Entry(val tag:Int,val type:Int,val count:Long,val raw:ByteArray,val value:ByteArray)
    data class Patch(val exifIfd:Boolean,val tag:Int,val type:Int,val value:ByteArray)
    init{
        require(bytes.size in 8..MAX_BYTES){"EXIF长度无效或超过安全上限"}
        order=when(String(bytes,0,2,Charsets.US_ASCII)){"II"->ByteOrder.LITTLE_ENDIAN;"MM"->ByteOrder.BIG_ENDIAN;else->error("无效EXIF字节序")}
        buffer=ByteBuffer.wrap(bytes).order(order)
        require(u16(2)==42){"不支持此EXIF/TIFF版本；未丢弃元数据继续保存"}
    }
    internal class ReferenceBoundsException:IllegalArgumentException("EXIF引用越界")
    private fun bounds(offset:Long,length:Long):Int {if(!(offset>=0 && length>=0 && offset<=bytes.size.toLong()-length))throw ReferenceBoundsException();return offset.toInt()}
    private fun u16(offset:Int)=buffer.getShort(bounds(offset.toLong(),2)).toInt() and 65535
    private fun u32(offset:Int)=buffer.getInt(bounds(offset.toLong(),4)).toLong() and 0xffffffffL
    fun entries(offset:Long):List<Entry> = directoryEntries(offset,true)
    private fun directoryEntries(offset:Long,hasNextImagePointer:Boolean):List<Entry>{
        if(offset==0L)return emptyList()
        // The root/image IFD has a mandatory next-image slot. Exif/GPS/Interop are
        // attribute directories reached by explicit tags; e.g. piexif omits that slot.
        // Do not mistake bytes following their complete entries for an image link.
        val start=bounds(offset,2);val n=u16(start);require(n<=4096){"EXIF字段过多"};bounds(offset+2,12L*n+if(hasNextImagePointer)4 else 0)
        return (0 until n).map{i->
            val pos=start+2+i*12;val type=u16(pos+2);val count=u32(pos+4)
            val length=Math.multiplyExact(count,when(type){1,2,6,7->1;3,8->2;4,9,11,13->4;5,10,12->8;else->error("未知EXIF字段类型$type，未静默丢弃")}.toLong())
            require(length<=MAX_BYTES){"EXIF字段超过安全上限"}
            val value=if(length<=4)pos+8 else bounds(u32(pos+8),length)
            Entry(u16(pos),type,count,bytes.copyOfRange(pos,pos+12),bytes.copyOfRange(value,value+length.toInt()))
        }.also{require(it.map{e->e.tag}.distinct().size==it.size){"EXIF含冲突重复字段"}}
    }
    fun root()=entries(u32(4))
    private fun pointer(entries:List<Entry>,tag:Int):Long=entries.firstOrNull{it.tag==tag}?.let{
        require(it.type in setOf(4,13) && it.count==1L && it.value.size==4){"EXIF子目录指针无效"}
        ByteBuffer.wrap(it.value).order(order).int.toLong() and 0xffffffffL
    }?:0
    fun exif()=directoryEntries(pointer(root(),0x8769),false)
    fun gps()=directoryEntries(pointer(root(),0x8825),false)
    fun value(exifIfd:Boolean,tag:Int)=(if(exifIfd)exif()else root()).firstOrNull{it.tag==tag}?.value
    @JvmOverloads
    fun normalized(width:Int,height:Int,patches:List<Patch> = emptyList(),outputSrgb:Boolean=true,onInvalidInterop:(()->Unit)?=null):ByteArray {
        require(width>0 && height>0)
        val root=root().associateBy{it.tag}.toMutableMap()
        val exif=exif().associateBy{it.tag}.toMutableMap()
        gps() // validate even when keeping its original byte-exact pointer/data
        val out=ByteArrayOutputStream();out.write(bytes)
        fun align(){if(out.size()%2!=0)out.write(0)}
        fun intBytes(value:Int)=ByteBuffer.allocate(4).order(order).putInt(value).array()
        fun changed(tag:Int,type:Int,value:ByteArray):Entry {
            val unit=when(type){1,2,7->1;3->2;4->4;else->error("无效新EXIF字段类型")}
            require(value.size%unit==0)
            val count=value.size/unit;val entry=ByteBuffer.allocate(12).order(order).putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if(value.size<=4)entry.put(value).put(ByteArray(4-value.size)) else{align();entry.putInt(out.size());out.write(value)}
            return Entry(tag,type,count.toLong(),entry.array(),value)
        }
        fun short(tag:Int,n:Int)=changed(tag,3,ByteBuffer.allocate(2).order(order).putShort(n.toShort()).array())
        fun long(tag:Int,n:Int)=changed(tag,4,intBytes(n))
        fun directory(values:Collection<Entry>):Int {
            require(values.size<=4096);align();val pos=out.size();val data=ByteBuffer.allocate(2+12*values.size+4).order(order)
            data.putShort(values.size.toShort());values.sortedBy{it.tag}.forEach{data.put(it.raw)};data.putInt(0);out.write(data.array());return pos
        }
        // Old TIFF compression/strips/tiles/subimages/embedded thumbnail and color-profile references
        // describe source pixels, not our newly encoded pixels. Preserve descriptive capture fields instead.
        val geometry=setOf(0x102,0x103,0x106,0x111,0x115,0x116,0x117,0x11c,0x13d,0x142,0x143,0x144,0x145,0x14a,0x152,0x201,0x202,0x205,0x206,0x207,0x208,0x209,0x211,0x212,0x213,0x214,0x8773)
        geometry.forEach{root.remove(it)}
        root[0x100]=long(0x100,width);root[0x101]=long(0x101,height);root[0x112]=short(0x112,1)
        exif[0xa002]=long(0xa002,width);exif[0xa003]=long(0xa003,height);exif[0xa001]=short(0xa001,if(outputSrgb)1 else 65535)
        val interopPointer=pointer(exif.values.toList(),0xa005)
        val directories=listOf(u32(4),pointer(root.values.toList(),0x8769),pointer(root.values.toList(),0x8825),interopPointer).filter{it!=0L}
        require(directories.distinct().size==directories.size){"EXIF目录循环/别名冲突，未丢弃元数据继续处理"}
        if(interopPointer!=0L){
            // Only manual editing opts in. Keep all bounds checks and all unrelated failures strict.
            // Original bytes remain at their original offsets and in the verified raw archive.
            val parsedInterop=try{directoryEntries(interopPointer,false)}catch(t:ReferenceBoundsException){
                if(onInvalidInterop==null)throw t
                MetadataExportDiagnostics.record(t);onInvalidInterop();null
            }
            if(parsedInterop==null)exif.remove(0xa005) else {
                val interop=parsedInterop.associateBy{it.tag}.toMutableMap()
                if(outputSrgb)interop[1]=changed(1,2,byteArrayOf(82,57,56,0)) else interop.remove(1) // destination ICC remains authoritative
                exif[0xa005]=long(0xa005,directory(interop.values))
            }
        }
        patches.forEach{p->(if(p.exifIfd)exif else root)[p.tag]=changed(p.tag,p.type,p.value)}
        root[0x8769]=long(0x8769,directory(exif.values))
        val position=directory(root.values)
        require(out.size()<=MAX_BYTES){"元数据过大，原片保留；请检查源EXIF"}
        val result=out.toByteArray();ByteBuffer.wrap(result).order(order).putInt(4,position)
        val parsed=CaptureExif(result);parsed.root();parsed.exif();parsed.gps()
        // Keep ordinary exports byte-identical. Only reclaim space when the active EXIF
        // would not fit a JPEG APP1 (6-byte Exif signature + classic TIFF <= 65533).
        if(result.size>65527)inactiveThumbnailPrefix()?.let{prefix->
            return CaptureExif(prefix).normalized(width,height,patches,outputSrgb,onInvalidInterop)
        }
        return result
    }
    /** Only a single, structurally complete JPEG thumbnail occupying the EXACT TIFF tail.
     * Keep the old IFD and every declared value at its original offset; remove image bytes,
     * never compact/rebase arbitrary metadata. Unknown layouts fall back to the old strict
     * capacity failure. The complete original packet (including thumbnail) is archived by
     * writePreserved; this does not claim to interpret vendor-private MakerNote semantics.
     */
    private fun inactiveThumbnailPrefix():ByteArray? {
        try {
            val rootAt=u32(4);if(rootAt==0L)return null
            val roots=root();val link=bounds(rootAt+2+12L*roots.size,4)
            val thumbAt=u32(link);if(thumbAt==0L)return null
            val thumb=directoryEntries(thumbAt,true)
            val thumbEnd=thumbAt+2+12L*thumb.size+4
            if(u32(bounds(thumbEnd-4,4))!=0L)return null // no additional image chain
            val allowed=setOf(0x103,0x112,0x11a,0x11b,0x128,0x201,0x202,0x213)
            if(thumb.any{it.tag !in allowed})return null
            fun number(tag:Int,type:Int):Long?=thumb.singleOrNull{it.tag==tag}?.let{
                if(it.type!=type || it.count!=1L)null else if(type==3)
                    ByteBuffer.wrap(it.value).order(order).short.toLong() and 65535L
                else ByteBuffer.wrap(it.value).order(order).int.toLong() and 0xffffffffL
            }
            if(number(0x103,3)!=6L)return null
            val at=number(0x201,4)?:return null;val length=number(0x202,4)?:return null
            if(at<8 || length<4 || at+length!=bytes.size.toLong() || thumbEnd>at)return null
            bounds(at,length)
            // Framing only, not entropy decoding; no pixel copy and no filesystem access.
            val end=java.io.ByteArrayInputStream(bytes,at.toInt(),length.toInt()).use{
                JpegCompletion.primaryEnd(it,length,Cancel())
            }
            // Some cameras reserve a fixed thumbnail allocation and zero-fill the bytes
            // after its real EOI. Accept ONLY all-zero slack inside that declared allocation;
            // a second JPEG, private trailer, or any nonzero byte remains unreclaimable.
            if(end==null || end>length)return null
            for(i in (at+end).toInt() until bytes.size)if(bytes[i]!=0.toByte())return null
            val exifs=exif();val gpsAt=pointer(roots,0x8825);val exifAt=pointer(roots,0x8769)
            val interopAt=pointer(exifs,0xa005)
            val dirs=listOf(Triple(rootAt,roots,true),Triple(exifAt,exifs,false),
                Triple(gpsAt,gps(),false),Triple(interopAt,directoryEntries(interopAt,false),false),
                Triple(thumbAt,thumb,true)).filter{it.first!=0L}
            if(dirs.map{it.first}.distinct().size!=dirs.size)return null
            val spans=dirs.map{(offset,entries,next)->offset to offset+2+12L*entries.size+if(next)4 else 0}
            if(spans.any{it.first<8 || it.second>at})return null
            for(i in spans.indices)for(j in 0 until i)
                if(spans[i].first<spans[j].second && spans[j].first<spans[i].second)return null
            for((offset,entries,_)in dirs)for(e in entries){
                // We do not follow other directory/pixel-pointer namespaces during reclamation.
                val knownPointer=(offset==rootAt && e.tag in setOf(0x8769,0x8825)) || (offset==exifAt && e.tag==0xa005)
                if((e.type==13 && !knownPointer) || e.tag in setOf(0x14a,0x111,0x144,0x145,0x120,0x121,0x14d))return null
                if(e.value.size>4){
                    val valueAt=ByteBuffer.wrap(e.raw).order(order).getInt(8).toLong() and 0xffffffffL
                    if(valueAt<8 || valueAt+e.value.size>at)return null // includes unknown fields/MakerNote/GPS
                    if(valueAt<link+4L && link<valueAt+e.value.size)return null // clearing the old link must not alter a shared value
                }
                // An unknown LONG can itself be a private TIFF-relative reference even when
                // its four bytes are inline. Do not reclaim a tail it may address. Standard
                // image dimensions/sensitivity are scalars, not references.
                val scalar=(offset==rootAt && e.tag in setOf(0x100,0x101,0x116,0x117)) ||
                    (offset==exifAt && e.tag in setOf(0xa002,0xa003,0x8831,0x8832,0x8833,0x8834,0x8835)) ||
                    (offset==thumbAt && e.tag in setOf(0x201,0x202))
                if(e.type==4 && !knownPointer && !scalar){
                    val values=ByteBuffer.wrap(e.value).order(order)
                    while(values.remaining()>=4){val n=values.int.toLong() and 0xffffffffL;if(n in at until bytes.size.toLong())return null}
                }
            }
            return bytes.copyOf(at.toInt()).also{ByteBuffer.wrap(it).order(order).putInt(link,0)}
        }catch(_:IllegalArgumentException){return null}catch(_:IllegalStateException){return null}
    }
    companion object {
        const val MAX_BYTES=1024*1024
        fun empty()=CaptureExif(byteArrayOf(77,77,0,42,0,0,0,0))
    }
}
