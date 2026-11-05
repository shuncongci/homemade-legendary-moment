package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Synthetic policy/headers/NAL tests, not real Dolby RPU, Android or device tests. */
internal object VideoDolbyFixture {
    private class Bits {
        private val b=StringBuilder()
        fun n(x:Int,k:Int){for(i in k-1 downTo 0)b.append(if(x ushr i and 1==1)'1' else '0')}
        fun ue(x:Int){val v=Integer.toBinaryString(x+1);b.append("0".repeat(v.length-1));b.append(v)}
        fun bytes():ByteArray {b.append('1');while(b.length%8!=0)b.append('0');val out=java.io.ByteArrayOutputStream();var z=0;for(v in b.toString().chunked(8)){val x=v.toInt(2);if(z>=2 && x<=3){out.write(3);z=0};out.write(x);z=if(x==0)z+1 else 0};return out.toByteArray()}
    }
    fun nal(t:Int,payload:ByteArray=byteArrayOf(0x55),layer:Int=0)=byteArrayOf(((t shl 1) or (layer ushr 5)).toByte(),(((layer and 31) shl 3) or 1).toByte())+payload
    fun framed(vararg n:ByteArray,annex:Boolean=true,short:Boolean=false)=n.flatMap{((if(annex)if(short)byteArrayOf(0,0,1)else byteArrayOf(0,0,0,1)else ByteBuffer.allocate(4).putInt(it.size).array())+it).asIterable()}.toByteArray()
    fun csd(transfer:Int=16,depth:Int=10,matrix:Int=9,full:Boolean=false,tagged:Boolean=true,main:Int=2,layers:Int=0):ByteArray {
        val s=Bits();s.n(0,4);s.n(0,3);s.n(1,1);s.n(main,8);repeat(11){s.n(0,8)}
        s.ue(0);s.ue(1);s.ue(32);s.ue(32);s.n(0,1);s.ue(depth-8);s.ue(depth-8);s.ue(4);s.n(0,1);repeat(3){s.ue(0)};repeat(6){s.ue(0)};s.n(0,1);s.n(0,2);s.n(0,1);s.ue(0);s.n(0,1);s.n(0,2);s.n(if(tagged)1 else 0,1)
        if(tagged){s.n(0,1);s.n(0,1);s.n(1,1);s.n(5,3);s.n(if(full)1 else 0,1);s.n(1,1);s.n(9,8);s.n(transfer,8);s.n(matrix,8);s.n(0,1);s.n(0,1);s.n(0,1);s.n(0,1);s.n(0,1)}
        val v=Bits();v.n(0,4);v.n(1,1);v.n(1,1);v.n(layers,6)
        return framed(nal(32,v.bytes()),nal(33,s.bytes()),nal(34))
    }
    fun cfg(profile:Int=8,id:Int=1,el:Boolean=false,rpu:Boolean=true,bl:Boolean=true,level:Int=6)=ByteArray(24).apply{this[0]=1;this[2]=((profile shl 1) or (level ushr 5)).toByte();this[3]=(((level and 31) shl 3) or (if(rpu)4 else 0) or (if(el)2 else 0) or (if(bl)1 else 0)).toByte();this[4]=(id shl 4).toByte()}
    fun hvcc(csd:ByteArray=csd()):ByteArray {
        val units=VideoCodecConfig.annex(csd);val head=ByteArray(23).apply{this[0]=1;this[1]=2;this[16]=1;this[17]=2;this[18]=2;this[21]=3;this[22]=units.size.toByte()}
        return head+VideoIso.bytes{for(n in units){writeByte((n[0].toInt() ushr 1) and 63);writeShort(1);writeShort(n.size);write(n)}}
    }
    fun entry(type:String="hvc1",config:ByteArray=cfg(),csd:ByteArray=csd(),extra:ByteArray=byteArrayOf(),color:Int?=null):VideoIso.Atom {
        val head=ByteArray(78);ByteBuffer.wrap(head).putShort(6,1).putShort(24,32).putShort(26,32)
        val colr=if(color==null)byteArrayOf()else VideoIso.atom("colr",VideoIso.bytes{writeBytes("nclx");writeShort(9);writeShort(color);writeShort(9);writeByte(0)}).raw
        return VideoIso.atom(type,head+VideoIso.atom("hvcC",hvcc(csd)).raw+VideoIso.atom("dvvC",config).raw+colr+extra)
    }
    fun track(entry:VideoIso.Atom=entry(),id:Int=1,ref:Boolean=false):VideoIso.Atom {
        val tk=ByteArray(84);ByteBuffer.wrap(tk).putInt(12,id).putInt(40,65536).putInt(56,65536).putInt(72,0x40000000)
        val h=ByteArray(24);"vide".toByteArray().copyInto(h,8)
        val sd=VideoIso.atom("stsd",VideoIso.bytes{writeInt(0);writeInt(1);write(entry.raw)})
        val dref=VideoIso.atom("dref",VideoIso.bytes{writeInt(0);writeInt(1);write(VideoIso.atom("url ",VideoIso.bytes{writeInt(1)}).raw)})
        val minf=VideoIso.atom("minf",VideoIso.atom("dinf",dref.raw).raw+VideoIso.atom("stbl",sd.raw).raw)
        return VideoIso.atom("trak",VideoIso.atom("tkhd",tk).raw+VideoIso.atom("mdia",VideoIso.atom("hdlr",h).raw+minf.raw).raw+(if(ref)VideoIso.atom("tref",VideoIso.atom("vdep",VideoIso.bytes{writeInt(2)}).raw).raw else byteArrayOf()))
    }
    fun file(entry:VideoIso.Atom=entry(),brand:String="isom",action:(File)->Unit){val d=Files.createTempDirectory("dolby-synthetic").toFile();try{val f=File(d,"fixture.mp4");f.writeBytes(VideoIso.atom("ftyp",VideoIso.bytes{writeBytes(brand);writeInt(0);writeBytes("isom")}).raw+VideoIso.atom("moov",track(entry).raw).raw);action(f)}finally{d.deleteRecursively()}}
    fun inventory(config:ByteArray=cfg(),csd:ByteArray=csd(),alias:Boolean=true):VideoTracks.Inventory {
        val base=VideoDolby.Base(VideoDolby.config(config),csd)
        val d=VideoTracks.Track(0,1,"video/dolby-vision",32,32,1_000_000,-1)
        val tracks=if(alias)listOf(d,d.copy(index=1,mime="video/hevc"))else listOf(d)
        return VideoTracks.Inventory(tracks,listOf(VideoTracks.Header(1,listOf("hvc1"),emptySet(),false,0,base)))
    }
}
class VideoDolbyTest {
    private val f=VideoDolbyFixture
    private fun reject(action:()->Unit){try{action();fail("must reject unsafe Dolby conversion")}catch(_:IllegalArgumentException){}}
    private fun clean(bytes:ByteArray,csd:ByteArray=f.csd()):ByteArray {val b=ByteBuffer.allocateDirect(bytes.size+16);b.put(bytes);val n=VideoDolby.cleanSample(b,bytes.size,csd);return ByteArray(n).also{b.position(0);b.get(it)}}
    @Test fun parsePq(){val c=VideoDolby.config(f.cfg());assertEquals(8,c.profile);assertEquals(6,c.level);assertEquals(16,c.transfer);assertNull(c.blockReason)}
    @Test fun parseHlg(){val c=VideoDolby.config(f.cfg(id=4));assertEquals(18,c.transfer);assertNull(c.blockReason)}
    @Test fun noProfile5(){assertNotNull(VideoDolby.config(f.cfg(profile=5,id=0)).blockReason)}
    @Test fun noProfile7(){assertNotNull(VideoDolby.config(f.cfg(profile=7,el=true)).blockReason)}
    @Test fun noProfile20(){assertNotNull(VideoDolby.config(f.cfg(profile=20)).blockReason)}
    @Test fun noUnknownId(){assertNotNull(VideoDolby.config(f.cfg(id=7)).blockReason)}
    @Test fun noSdrId(){assertNotNull(VideoDolby.config(f.cfg(id=2)).blockReason)}
    @Test fun noEnhancement(){assertNotNull(VideoDolby.config(f.cfg(el=true)).blockReason)}
    @Test fun noBase(){assertNotNull(VideoDolby.config(f.cfg(bl=false)).blockReason)}
    @Test fun noRpuFlag(){assertNotNull(VideoDolby.config(f.cfg(rpu=false)).blockReason)}
    @Test fun noUnknownLevel(){assertNotNull(VideoDolby.config(f.cfg(level=63)).blockReason)}
    @Test fun noAdvancedBits(){val b=f.cfg();b[4]=(b[4].toInt() or 1).toByte();assertNotNull(VideoDolby.config(b).blockReason)}
    @Test fun noReservedExtension(){val b=f.cfg();b[20]=1;assertNotNull(VideoDolby.config(b).blockReason)}
    @Test fun noTruncatedConfig(){reject{VideoDolby.config(f.cfg().copyOf(23))}}
    @Test fun noLongConfig(){reject{VideoDolby.config(f.cfg()+0)}}
    @Test fun noUnknownVersion(){val b=f.cfg();b[1]=1;reject{VideoDolby.config(b)}}
    @Test fun truthfulNotice(){val c=VideoDolby.config(f.cfg());assertTrue(c.notice.contains("普通HEVC"));assertTrue(c.notice.contains("不含杜比视界"));assertTrue(c.notice.contains("原文件不修改"))}
    @Test fun cleanConfiguration(){val b=f.csd();assertArrayEquals(b,VideoDolby.cleanConfig(b))}
    @Test fun configRpuAndSeiRemoved(){val b=f.csd();assertArrayEquals(b,VideoDolby.cleanConfig(b+f.framed(f.nal(62),f.nal(39),f.nal(40))))}
    @Test fun noDuplicateSps(){reject{VideoDolby.cleanConfig(f.csd()+f.framed(VideoCodecConfig.annex(f.csd())[1]))}}
    @Test fun noMissingVps(){reject{VideoDolby.cleanConfig(f.framed(*VideoCodecConfig.annex(f.csd()).drop(1).toTypedArray()))}}
    @Test fun noMultilayerVps(){reject{VideoDolby.cleanConfig(f.csd(layers=1))}}
    @Test fun noNonMain10(){reject{VideoDolby.cleanConfig(f.csd(main=1))}}
    @Test fun configNoLeadingGarbage(){reject{VideoDolby.cleanConfig(byteArrayOf(9)+f.csd())}}
    @Test fun configLayerRejected(){reject{VideoDolby.cleanConfig(f.csd()+f.framed(f.nal(62,layer=1)))}}
    @Test fun sameConfigAfterRpuRemoval(){VideoDolby.requireSameConfig(f.csd()+f.framed(f.nal(62)),f.csd())}
    @Test fun differentSystemSpsRejected(){reject{VideoDolby.requireSameConfig(f.csd(18),f.csd())}}
    @Test fun recordRoundtrip(){assertArrayEquals(f.csd(),VideoDolby.hevcRecord(f.hvcc()))}
    @Test fun recordTrailingRejected(){reject{VideoDolby.hevcRecord(f.hvcc()+1)}}
    @Test fun recordLengthRejected(){val b=f.hvcc();b[21]=1;reject{VideoDolby.hevcRecord(b)}}
    @Test fun recordWrongDepthRejected(){val b=f.hvcc();b[17]=0;reject{VideoDolby.hevcRecord(b)}}
    @Test fun recordWrongArrayRejected(){val b=f.hvcc();b[23]=33;reject{VideoDolby.hevcRecord(b)}}
    @Test fun pqColor(){assertEquals(VideoContract.Color(9,16,9,false,10),VideoDolby.color(f.inventory().dolbyBase!!,32,32))}
    @Test fun hlgColor(){assertEquals(18,VideoDolby.color(f.inventory(f.cfg(id=4),f.csd(18)).dolbyBase!!,32,32).transfer)}
    @Test fun fullRangeColor(){assertTrue(VideoDolby.color(f.inventory(csd=f.csd(full=true)).dolbyBase!!,32,32).fullRange)}
    @Test fun conflictingTransferBlocked(){assertNotNull(f.inventory(csd=f.csd(18)).processingBlockReason)}
    @Test fun missingColorBlocked(){assertNotNull(f.inventory(csd=f.csd(tagged=false)).processingBlockReason)}
    @Test fun wrongMatrixBlocked(){assertNotNull(f.inventory(csd=f.csd(matrix=15)).processingBlockReason)}
    @Test fun lowerDepthBlocked(){assertNotNull(f.inventory(csd=f.csd(depth=8)).processingBlockReason)}
    @Test fun wrongSizeBlocked(){reject{VideoDolby.color(f.inventory().dolbyBase!!,64,32)}}
    @Test fun compatibleAliasCollapsed(){val i=f.inventory();assertEquals(2,i.videos.size);assertEquals(1,i.processingVideos.size);assertEquals(1,i.choose().index);assertNotNull(i.conversionNotice)}
    @Test fun singleDolbyViewAllowed(){assertEquals(0,f.inventory(alias=false).choose().index)}
    @Test fun singleHevcViewAllowed(){val i=f.inventory();assertEquals(1,i.copy(tracks=listOf(i.tracks[1])).choose().index)}
    @Test fun mismatchedAliasIdBlocked(){val i=f.inventory();reject{i.copy(tracks=listOf(i.tracks[0],i.tracks[1].copy(id=2))).choose()}}
    @Test fun mismatchedAliasSizeBlocked(){val i=f.inventory();reject{i.copy(tracks=listOf(i.tracks[0],i.tracks[1].copy(width=64))).choose()}}
    @Test fun mismatchedAliasDurationBlocked(){val i=f.inventory();reject{i.copy(tracks=listOf(i.tracks[0],i.tracks[1].copy(durationUs=900000))).choose()}}
    @Test fun extraVideoNotDropped(){val i=f.inventory();reject{i.copy(tracks=i.tracks+i.tracks[1].copy(index=2,id=3)).choose()}}
    @Test fun hiddenContainerLayerBlocked(){val i=f.inventory();reject{i.copy(headers=i.headers!!+i.headers!![0].copy(id=3)).choose()}}
    @Test fun dependencyBlocked(){val i=f.inventory();reject{i.copy(headers=listOf(i.headers!![0].copy(unsafe=true,dependencies=setOf(2)))).choose()}}
    @Test fun aliasRotationConflictBlocked(){val i=f.inventory();reject{i.copy(tracks=listOf(i.tracks[0].copy(rotation=90),i.tracks[1].copy(rotation=0))).choose()}}
    @Test fun missingContainerProofBlocked(){reject{f.inventory().copy(headers=null).choose()}}
    @Test fun unknownAliasIndexBlocked(){reject{f.inventory().choose(0)}}
    @Test fun compatiblePqEntry(){assertNotNull(VideoDolby.entry(f.entry(color=16)))}
    @Test fun compatibleHlgEntry(){assertNotNull(VideoDolby.entry(f.entry(config=f.cfg(id=4),csd=f.csd(18),color=18)))}
    @Test fun dvheEntryWithCompatibleProof(){assertNotNull(VideoDolby.entry(f.entry(type="dvhe")))}
    @Test fun conflictingColrNotAccepted(){assertNull(VideoDolby.entry(f.entry(color=18)))}
    @Test fun duplicateDoviNotAccepted(){assertNull(VideoDolby.entry(f.entry(extra=VideoIso.atom("dvcC",f.cfg()).raw)))}
    @Test fun parseHeadersWithConfig(){f.file{file->val h=VideoTracks.headers(file).single();assertFalse(h.unsafe);assertNotNull(h.dolby)}}
    @Test fun malformedConfigUnsafe(){f.file(f.entry(config=byteArrayOf(1))){file->assertTrue(VideoTracks.headers(file).single().unsafe)}}
    @Test fun unknownBoxUnsafe(){f.file(f.entry(extra=VideoIso.atom("uuid",ByteArray(16)).raw)){file->assertTrue(VideoTracks.headers(file).single().unsafe)}}
    @Test fun headerSourceUnchanged(){f.file{file->val bytes=file.readBytes();VideoTracks.headers(file);assertArrayEquals(bytes,file.readBytes())}}
    @Test fun stripRpuAnnex(){val v=f.nal(19);assertArrayEquals(f.framed(v),clean(f.framed(f.nal(62),v)))}
    @Test fun stripRpuLength(){val v=f.nal(19);assertArrayEquals(f.framed(v,annex=false),clean(f.framed(f.nal(62),v,f.nal(62),annex=false)))}
    @Test fun stripBothSei(){val v=f.nal(1);assertArrayEquals(f.framed(v),clean(f.framed(f.nal(39),v,f.nal(40))))}
    @Test fun shortAnnexPrefix(){val v=f.nal(1);assertArrayEquals(f.framed(v,short=true),clean(f.framed(f.nal(62),v,short=true)))}
    @Test fun everyVclBytePreserved(){val v=f.nal(19,ByteArray(100000){((it%251)+1).toByte()});assertArrayEquals(f.framed(v),clean(f.framed(f.nal(62),v)))}
    @Test fun keepInBandConfig(){val v=f.nal(19);assertArrayEquals(f.csd()+f.framed(v),clean(f.csd()+f.framed(f.nal(62),v)))}
    @Test fun rejectChangedSps(){reject{clean(f.csd(18)+f.framed(f.nal(19)))}}
    @Test fun rejectChangedPps(){reject{clean(f.framed(f.nal(34,byteArrayOf(0x66)),f.nal(19)))}}
    @Test fun rejectChangedVps(){reject{clean(f.framed(f.nal(32,byteArrayOf(0x66)),f.nal(19)))}}
    @Test fun rejectElNal(){reject{clean(f.framed(f.nal(63),f.nal(19)))}}
    @Test fun rejectLayer(){reject{clean(f.framed(f.nal(19,layer=1)))}}
    @Test fun rejectReservedVcl(){reject{clean(f.framed(f.nal(13)))}}
    @Test fun rejectNoPicture(){reject{clean(f.framed(f.nal(62),f.nal(39)))}}
    @Test fun rejectTruncatedLength(){reject{clean(byteArrayOf(0,0,0,99,38,1,5))}}
    @Test fun rejectReadOnlyBuffer(){val b=f.framed(f.nal(19));reject{VideoDolby.cleanSample(ByteBuffer.wrap(b).asReadOnlyBuffer(),b.size,f.csd())}}
    @Test fun cancelledInspectionPropagates(){val b=f.framed(f.nal(19));try{VideoDolby.cleanSample(ByteBuffer.wrap(b),b.size,f.csd()){throw CancellationException()};fail()}catch(_:CancellationException){}}
    @Test fun cancelledLargeNalPropagates(){val b=f.framed(f.nal(62),f.nal(19,ByteArray(200000){0x55}));var n=0;try{VideoDolby.cleanSample(ByteBuffer.wrap(b),b.size,f.csd()){if(++n==3)throw CancellationException()};fail()}catch(_:CancellationException){assertEquals(3,n)}}
    @Test fun callerBufferStateUnchanged(){val data=f.framed(f.nal(62),f.nal(19));val b=ByteBuffer.wrap(data);b.position(2);b.limit(3);VideoDolby.cleanSample(b,data.size,f.csd());assertEquals(2,b.position());assertEquals(3,b.limit())}
    @Test fun guardsUnchanged(){val data=f.framed(f.nal(62),f.nal(19));val b=ByteBuffer.allocate(data.size+8);b.put(data);repeat(8){b.put(0x66)};VideoDolby.cleanSample(b,data.size,f.csd());for(i in data.size until b.capacity())assertEquals(0x66,b.get(i).toInt())}
    @Test fun outputDolbyDescriptionBlocked(){f.file{file->reject{VideoDolby.verifyOutput(file)}}}
    @Test fun outputOrdinaryDescriptionAllowed(){val e=f.entry();val plain=VideoIso.atom("hvc1",e.body.copyOfRange(0,78)+e.children(78).filter{it.type!="dvvC"}.flatMap{it.raw.asIterable()}.toByteArray());f.file(plain){VideoDolby.verifyOutput(it)}}
    @Test fun outputDolbyBrandBlocked(){val e=f.entry();val plain=VideoIso.atom("hvc1",e.body.copyOfRange(0,78)+e.children(78).filter{it.type!="dvvC"}.flatMap{it.raw.asIterable()}.toByteArray());for(brand in listOf("dvhe","dvh1","db4h")){f.file(plain,brand){file->reject{VideoDolby.verifyOutput(file)}}}}
    @Test fun ambiguousLengthPrefixPreserved(){for(n in listOf(256,257,511,512)){val v=f.nal(19,ByteArray(n-2){0x55});assertArrayEquals(f.framed(v,annex=false),clean(f.framed(v,annex=false)))}}
    @Test fun ambiguousLengthAfterRpuPreserved(){val v=f.nal(19,ByteArray(255){0x55});assertArrayEquals(f.framed(v,annex=false),clean(f.framed(f.nal(62),v,annex=false)))}
    @Test fun outputConfigurationRpuBlocked(){val e=f.entry(csd=f.csd()+f.framed(f.nal(62)));val plain=VideoIso.atom("hvc1",e.body.copyOfRange(0,78)+e.children(78).filter{it.type!="dvvC"}.flatMap{it.raw.asIterable()}.toByteArray());f.file(plain){file->reject{VideoDolby.verifyOutput(file)}}}
    @Test fun outputCancellationPropagates(){f.file{file->try{VideoDolby.verifyOutput(file){throw CancellationException()};fail()}catch(_:CancellationException){}}}
}
