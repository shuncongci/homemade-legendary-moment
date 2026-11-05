package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Synthetic containers/bitstreams only. Does not stand in for MediaCodec or device validation. */
class VideoModeTest {
    private fun reject(action:()->Unit){try{action();fail("must reject")}catch(e:IllegalArgumentException){}catch(e:IllegalStateException){}}
    private class Bits {
        val b=StringBuilder()
        fun n(x:Int,k:Int){for(i in k-1 downTo 0)b.append(if(x ushr i and 1==1)'1' else '0')}
        fun ue(x:Int){val v=Integer.toBinaryString(x+1);b.append("0".repeat(v.length-1));b.append(v)}
        fun bytes():ByteArray {b.append('1');while(b.length%8!=0)b.append('0');val raw=b.chunked(8).map{it.toInt(2).toByte()};val out=java.io.ByteArrayOutputStream();var z=0;raw.forEach{v->val x=v.toInt() and 255;if(z>=2 && x<=3){out.write(3);z=0};out.write(x);z=if(x==0)z+1 else 0};return out.toByteArray()}
    }
    private fun avc(field:Boolean=false,transfer:Int=1,depth:Int=8,sar:Int=1):ByteArray {
        val b=Bits();b.n(100,8);b.n(0,8);b.n(30,8);b.ue(0);b.ue(1);b.ue(depth-8);b.ue(depth-8);b.n(0,2);b.ue(0);b.ue(0);b.ue(0);b.ue(1);b.n(0,1);b.ue(1);b.ue(1);b.n(if(field)0 else 1,1);b.n(1,1);b.n(0,1);b.n(1,1)
        b.n(1,1);b.n(sar,8);b.n(0,1);b.n(1,1);b.n(5,3);b.n(0,1);b.n(1,1);b.n(1,8);b.n(transfer,8);b.n(1,8);b.n(0,1)
        return byteArrayOf(0,0,0,1,0x67)+b.bytes()
    }
    private fun hevc(transfer:Int=16,field:Boolean=false,depth:Int=10):ByteArray {
        val b=Bits();b.n(0,4);b.n(0,3);b.n(1,1);repeat(12){b.n(0,8)};b.ue(0);b.ue(1);b.ue(32);b.ue(32);b.n(0,1);b.ue(depth-8);b.ue(depth-8);b.ue(4);b.n(0,1);repeat(3){b.ue(0)};repeat(6){b.ue(0)};b.n(0,1);b.n(0,2);b.n(0,1);b.ue(0);b.n(0,1);b.n(0,2);b.n(1,1)
        b.n(0,1);b.n(0,1);b.n(1,1);b.n(5,3);b.n(0,1);b.n(1,1);b.n(9,8);b.n(transfer,8);b.n(9,8);b.n(0,1);b.n(0,1);b.n(if(field)1 else 0,1);b.n(0,1);b.n(0,1)
        return byteArrayOf(0,0,0,1,0x42,1)+b.bytes()
    }
    private fun atom(t:String,body:ByteArray)=VideoIso.atom(t,body)
    private fun bytes(block:java.io.DataOutputStream.()->Unit)=VideoIso.bytes(block)
    private fun concat(vararg a:VideoIso.Atom)=a.flatMap{it.raw.asIterable()}.toByteArray()
    private fun list(t:String,vararg a:VideoIso.Atom)=atom(t,concat(*a))
    private fun record():ByteArray {val s=avc().drop(4).toByteArray();return bytes{write(byteArrayOf(1,100,0,30,-1,-31));writeShort(s.size);write(s);writeByte(1);writeShort(2);write(byteArrayOf(0x68,0))}}
    private fun movie(file:File,unknown:Boolean=false,badMeta:Boolean=false,offset:Long=0,ctts:Boolean=false,hasMeta:Boolean=true,mediaDuration:Int=150,sampleDurations:List<Int> = listOf(40,60,50),composition:List<Int>?=null,editDuration:Int?=null,editStart:Int=0):File {
        val ftyp=atom("ftyp","isom\u0000\u0000\u0000\u0000isom".toByteArray())
        val mdhd=atom("mdhd",bytes{writeInt(0);writeLong(0);writeInt(1000);writeInt(mediaDuration);writeInt(0)})
        val hdlr=atom("hdlr",bytes{writeLong(0);writeBytes("vide");write(ByteArray(12))})
        val header=ByteArray(78);ByteBuffer.wrap(header).putShort(6,1).putShort(24,32).putShort(26,32)
        val entry=atom("avc1",header+concat(atom("avcC",record()),atom("colr",bytes{writeBytes("nclx");writeShort(1);writeShort(1);writeShort(1);writeByte(0)})))
        val stsd=atom("stsd",bytes{writeInt(0);writeInt(1);write(entry.raw)})
        val stts=atom("stts",bytes{writeInt(0);writeInt(3);sampleDurations.forEach{writeInt(1);writeInt(it)}})
        val stsc=atom("stsc",bytes{writeInt(0);writeInt(1);writeInt(1);writeInt(3);writeInt(1)})
        val stsz=atom("stsz",bytes{writeInt(0);writeInt(4);writeInt(3)})
        val stco=atom("stco",bytes{writeInt(0);writeInt(1);writeInt((ftyp.raw.size+8L+offset).toInt())})
        val extras=if(composition!=null)arrayOf(atom("ctts",bytes{writeInt(0);writeInt(composition.size);composition.forEach{writeInt(1);writeInt(it)}})) else if(ctts)arrayOf(atom("ctts",bytes{writeInt(0);writeInt(1);writeInt(3);writeInt(20)}))else emptyArray()
        val table=list("stbl",stsd,stts,stsc,stsz,stco,*extras)
        val url=atom("url ",bytes{writeInt(1)});val dref=atom("dref",bytes{writeInt(0);writeInt(1);write(url.raw)})
        val minf=list("minf",atom("vmhd",ByteArray(12)),list("dinf",dref),table)
        val edits=if(editDuration==null)emptyArray() else arrayOf(list("edts",atom("elst",bytes{writeInt(0);writeInt(1);writeInt(editDuration);writeInt(editStart);writeInt(65536)})))
        val track=list("trak",atom("tkhd",ByteArray(84)),list("mdia",mdhd,hdlr,minf),*edits)
        val udta=if(badMeta)atom("udta",byteArrayOf(0,0,0,99,1))else list("udta",atom("©day","2026-synthetic".toByteArray()),atom("©mak","Synthetic camera".toByteArray()),atom("zzzz",byteArrayOf(9)))
        val moov=list("moov",atom("mvhd",bytes{writeInt(0);writeInt(123);writeInt(456);writeInt(1000);writeInt(mediaDuration);write(ByteArray(80))}),track,*(if(hasMeta)arrayOf(udta)else emptyArray()))
        file.writeBytes(concat(ftyp,atom("mdat",ByteArray(12){it.toByte()}),moov)+if(unknown)atom("uuid",ByteArray(16)).raw else byteArrayOf());return file
    }
    private fun temp(action:(File)->Unit){val d=Files.createTempDirectory("video-synthetic").toFile();try{action(d)}finally{d.deleteRecursively()}}
    @Test fun namesAndAssets(){assertEquals(listOf("徕卡一瞬","徕卡N瞬"),VideoColor.entries.map{it.label});assertEquals(listOf("looks/leica-instant-v2.bin","looks/leica-n-instant.bin"),VideoColor.entries.map{it.asset});assertEquals(LeicaStyle.LEICA_INSTANT.sha256,VideoColor.INSTANT.sha256)}
    @Test fun sdrValid(){VideoContract.Color(1,1,1,false,8).validate()}
    @Test fun pqValid(){VideoContract.Color(9,16,9,false,10).validate()}
    @Test fun hlgValid(){VideoContract.Color(9,18,9,true,10).validate()}
    @Test fun noHdrDowngrade(){reject{VideoContract.Color(9,16,9,false,8).validate()}}
    @Test fun noWrongMatrix(){reject{VideoContract.Color(9,16,1,false,10).validate()}}
    @Test fun timelineVfr(){assertArrayEquals(longArrayOf(0,40000,100000),VideoContract.timelineUs(listOf(20,60,120),1000))}
    @Test fun timelinePrecision(){reject{VideoContract.timelineUs(listOf(0,1),10_000_000)}}
    @Test fun timelineNegative(){reject{VideoContract.timelineUs(listOf(-1,1),1000)}}
    @Test fun timelineOrder(){reject{VideoContract.timelineUs(listOf(2,1),1000)}}
    @Test fun framesExact(){VideoContract.verifyFrames(longArrayOf(0,40,100),listOf(0,40,100))}
    @Test fun noDrop(){reject{VideoContract.verifyFrames(longArrayOf(0,40,100),listOf(0,100))}}
    @Test fun noDuplicate(){reject{VideoContract.verifyFrames(longArrayOf(0,40),listOf(0,40,40))}}
    @Test fun noRetiming(){reject{VideoContract.verifyFrames(longArrayOf(0,40),listOf(0,41))}}
    @Test fun optionalMalformedContinues(){val m=VideoMetadata();assertNull(m.attempt("metadata"){throw IllegalArgumentException("broken")});assertTrue(m.summary(true).contains("已导出"));assertTrue(m.warnings.single().contains("IllegalArgumentException"))}
    @Test fun optionalCopyIoKeepsFallback(){val m=VideoMetadata();assertNull(m.attempt("optional copy"){throw IOException("space")});assertTrue(m.summary(false).contains("可导出"))}
    @Test fun cancellationNotWarning(){val m=VideoMetadata();try{m.attempt<Unit>("x"){throw CancellationException()};fail()}catch(_:CancellationException){};assertTrue(m.warnings.isEmpty())}
    @Test fun securityNotWarning(){try{VideoMetadata().attempt<Unit>("x"){throw SecurityException()};fail()}catch(_:SecurityException){}}
    @Test fun interruptNotWarning(){try{VideoMetadata().attempt<Unit>("x"){throw InterruptedException()};fail()}catch(_:InterruptedException){}}
    @Test fun fatalNotWarning(){try{VideoMetadata().attempt<Unit>("x"){throw OutOfMemoryError("synthetic")};fail()}catch(_:OutOfMemoryError){}}
    @Test fun boundedWarnings(){val m=VideoMetadata();repeat(40){m.warn("$it"+"x".repeat(3000))};assertEquals(24,m.warnings.size);assertTrue(m.warnings.all{it.length==2048})}
    @Test fun warningsSurviveSuccess(){val m=VideoMetadata();m.warn("first");m.attempt("later"){123};m.summary(true);assertEquals(listOf("first"),m.warnings)}
    @Test fun notesNotErrors(){val m=VideoMetadata();m.note("scope");assertTrue(m.warnings.isEmpty());assertEquals(listOf("scope"),m.records);assertFalse(m.summary(true).contains("问题"))}
    @Test fun parseAvc(){val c=VideoBitstream.config(avc(),false);assertEquals(8,c.bits);assertEquals(32,c.width);assertEquals(32,c.height);assertEquals(1,c.primaries);assertEquals(1,c.transfer);assertEquals(false,c.full)}
    @Test fun parseAvcHigh10(){assertEquals(10,VideoBitstream.config(avc(depth=10),false).bits)}
    @Test fun rejectAvcFields(){reject{VideoBitstream.config(avc(field=true),false)}}
    @Test fun parseHevcPq(){val c=VideoBitstream.config(hevc(),true);assertEquals(10,c.bits);assertEquals(16,c.transfer);assertEquals(9,c.matrix);assertEquals(32,c.width)}
    @Test fun parseHevcHlg(){assertEquals(18,VideoBitstream.config(hevc(18),true).transfer)}
    @Test fun rejectHevcFields(){reject{VideoBitstream.config(hevc(field=true),true)}}
    @Test fun rejectTruncatedSps(){reject{VideoBitstream.config(hevc().copyOf(14),true)}}
    @Test fun sarRead(){val c=VideoBitstream.config(avc(sar=2),false);assertEquals(12,c.sarW);assertEquals(11,c.sarH)}
    @Test fun recordRead(){assertEquals(32,VideoBitstream.config(VideoBitstream.record(record(),false),false).height)}
    @Test fun recordBounds(){reject{VideoBitstream.record(record().copyOf(12),false)}}
    @Test fun annexSample(){val data=byteArrayOf(0,0,0,1,0x65,1);assertFalse(VideoBitstream.sample(ByteBuffer.wrap(data),data.size,false,avc()))}
    @Test fun lengthSample(){val data=byteArrayOf(0,0,0,2,0x65,1);assertFalse(VideoBitstream.sample(ByteBuffer.wrap(data),data.size,false,avc()))}
    @Test fun seiWarnsNotBlocks(){val data=byteArrayOf(0,0,0,1,6,1,0,0,0,1,0x65,1);assertTrue(VideoBitstream.sample(ByteBuffer.wrap(data),data.size,false,avc()))}
    @Test fun noStandaloneConfigFrame(){val d=avc();reject{VideoBitstream.sample(ByteBuffer.wrap(d),d.size,false,d)}}
    @Test fun noChangedSps(){val data=avc(transfer=6)+byteArrayOf(0,0,0,1,0x65,1);reject{VideoBitstream.sample(ByteBuffer.wrap(data),data.size,false,avc())}}
    @Test fun noDolbyEnhancement(){val d=byteArrayOf(0,0,0,1,0x7c,1,0,0,0,1,2,1,1);reject{VideoBitstream.sample(ByteBuffer.wrap(d),d.size,true,hevc())}}
    @Test fun noLayer(){val d=byteArrayOf(0,0,0,1,2,9,1);reject{VideoBitstream.sample(ByteBuffer.wrap(d),d.size,true,hevc())}}
    @Test fun noTruncatedNal(){val d=byteArrayOf(0,0,0,9,0x65,1);reject{VideoBitstream.sample(ByteBuffer.wrap(d),d.size,false,avc())}}
    @Test fun parseAtoms(){assertEquals("abcd",VideoIso.parse(atom("abcd",byteArrayOf(1)).raw).single().type)}
    @Test fun rejectAtomOverflow(){reject{VideoIso.parse(byteArrayOf(127,-1,-1,-1,97,98,99,100))}}
    @Test fun readSyntheticMovie(){temp{d->val m=VideoIso.read(movie(File(d,"in.mp4")));assertEquals(listOf(0L,40L,100L),m.video.samples.map{it.pts});assertEquals(150L,m.video.duration);assertEquals(8,VideoIso.color(m.video).bits)}}
    @Test fun sampleBounds(){temp{d->reject{VideoIso.read(movie(File(d,"bad.mp4"),offset=99999))}}}
    @Test fun unknownMetadataIsOptional(){temp{d->val f=movie(File(d,"unknown.mp4"),unknown=true);val m=VideoMetadata();assertNull(m.attempt("read"){VideoIso.read(f)});assertTrue(f.length()>0)}}
    @Test fun rewriteVfrAndMetadata(){temp{d->val s=VideoIso.read(movie(File(d,"source.mp4")));val e=VideoIso.read(movie(File(d,"encoded.mp4")));val out=File(d,"out.mp4");val before=s.file.readBytes();VideoIso.rewrite(s,e,out);val r=VideoIso.read(out);VideoIso.verify(s,r);assertArrayEquals(before,s.file.readBytes());assertEquals(listOf(40L,60L,50L),r.video.samples.map{it.duration});assertArrayEquals(e.sample(e.video,1),r.sample(r.video,1))}}
    @Test fun rewriteOffsetTimeline(){temp{d->val s=VideoIso.read(movie(File(d,"s.mp4"),ctts=true));val e=VideoIso.read(movie(File(d,"e.mp4")));val out=File(d,"o.mp4");VideoIso.rewrite(s,e,out);assertEquals(listOf(20L,60L,120L),VideoIso.read(out).video.samples.map{it.pts})}}
    @Test fun noOverwrite(){temp{d->val s=VideoIso.read(movie(File(d,"s.mp4")));reject{VideoIso.rewrite(s,s,s.file)}}}
    @Test fun salvageUnknownStructure(){temp{d->val s=movie(File(d,"s.mp4"),unknown=true);val e=movie(File(d,"e.mp4"),hasMeta=false);val before=e.readBytes();val m=VideoMetadata();val out=File(d,"o.mp4");VideoIso.salvage(s,e,out,m);val r=VideoIso.read(out);assertTrue(r.moov.child("udta").children().any{it.type=="©day"});assertTrue(r.moov.child("udta").children().none{it.type=="zzzz"});assertArrayEquals(before,e.readBytes());assertArrayEquals(byteArrayOf(0,1,2,3),r.sample(r.video,0));assertTrue(m.warnings.isNotEmpty())}}
    @Test fun corruptMetadataStillValidFallback(){temp{d->val s=movie(File(d,"s.mp4"),badMeta=true);val e=movie(File(d,"e.mp4"));val m=VideoMetadata();val before=e.readBytes();m.attempt("salvage"){VideoIso.salvage(s,e,File(d,"o.mp4"),m)};assertTrue(m.warnings.isNotEmpty());assertArrayEquals(before,e.readBytes());assertEquals(3,VideoIso.read(e).video.samples.size)}}
    @Test fun bFrameDisplaySpanNotDecodeSpan(){temp{d->val s=VideoIso.read(movie(File(d,"s.mp4"),mediaDuration=140,sampleDurations=listOf(40,40,50),composition=listOf(40,100,0)));assertEquals(130L,s.video.samples.sumOf{it.duration});assertEquals(140L,s.video.duration);val e=VideoIso.read(movie(File(d,"e.mp4"),mediaDuration=140,sampleDurations=listOf(40,60,40)));val out=File(d,"o.mp4");VideoIso.rewrite(s,e,out);val r=VideoIso.read(out);assertEquals(listOf(40L,80L,140L),r.video.samples.map{it.pts});assertEquals(listOf(40L,60L,40L),r.video.samples.map{it.duration})}}
    @Test fun bFrameExactEditSpan(){temp{d->val s=VideoIso.read(movie(File(d,"s.mp4"),mediaDuration=135,sampleDurations=listOf(40,40,50),composition=listOf(40,100,0),editDuration=135,editStart=40));val e=VideoIso.read(movie(File(d,"e.mp4"),mediaDuration=135,sampleDurations=listOf(40,60,35)));val out=File(d,"o.mp4");VideoIso.rewrite(s,e,out);assertEquals(listOf(40L,60L,35L),VideoIso.read(out).video.samples.map{it.duration})}}
    @Test fun mismatchedEditSpanRejected(){temp{d->reject{VideoIso.read(movie(File(d,"bad.mp4"),mediaDuration=135,sampleDurations=listOf(40,40,50),composition=listOf(40,100,0),editDuration=134,editStart=40))}}}
    @Test fun noGuessedContainerDuration(){temp{d->reject{VideoIso.read(movie(File(d,"bad.mp4"),mediaDuration=141,sampleDurations=listOf(40,40,50),composition=listOf(40,100,0)))}}}
    @Test fun zeroLastDurationRejected(){temp{d->reject{VideoIso.read(movie(File(d,"bad.mp4"),mediaDuration=100,sampleDurations=listOf(40,60,0)))}}}
    @Test fun zeroInteriorDurationRejected(){temp{d->reject{VideoIso.read(movie(File(d,"bad.mp4"),mediaDuration=100,sampleDurations=listOf(40,0,60)))}}}
    private fun rollDescription(kind:String="roll",version:Int=1,length:Int=2,entries:Int=1,flags:Int=0)=atom("sgpd",bytes{writeInt((version shl 24) or flags);writeBytes(kind);writeInt(length);writeInt(entries);writeShort(-1)})
    private fun rollMembership(kind:String="roll",samples:Int=3,index:Int=1,version:Int=0)=atom("sbgp",bytes{writeInt(version shl 24);writeBytes(kind);writeInt(1);writeInt(samples);writeInt(index)})
    @Test fun audioNoGroups(){VideoIso.audioRollGroups(emptyList(),3)}
    @Test fun audioRollPreserved(){val a=rollDescription();val b=rollMembership();val before=concat(a,b);VideoIso.audioRollGroups(listOf(a,b),3);assertArrayEquals(before,concat(a,b))}
    @Test fun audioProlPreserved(){VideoIso.audioRollGroups(listOf(rollDescription("prol"),rollMembership("prol")),3)}
    @Test fun audioUngroupedIndexAllowed(){VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(index=0)),3)}
    @Test fun audioGroupMissing(){reject{VideoIso.audioRollGroups(listOf(rollDescription()),3)}}
    @Test fun audioGroupDuplicate(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollDescription(),rollMembership()),3)}}
    @Test fun audioUnknownGroup(){reject{VideoIso.audioRollGroups(listOf(rollDescription("seig"),rollMembership("seig")),3)}}
    @Test fun audioGroupTypeMismatch(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership("prol")),3)}}
    @Test fun audioGroupVersionRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(version=2),rollMembership()),3)}}
    @Test fun audioMembershipVersionRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(version=1)),3)}}
    @Test fun audioGroupFlagsRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(flags=1),rollMembership()),3)}}
    @Test fun audioGroupLengthRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(length=4),rollMembership()),3)}}
    @Test fun audioGroupTruncatedRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(entries=2),rollMembership()),3)}}
    @Test fun audioGroupIndexRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(index=2)),3)}}
    @Test fun audioGroupZeroSamplesRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(samples=0)),3)}}
    @Test fun audioGroupOversizedRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(samples=-1)),3)}}
    @Test fun audioGroupIncompleteRejected(){reject{VideoIso.audioRollGroups(listOf(rollDescription(),rollMembership(samples=2)),3)}}
    @Test fun metadataCancellation(){temp{d->val s=movie(File(d,"s.mp4"));val e=movie(File(d,"e.mp4"));try{VideoMetadata().attempt("metadata"){VideoIso.salvage(s,e,File(d,"o.mp4"),VideoMetadata()){throw CancellationException()}};fail()}catch(_:CancellationException){};assertFalse(File(d,"o.mp4").exists())}}
}
