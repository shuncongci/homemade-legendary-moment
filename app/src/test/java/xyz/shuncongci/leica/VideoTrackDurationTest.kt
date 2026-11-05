package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/** Real BMFF tables, not a change to the previous fixtures or assertions.
 * mdhd may include an explicit empty edit; stts never includes that silence/delay. */
class VideoTrackDurationTest {
    private val iso=VideoIso
    private fun atom(type:String,body:ByteArray)=iso.atom(type,body)
    private fun list(type:String,vararg children:VideoIso.Atom)=atom(type,children.flatMap{it.raw.asIterable()}.toByteArray())
    private fun data(block:java.io.DataOutputStream.()->Unit)=iso.bytes(block)
    private inline fun temp(block:(File)->Unit){val dir=Files.createTempDirectory("track-duration-").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    private fun reject(block:()->Unit){try{block();fail("must reject inconsistent timing")}catch(_:IllegalArgumentException){}}
    private fun movie(file:File,handler:String="vide",scale:Long=90_000,movieScale:Long=1000,
        durations:List<Long> = listOf(3000,4500,4500),declared:Long=14_700,
        empty:Long?=30,segment:Long=133,media:Long=0,rate:Int=65536,version:Int=0,
        composition:List<Int>?=null,extraEdit:Boolean=false):File {
        val ftyp=atom("ftyp","isom\u0000\u0000\u0000\u0000isom".toByteArray())
        val mdhd=atom("mdhd",data{writeInt(version shl 24);if(version==1){writeLong(0);writeLong(0);writeInt(scale.toInt());writeLong(declared)}else{writeLong(0);writeInt(scale.toInt());writeInt(declared.toInt())};writeInt(0)})
        val hdlr=atom("hdlr",data{writeLong(0);writeBytes(handler);write(ByteArray(12))})
        val entry=atom(if(handler=="soun")"mp4a" else "avc1",ByteArray(if(handler=="soun")28 else 78))
        val stsd=atom("stsd",data{writeInt(0);writeInt(1);write(entry.raw)})
        val stts=atom("stts",data{writeInt(0);writeInt(durations.size);durations.forEach{writeInt(1);writeInt(it.toInt())}})
        val stsz=atom("stsz",data{writeInt(0);writeInt(4);writeInt(durations.size)})
        val stsc=atom("stsc",data{writeInt(0);writeInt(1);writeInt(1);writeInt(durations.size);writeInt(1)})
        val stco=atom("stco",data{writeInt(0);writeInt(1);writeInt(ftyp.raw.size+8)})
        val ctts=composition?.let{atom("ctts",data{writeInt(0);writeInt(it.size);it.forEach{v->writeInt(1);writeInt(v)}})}
        val stbl=list("stbl",stsd,stts,stsz,stsc,stco,*(ctts?.let{arrayOf(it)}?:emptyArray()))
        val dref=atom("dref",data{writeInt(0);writeInt(1);write(atom("url ",data{writeInt(1)}).raw)})
        val minf=list("minf",list("dinf",dref),stbl)
        val tkhd=atom("tkhd",data{writeInt(0);writeLong(0);writeInt(if(handler=="soun")2 else 1);write(ByteArray(68))})
        val edits=if(empty==null)emptyArray() else arrayOf(list("edts",atom("elst",data{
            writeInt(version shl 24);writeInt(if(extraEdit)3 else 2)
            fun row(length:Long,start:Long){if(version==1){writeLong(length);writeLong(start)}else{writeInt(length.toInt());writeInt(start.toInt())};writeInt(rate)}
            row(empty,-1);row(segment,media);if(extraEdit)row(1,0)
        })))
        val track=list("trak",tkhd,list("mdia",mdhd,hdlr,minf),*edits)
        val video=if(handler=="soun"){
            // read() still requires exactly one video; build it independently, then rebase only its chunk offset.
            val separate=movie(File(file.parentFile,"helper-video.mp4"),declared=12_000,empty=null)
            val m=iso.read(separate);val t=m.video
            val newCo=atom("stco",data{writeInt(0);writeInt(1);writeInt(ftyp.raw.size+8+durations.size*4)})
            t.replaceTable(t.stbl.withChildren(t.stbl.children().map{if(it.type=="stco")newCo else it}))
        }else null
        val mvhd=atom("mvhd",data{writeInt(0);writeLong(0);writeInt(movieScale.toInt());writeInt(1000);write(ByteArray(80))})
        val moov=list("moov",mvhd,track,*(video?.let{arrayOf(it)}?:emptyArray()))
        file.writeBytes(ftyp.raw+atom("mdat",ByteArray(durations.size*4+(if(video!=null)12 else 0))).raw+moov.raw)
        return file
    }
    @Test fun delayedVideoHeaderReproducesOldRejection(){temp{d->val t=iso.read(movie(File(d,"video.mp4"))).video;assertEquals(14_700L,t.duration);assertEquals(12_000L,t.samples.sumOf{it.duration})}}
    @Test fun delayedAudioHeaderReproducesOldRejection(){temp{d->val t=iso.read(movie(File(d,"audio.mp4"),handler="soun")).tracks.first{it.handler=="soun"};assertEquals(14_700L,t.duration);assertEquals(12_000L,t.samples.sumOf{it.duration})}}
    @Test fun versionOneDelay(){temp{d->assertEquals(14_700L,iso.read(movie(File(d,"v1.mp4"),version=1)).video.duration)}}
    @Test fun ordinaryMediaDurationStillAccepted(){temp{d->assertEquals(12_000L,iso.read(movie(File(d,"normal.mp4"),declared=12_000)).video.duration)}}
    @Test fun noUnexplainedExtraTick(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),declared=14_701))}}}
    @Test fun noUnexplainedMissingTick(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),declared=14_699))}}}
    @Test fun noGuessWithoutEdit(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),empty=null))}}}
    @Test fun wrongEmptyEditRejected(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),empty=29))}}}
    @Test fun wrongActiveSegmentRejected(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),segment=131))}}}
    @Test fun trimCannotExplainDelayedHeader(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),media=1))}}}
    @Test fun speedChangeRejected(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),rate=131072))}}}
    @Test fun multipleActiveEditsRejected(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),extraEdit=true))}}}
    @Test fun reorderingNotMistakenForSimpleDelay(){temp{d->reject{iso.read(movie(File(d,"bad.mp4"),composition=listOf(0,1000,0)))}}}
    @Test fun audioDifferentClockDelay(){temp{d->val t=iso.read(movie(File(d,"audio.mp4"),handler="soun",scale=48_000,durations=listOf(1024,1024,1024),declared=3552,empty=10,segment=64)).tracks.first{it.handler=="soun"};assertEquals(3552L,t.duration)}}
    @Test fun exactActiveSegment(){temp{d->assertEquals(14_700L,iso.read(movie(File(d,"exact.mp4"),movieScale=90_000,empty=2700,segment=12_000)).video.duration)}}
    @Test fun delayNotIncludedInMediaSpan(){temp{d->val t=iso.read(movie(File(d,"span.mp4"))).video;assertEquals(12_000L,t.mediaDuration);assertEquals(4500L,t.samples.last().duration)}}
    @Test fun delayedMovieStillPassesWholeFrameGate(){temp{d->val m=iso.read(movie(File(d,"gate.mp4")));VideoMuxTiming.verifyMovie(longArrayOf(30000,63333,113333),listOf(30000,63333,113333),m,163333,163333)}}
    @Test fun delayedMovieChangedEndStillRejected(){temp{d->val m=iso.read(movie(File(d,"end.mp4")));reject{VideoMuxTiming.verifyMovie(longArrayOf(30000,63333,113333),listOf(30000,63333,113333),m,164444,163333)}}}
    @Test fun delayedMovieChangedPtsStillRejected(){temp{d->val m=iso.read(movie(File(d,"pts.mp4")));reject{VideoMuxTiming.verifyMovie(longArrayOf(30000,64333,113333),listOf(30000,63333,113333),m,163333,163333)}}}
    @Test fun integralDelayHasNoTickAllowance(){val e=VideoMuxTiming.Edit(empty=1,movieScale=1000,segment=100);assertTrue(VideoMuxTiming.delayedHeader(4848,4800,48000,e));assertFalse(VideoMuxTiming.delayedHeader(4849,4800,48000,e));assertFalse(VideoMuxTiming.delayedHeader(4847,4800,48000,e))}
    @Test fun onlyActualRationalClockConversion(){val e=VideoMuxTiming.Edit(empty=1,movieScale=1000,segment=100);assertTrue(VideoMuxTiming.delayedHeader(4454,4410,44100,e));assertTrue(VideoMuxTiming.delayedHeader(4455,4410,44100,e));assertFalse(VideoMuxTiming.delayedHeader(4456,4410,44100,e))}
    @Test fun durationDiagnosticNamesTheTrack(){temp{d->try{iso.read(movie(File(d,"bad.mp4"),declared=14_701));fail("must reject")}catch(e:IllegalArgumentException){for(value in listOf("ID1 vide","90000","mdhd=14701","stts=12000","media_time=0"))assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(value))}}}
    @Test fun zeroDelayCannotExcuseMismatch(){assertFalse(VideoMuxTiming.delayedHeader(4801,4800,48000,VideoMuxTiming.Edit(segment=100,movieScale=1000)))}
    @Test fun unsignedScalesAndLongProducts(){temp{d->assertEquals(12_885_000_000L,iso.read(movie(File(d,"wide.mp4"),scale=4_000_000_000,movieScale=4_000_000_000,durations=listOf(4_000_000_000,4_000_000_000,4_000_000_000),declared=12_885_000_000,empty=885_000_000,segment=12_000_000_000,version=1)).video.duration)}}
}
