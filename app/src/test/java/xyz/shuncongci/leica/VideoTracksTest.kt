package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Pure policy + synthetic ISO headers, not Android extractor/codec/UI execution. */
class VideoTracksTest {
    private fun video(index:Int=0,id:Int=index+1,mime:String="video/avc",rotation:Int=0)=VideoTracks.Track(index,id,mime,1920,1080,1_000_000,rotation)
    private fun header(id:Int=1,codec:String="avc1",unsafe:Boolean=false,refs:Set<Int> = emptySet(),rotation:Int=0)=VideoTracks.Header(id,listOf(codec),refs,unsafe,rotation)
    private fun inventory(vararg t:VideoTracks.Track,headers:List<VideoTracks.Header>?=null)=VideoTracks.Inventory(t.toList(),headers,"synthetic no ISO header")
    private fun reject(message:String="",action:()->Unit){
        try{action();fail("expected rejection: $message")}catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty(),message.isEmpty() || e.message.orEmpty().contains(message))}
    }
    @Test fun oldRuleRejectsIndependentMulti(){val t=listOf(video(),video(1));reject{require(t.count{it.video}==1){"需要且仅支持一条主视频轨"}}}
    @Test fun singleAvc(){assertEquals(0,inventory(video()).choose().index)}
    @Test fun singleHevc(){assertEquals("video/hevc",inventory(video(mime="video/hevc")).choose().normalizedMime)}
    @Test fun audioNotVideo(){assertEquals(1,inventory(VideoTracks.Track(0,2,"audio/mp4a-latm"),video(1)).choose().index)}
    @Test fun unknownDataNotVideo(){assertEquals(2,inventory(VideoTracks.Track(0,2,"application/octet-stream"),VideoTracks.Track(1,3,"text/3gpp"),video(2)).choose().index)}
    @Test fun emptyDiagnostic(){reject("没有识别到"){inventory().choose()}}
    @Test fun audioOnlyDiagnostic(){reject("没有识别到"){inventory(VideoTracks.Track(0,1,"audio/aac")).choose()}}
    @Test fun normalizedMime(){assertEquals("video/avc",inventory(video(mime=" VIDEO/AVC ")).choose().normalizedMime)}
    @Test fun noBlindFirst(){reject("请先"){inventory(video(),video(1)).choose()}}
    @Test fun noBlindLargest(){reject("请先"){inventory(video().copy(width=640),video(1).copy(width=3840)).choose()}}
    @Test fun explicitSecond(){assertEquals(1,inventory(video(),video(1),headers=listOf(header(),header(2))).choose(1).index)}
    @Test fun explicitFirst(){assertEquals(0,inventory(video(),video(1),headers=listOf(header(),header(2))).choose(0).index)}
    @Test fun explicitMissing(){reject("不存在"){inventory(video()).choose(9)}}
    @Test fun explicitAudio(){reject("不存在"){inventory(video(),VideoTracks.Track(1,2,"audio/aac")).choose(1)}}
    @Test fun duplicateIndex(){reject("索引"){inventory(video(),video()).choose(0)}}
    @Test fun negativeIndex(){reject("索引"){inventory(video(-1)).choose()}}
    @Test fun boundedTrackCount(){reject("容量"){VideoTracks.Inventory((0..64).map{video(it)},null).choose(0)}}
    @Test fun zeroWidth(){reject("画幅"){inventory(video().copy(width=0)).choose()}}
    @Test fun negativeHeight(){reject("画幅"){inventory(video().copy(height=-1)).choose()}}
    @Test fun noDuration(){reject("时长"){inventory(video().copy(durationUs=0)).choose()}}
    @Test fun unsupportedCodec(){reject("AVC/HEVC"){inventory(video(mime="video/vp9")).choose()}}
    @Test fun dolbySingle(){reject("杜比"){inventory(video(mime="video/dolby-vision")).choose()}}
    @Test fun dolbyAliasNotDropped(){reject("杜比"){inventory(video(mime="video/hevc"),video(1,mime="video/dolby-vision")).choose(0)}}
    @Test fun unknownMultiProof(){reject("独立性"){inventory(video(),video(1)).choose(0)}}
    @Test fun missingMultiId(){reject("ID"){inventory(video(id=-1),video(1),headers=listOf(header(),header(2))).choose(0)}}
    @Test fun duplicatedMultiId(){reject("ID"){inventory(video(),video(1,id=1),headers=listOf(header(),header(2))).choose(0)}}
    @Test fun mismatchedIds(){reject("ID"){inventory(video(),video(1),headers=listOf(header(),header(3))).choose(0)}}
    @Test fun hiddenContainerVideo(){reject("数量"){inventory(video(),headers=listOf(header(),header(2))).choose()}}
    @Test fun missingContainerVideo(){reject("数量"){inventory(video(),video(1),headers=listOf(header())).choose(0)}}
    @Test fun singleKnownIdMismatch(){reject("ID"){inventory(video(id=9),headers=listOf(header())).choose()}}
    @Test fun singleUnknownIdMapped(){assertEquals(0,inventory(video(id=-1),headers=listOf(header())).choose().index)}
    @Test fun unsafeSelected(){reject("增强"){inventory(video(),headers=listOf(header(unsafe=true))).choose()}}
    @Test fun unsafeOtherNotSilentlyDropped(){reject("依赖"){inventory(video(),video(1),headers=listOf(header(),header(2,unsafe=true))).choose(0)}}
    @Test fun crossReferenceRejected(){reject("依赖"){inventory(video(),video(1),headers=listOf(header(refs=setOf(2)),header(2))).choose(0)}}
    @Test fun externalTimecodeReferenceAllowed(){assertEquals(0,inventory(video(),video(1),headers=listOf(header(refs=setOf(3)),header(2))).choose(0).index)}
    @Test fun unsupportedOtherCodec(){reject("编码"){inventory(video(),video(1,mime="video/vp9"),headers=listOf(header(),header(2,"vp09"))).choose(0)}}
    @Test fun wrongSingleCodec(){reject("编码"){inventory(video(),headers=listOf(header(codec="hvc1"))).choose()}}
    @Test fun badRotation(){reject("方向"){inventory(video(rotation=45)).choose()}}
    @Test fun containerRotationFallback(){assertEquals(90,inventory(video(rotation=-1),headers=listOf(header(rotation=90))).choose().rotation)}
    @Test fun rotationConflict(){reject("方向"){inventory(video(rotation=0),headers=listOf(header(rotation=90))).choose()}}
    @Test fun noIsoDefaultRotation(){assertEquals(0,inventory(video(rotation=-1)).choose().rotation)}
    @Test fun summaryHasActualCountsAndIds(){val s=inventory(video(),video(3),headers=listOf(header(),header(4))).summary;assertTrue(s.contains("2 条视频"));assertTrue(s.contains("轨道 4"));assertTrue(s.contains("ID 4"))}
    @Test fun diagnosticIssueBounded(){val s=VideoTracks.Inventory(listOf(video()),null,"x".repeat(2000)).summary;assertTrue(s.length<1000)}

    private fun atom(t:String,b:ByteArray)=VideoIso.atom(t,b)
    private fun box(t:String,vararg a:VideoIso.Atom)=atom(t,a.flatMap{it.raw.asIterable()}.toByteArray())
    private fun track(id:Int=1,codec:String="avc1",extra:String?=null,ref:String?=null,refId:Int=2,rotation:Int=0,version:Int=0,
                      handler:String="vide",external:Boolean=false,group:Boolean=false,perspective:Boolean=false,translate:Boolean=false):VideoIso.Atom {
        val tk=ByteArray(if(version==1)96 else 84);tk[0]=version.toByte();val b=ByteBuffer.wrap(tk)
        b.putInt(if(version==1)20 else 12,id);val offset=if(version==1)52 else 40
        val v=when(rotation){0->intArrayOf(65536,0,0,65536);90->intArrayOf(0,65536,-65536,0);180->intArrayOf(-65536,0,0,-65536);270->intArrayOf(0,-65536,65536,0);else->intArrayOf(10,20,30,40)}
        listOf(0,1,3,4).forEachIndexed{i,n->b.putInt(offset+4*n,v[i])};b.putInt(offset+32,0x40000000);if(perspective)b.putInt(offset+8,1);if(translate)b.putInt(offset+24,1080*65536)
        val h=ByteArray(24);"$handler".toByteArray().copyInto(h,8)
        val vh=ByteArray(78);ByteBuffer.wrap(vh).putShort(6,1)
        val entry=atom(codec,vh+(extra?.let{atom(it,ByteArray(8)).raw}?:byteArrayOf()))
        val stsd=atom("stsd",VideoIso.bytes{writeInt(0);writeInt(1);write(entry.raw)})
        val dref=atom("dref",VideoIso.bytes{writeInt(0);writeInt(1);write(atom("url ",VideoIso.bytes{writeInt(if(external)0 else 1)}).raw)})
        val groups=if(group)arrayOf(atom("sgpd",ByteArray(12)))else emptyArray()
        val minf=box("minf",box("dinf",dref),box("stbl",stsd,*groups))
        val refs=if(ref==null)emptyArray()else arrayOf(box("tref",atom(ref,VideoIso.bytes{writeInt(refId)})))
        return box("trak",atom("tkhd",tk),box("mdia",atom("hdlr",h),minf),*refs)
    }
    private fun fixture(vararg tracks:VideoIso.Atom,action:(File)->Unit){val d=Files.createTempDirectory("video-track-test").toFile();try{val f=File(d,"synthetic.mp4");f.writeBytes(box("moov",*tracks).raw);action(f)}finally{d.deleteRecursively()}}
    @Test fun parseSingle(){fixture(track()){f->val h=VideoTracks.headers(f).single();assertEquals(1,h.id);assertFalse(h.unsafe)}}
    @Test fun parseMultiThenChoose(){fixture(track(),track(2,"hvc1")){f->val h=VideoTracks.headers(f);assertEquals(2,h.size);assertEquals(1,inventory(video(),video(1,mime="video/hevc"),headers=h).choose(1).index)}}
    @Test fun ignoreAudioHeader(){fixture(track(),track(2,handler="soun")){f->assertEquals(1,VideoTracks.headers(f).size)}}
    @Test fun parseAllRotations(){for(r in listOf(0,90,180,270))fixture(track(rotation=r)){f->assertEquals(r,VideoTracks.headers(f).single().rotation)}}
    @Test fun parseVersionOne(){fixture(track(version=1,rotation=270)){f->assertEquals(270,VideoTracks.headers(f).single().rotation)}}
    @Test fun parseTranslatedRotation(){fixture(track(rotation=90,translate=true)){f->assertFalse(VideoTracks.headers(f).single().unsafe)}}
    @Test fun parseInvalidMatrix(){fixture(track(rotation=42)){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun parsePerspective(){fixture(track(perspective=true)){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun parseDuplicateId(){fixture(track(),track()){f->reject("ID"){VideoTracks.headers(f)}}}
    @Test fun parseZeroId(){fixture(track(id=0)){f->reject{VideoTracks.headers(f)}}}
    @Test fun parseBadVersion(){fixture(track(version=2)){f->reject{VideoTracks.headers(f)}}}
    @Test fun dolbyConfigUnsafe(){fixture(track(codec="hvc1",extra="dvcC")){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun encryptedEntryUnsafe(){fixture(track(codec="encv")){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun unknownEntryUnsafe(){fixture(track(extra="uuid")){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun ordinaryColorBoxAllowed(){fixture(track(extra="colr")){f->assertFalse(VideoTracks.headers(f).single().unsafe)}}
    @Test fun auxiliaryDependencyUnsafe(){fixture(track(ref="auxl")){f->val h=VideoTracks.headers(f).single();assertTrue(h.unsafe);assertEquals(setOf(2),h.dependencies)}}
    @Test fun timecodeReferenceSafe(){fixture(track(ref="tmcd",refId=3)){f->assertFalse(VideoTracks.headers(f).single().unsafe)}}
    @Test fun externalSampleReferenceUnsafe(){fixture(track(external=true)){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun unknownSampleGroupUnsafe(){fixture(track(group=true)){f->assertTrue(VideoTracks.headers(f).single().unsafe)}}
    @Test fun noMovieHeader(){fixture(track()){f->f.writeBytes(atom("ftyp",ByteArray(8)).raw);reject("未找到"){VideoTracks.headers(f)}}}
    @Test fun fragmentedHeaderRejected(){fixture(track()){f->f.writeBytes(box("moov",track(),atom("mvex",ByteArray(0))).raw);reject("分片"){VideoTracks.headers(f)}}}
    @Test fun duplicateMovieHeader(){fixture(track()){f->f.appendBytes(f.readBytes());reject("重复"){VideoTracks.headers(f)}}}
    @Test fun truncatedHeader(){fixture(track()){f->f.writeBytes(byteArrayOf(1,2,3));reject("截断"){VideoTracks.headers(f)}}}
    @Test fun oversizedMovieHeader(){fixture(track()){f->RandomAccessFile(f,"rw").use{it.setLength(VideoIso.MAX_BOX+16L);it.seek(0);it.writeInt(VideoIso.MAX_BOX+16);it.writeBytes("moov")};reject("容量"){VideoTracks.headers(f)}}}
    @Test fun cancelledHeaderPropagates(){fixture(track()){f->try{VideoTracks.headers(f){throw CancellationException()};fail()}catch(_:CancellationException){}}}
    @Test fun headerReadNeverModifies(){fixture(track(),track(2)){f->val before=f.readBytes();VideoTracks.headers(f);assertArrayEquals(before,f.readBytes())}}

    @Test fun successfulHandoff(){val g=VideoTaskGate();val a=Any();g.begin(a);assertEquals(VideoTaskGate.End.SUCCESS,g.finish(a,false))}
    @Test fun cancelAtHandoffKeepsCompleted(){val g=VideoTaskGate();val a=Any();var completed="old verified";g.begin(a);if(g.finish(a,true)==VideoTaskGate.End.SUCCESS)completed="new";assertEquals("old verified",completed)}
    @Test fun failedTaskKeepsCompleted(){val g=VideoTaskGate();val a=Any();var completed="old verified";g.begin(a);if(g.finish(a,false,true)==VideoTaskGate.End.SUCCESS)completed="new";assertEquals("old verified",completed)}
    @Test fun unfinishedIsNotSuccess(){val g=VideoTaskGate();assertEquals(VideoTaskGate.End.STALE,g.finish(Any(),false))}
    @Test fun duplicateHandoffIgnored(){val g=VideoTaskGate();val a=Any();g.begin(a);g.finish(a,false);assertEquals(VideoTaskGate.End.STALE,g.finish(a,false))}
    @Test fun destroyedOwnerRejectsLateResult(){val g=VideoTaskGate();val a=Any();g.begin(a);g.abandon();assertEquals(VideoTaskGate.End.STALE,g.finish(a,false))}
    @Test fun staleCannotEndNewTask(){val g=VideoTaskGate();val a=Any();val b=Any();g.begin(a);g.abandon();g.begin(b);assertEquals(VideoTaskGate.End.STALE,g.finish(a,false));assertEquals(VideoTaskGate.End.SUCCESS,g.finish(b,false))}
    @Test fun realFailurePreservedAfterCancel(){val g=VideoTaskGate();val a=Any();g.begin(a);assertEquals(VideoTaskGate.End.FAILED,g.finish(a,true,true))}
    @Test fun cannotOverlap(){val g=VideoTaskGate();g.begin(Any());try{g.begin(Any());fail()}catch(_:IllegalStateException){}}
    @Test fun newTaskAfterCancel(){val g=VideoTaskGate();val a=Any();val b=Any();g.begin(a);g.finish(a,true);g.begin(b);assertEquals(VideoTaskGate.End.SUCCESS,g.finish(b,false))}
}
