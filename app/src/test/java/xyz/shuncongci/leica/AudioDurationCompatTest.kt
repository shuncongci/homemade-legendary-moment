package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Independently constructed numeric reproduction of the reported audio track.
 * Payloads here are sentinels, not playable media. Playable AAC is tested separately. */
internal object AudioDurationCompatFixture {
    private val iso=VideoIso
    val points=longArrayOf(0,16641,33281,49922)
    private fun data(f:java.io.DataOutputStream.()->Unit)=iso.bytes(f)
    private fun box(t:String,vararg a:VideoIso.Atom)=iso.atom(t,a.flatMap{it.raw.asIterable()}.toByteArray())
    fun packet(i:Int)=byteArrayOf(0,0,0,2,if(i==0)0x65 else 0x41,(40+i).toByte())
    fun witness():VideoMuxExact.Seal {
        val w=VideoMuxExact.Witness(false)
        for(i in points.indices){val b=packet(i);w.add(ByteBuffer.wrap(b),0,b.size,points[i],i==0)}
        return w.finish()
    }
    fun movie(file:File,scale:Long=48000,declared:Long=48406,movieScale:Long=1000000,
        empty:Long=5800,segment:Long=1002700,media:Long=0,version:Int=0,
        audioDurations:List<Int> = List(47){1024},rate:Int=65536,withEdit:Boolean=true,
        badOffset:Boolean=false,badCount:Boolean=false,encrypted:Boolean=false,extraEdit:Boolean=false,videoDeclared:Long=5992):File {
        val ft=iso.atom("ftyp","isom\u0000\u0000\u0000\u0000isom".toByteArray())
        val sound=ByteArray(audioDurations.size*4){(it%101).toByte()}
        val video=(0..3).flatMap{packet(it).asIterable()}.toByteArray()
        fun track(audio:Boolean):VideoIso.Atom {
            val ds=if(audio)audioDurations else List(4){1498};val clock=if(audio)scale else 90000L
            val duration=if(audio)declared else videoDeclared;val stride=if(audio)4 else 6
            val md=iso.atom("mdhd",data{writeInt(version shl 24);if(version==1){writeLong(0);writeLong(0);writeInt(clock.toInt());writeLong(duration)}else{writeLong(0);writeInt(clock.toInt());writeInt(duration.toInt())};writeInt(0)})
            val tk=iso.atom("tkhd",data{writeInt(7);writeLong(0);writeInt(if(audio)1 else 2);writeInt(0);writeInt(if(audio)(empty+segment).toInt()else 66600);write(ByteArray(60))})
            val hd=iso.atom("hdlr",data{writeLong(0);writeBytes(if(audio)"soun" else "vide");write(ByteArray(12))})
            val entry=iso.atom(if(audio)if(encrypted)"enca" else "mp4a" else "avc1",ByteArray(if(audio)28 else 78)+(if(audio)byteArrayOf()else iso.atom("avcC",byteArrayOf(1,0,0,0,3,0,0)).raw))
            val stsd=iso.atom("stsd",data{writeInt(0);writeInt(1);write(entry.raw)})
            val stts=iso.atom("stts",data{writeInt(0);writeInt(ds.size);for((i,dt)in ds.withIndex()){writeInt(if(audio && badCount && i==0)2 else 1);writeInt(dt)}})
            val stsz=iso.atom("stsz",data{writeInt(0);writeInt(stride);writeInt(ds.size)})
            val stsc=iso.atom("stsc",data{writeInt(0);writeInt(1);writeInt(1);writeInt(ds.size);writeInt(1)})
            val stco=iso.atom("stco",data{writeInt(0);writeInt(1);writeInt(if(audio && badOffset)999999 else ft.raw.size+8+if(audio)0 else sound.size)})
            val ss=iso.atom("stss",data{writeInt(0);writeInt(1);writeInt(1)})
            val table=box("stbl",stsd,stts,stsz,stsc,stco,ss)
            val dref=iso.atom("dref",data{writeInt(0);writeInt(1);write(iso.atom("url ",data{writeInt(1)}).raw)})
            val edit=if(audio && withEdit)arrayOf(box("edts",iso.atom("elst",data{
                writeInt(version shl 24);writeInt((if(empty>0)2 else 1)+(if(extraEdit)1 else 0))
                fun row(d:Long,t:Long){if(version==1){writeLong(d);writeLong(t)}else{writeInt(d.toInt());writeInt(t.toInt())};writeInt(rate)}
                if(empty>0)row(empty,-1);row(segment,media);if(extraEdit)row(1,0)
            })))else emptyArray()
            return box("trak",tk,box("mdia",md,hd,box("minf",box("dinf",dref),table)),*edit)
        }
        val mv=iso.atom("mvhd",data{writeInt(0);writeLong(0);writeInt(movieScale.toInt());writeInt(1008500);write(ByteArray(80))})
        file.writeBytes(ft.raw+iso.atom("mdat",sound+video).raw+box("moov",mv,track(true),track(false)).raw)
        return file
    }
}

class AudioDurationCompatTest {
    private inline fun temp(f:(File)->Unit){val d=Files.createTempDirectory("audio-duration-compat-").toFile();try{f(d)}finally{d.deleteRecursively()}}
    @Test fun oldStrictReaderReproducesReportedAudioFailure(){temp{d->
        val file=AudioDurationCompatFixture.movie(File(d,"reported.mp4"))
        try{VideoIso.read(file);fail("strict legacy diagnostic must reproduce")}catch(e:IllegalArgumentException){
            for(s in listOf("ID1 soun","时间基48000","mdhd=48406","stts=48128","显示=48128","47样本","首PTS=0","末PTS=47104","空段5800+活动段1002700@1000000,media_time=0"))assertTrue(e.message,e.message.orEmpty().contains(s))
        }
    }}
    @Test fun numericLogHasBothHeaderDelayAndActiveRounding(){
        assertEquals(278,48406-48128)
        assertEquals(278.4,5800.0*48000/1000000,1e-10)
        assertEquals(33.3333333333,1002700.0-48128.0*1000000/48000,1e-6)
        assertFalse(VideoMuxTiming.delayedHeader(48406,48128,48000,VideoMuxTiming.Edit(empty=5800,movieScale=1000000,segment=1002700)))
    }
    private fun reject(f:()->Unit){try{f();fail("damaged/ambiguous media must reject")}catch(_:IllegalArgumentException){}}
    private fun read(d:File,declared:Long=48406,scale:Long=48000,empty:Long=5800,segment:Long=1002700,media:Long=0,version:Int=0,withEdit:Boolean=true)=
        VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"audio.mp4"),declared=declared,scale=scale,empty=empty,segment=segment,media=media,version=version,withEdit=withEdit))
    private fun audio(m:VideoIso.Movie)=m.tracks.single{it.handler=="soun"}
    private fun retained(m:VideoIso.Movie){
        val a=audio(m);assertEquals(47,a.samples.size);assertEquals(48128L,a.mediaDuration)
        assertEquals((0 until 47).map{it*1024L},a.samples.map{it.pts});assertTrue(a.samples.all{it.duration==1024L})
    }
    @Test fun reportedHeaderNowWarnsAndContinues(){temp{d->val m=read(d);retained(m);assertEquals(48406L,audio(m).duration);assertEquals(1,m.warnings.size);assertTrue(m.warnings.single().contains("已兼容继续"));assertTrue(m.warnings.single().contains("ID1 soun"))}}
    @Test fun noArbitraryMillisecondTolerance(){temp{d->val m=read(d,declared=2000000000);retained(m);assertEquals(1,m.warnings.size)}}
    @Test fun smallerHeaderDoesNotTruncatePackets(){temp{d->val m=read(d,declared=1);retained(m);assertEquals(1,m.warnings.size)}}
    @Test fun zeroAudioHeaderUsesCompleteTables(){temp{d->val m=read(d,declared=0);retained(m);assertEquals(1,m.warnings.size)}}
    @Test fun noEditDoesNotRequireGuessingADelay(){temp{d->val m=read(d,withEdit=false);retained(m);assertEquals(1,m.warnings.size);assertTrue(audio(m).atom.children().none{it.type=="edts"})}}
    @Test fun ordinaryHeaderAddsNoWarning(){temp{d->val m=read(d,declared=48128);retained(m);assertTrue(m.warnings.isEmpty())}}
    @Test fun versionOneAudioHeader(){temp{d->val m=read(d,declared=5000000000,version=1);retained(m);assertEquals(5000000000L,audio(m).duration)}}
    @Test fun clock44100Kept(){temp{d->val m=read(d,scale=44100);retained(m);assertEquals(44100L,audio(m).scale)}}
    @Test fun clock96000Kept(){temp{d->val m=read(d,scale=96000);retained(m);assertEquals(96000L,audio(m).scale)}}
    @Test fun validPrimingTrimIsNotReset(){temp{d->val m=read(d,media=1024);retained(m);assertEquals(1024L,VideoMuxTiming.edit(m.moov.child("mvhd").body,audio(m).atom.child("edts")).mediaTime)}}
    @Test fun shorterActiveEditPreservedNotExpanded(){temp{d->val m=read(d,segment=950000);retained(m);assertEquals(950000L,VideoMuxTiming.edit(m.moov.child("mvhd").body,audio(m).atom.child("edts")).segment)}}
    @Test fun longerActiveEditNotConvertedIntoFakeSamples(){temp{d->val m=read(d,segment=1500000);retained(m);assertEquals(1500000L,VideoMuxTiming.edit(m.moov.child("mvhd").body,audio(m).atom.child("edts")).segment)}}
    @Test fun zeroOffsetSingleSegmentPreserved(){temp{d->val m=read(d,empty=0);retained(m);assertEquals(0L,VideoMuxTiming.edit(m.moov.child("mvhd").body,audio(m).atom.child("edts")).empty)}}
    @Test fun invalidSpeedStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),rate=131072))}}}
    @Test fun ambiguousMultipleEditsStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),extraEdit=true))}}}
    @Test fun corruptSampleCountStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),badCount=true))}}}
    @Test fun outOfBoundsSampleStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),badOffset=true))}}}
    @Test fun encryptedAudioStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),encrypted=true))}}}
    @Test fun zeroInteriorSampleDurationStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),audioDurations=List(47){if(it==20)0 else 1024}))}}}
    @Test fun zeroTerminalSampleDurationStillRejected(){temp{d->reject{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"bad.mp4"),audioDurations=List(47){if(it==46)0 else 1024}))}}}
    @Test fun invalidClockStillRejected(){temp{d->reject{read(d,scale=0)}}}
    @Test fun cancellationNotConvertedToWarning(){temp{d->val f=AudioDurationCompatFixture.movie(File(d,"cancel.mp4"));try{VideoIso.readCompatible(f){throw java.util.concurrent.CancellationException("stop")};fail()}catch(e:java.util.concurrent.CancellationException){assertEquals("stop",e.message)}}}
    @Test fun readerDoesNotModifyInput(){temp{d->val f=AudioDurationCompatFixture.movie(File(d,"original.mp4"));val before=f.readBytes();VideoIso.readCompatible(f);assertArrayEquals(before,f.readBytes())}}
    private fun exact(d:File,media:Long=0,declared:Long=48406,withEdit:Boolean=true){
        val f=AudioDurationCompatFixture.movie(File(d,"system.mp4"),media=media,declared=declared,withEdit=withEdit)
        val original=f.readBytes();val m=VideoIso.readCompatible(f);val out=File(d,"exact.mp4")
        VideoMuxExact.write(m,out,AudioDurationCompatFixture.points,66563,AudioDurationCompatFixture.witness())
        val r=VideoIso.readCompatible(out);val a=audio(m);val b=audio(r);retained(r)
        assertArrayEquals(a.mdia.raw,b.mdia.raw);assertEquals(a.samples,b.samples)
        for(i in a.samples.indices)assertArrayEquals(m.sample(a,i),r.sample(b,i))
        val e=a.atom.children().singleOrNull{it.type=="edts"};val e2=b.atom.children().singleOrNull{it.type=="edts"}
        assertEquals(VideoMuxTiming.edit(m.moov.child("mvhd").body,e),VideoMuxTiming.edit(r.moov.child("mvhd").body,e2))
        assertArrayEquals(original,f.readBytes());assertEquals(AudioDurationCompatFixture.points.toList(),r.video.samples.map{it.pts})
        VideoMuxTiming.verifyMovie(AudioDurationCompatFixture.points,r.video.samples.map{it.pts},r,66563,66563)
        val before=out.readBytes();reject{VideoMuxExact.write(m,out,AudioDurationCompatFixture.points,66563,AudioDurationCompatFixture.witness())};assertArrayEquals(before,out.readBytes())
    }
    @Test fun exactMuxIntegratesReportedAudioAndWarns(){temp{d->exact(d)}}
    @Test fun exactMuxRetainsAudioPrimingTrim(){temp{d->exact(d,media=1024)}}
    @Test fun exactMuxRetainsZeroAudioHeader(){temp{d->exact(d,declared=0)}}
    @Test fun exactMuxRetainsAudioWithoutEdit(){temp{d->exact(d,withEdit=false)}}
    @Test fun compatibilityWarningStillAllowsExportSummary(){temp{d->val m=read(d);val metadata=VideoMetadata();repeat(3){m.warnings.forEach(metadata::warn)};assertEquals(1,metadata.warnings.size);assertTrue(metadata.summary(false).contains("可导出"));assertTrue(metadata.summary(true).startsWith("视频已导出"))}}
    @Test fun videoHeaderMismatchStillRejectedByCompatibleReader(){temp{d->
        try{VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"video-bad.mp4"),videoDeclared=1));fail()}
        catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("vide"))}
    }}
    @Test fun strictReaderStillRejectsZeroAudioHeader(){temp{d->reject{VideoIso.read(AudioDurationCompatFixture.movie(File(d,"zero-strict.mp4"),declared=0))}}}
    @Test fun changedAudioPayloadStillFailsFinalContainerVerification(){temp{d->
        val f=AudioDurationCompatFixture.movie(File(d,"source.mp4"));val source=VideoIso.readCompatible(f)
        val changed=File(d,"payload-changed.mp4");f.copyTo(changed)
        java.io.RandomAccessFile(changed,"rw").use{it.seek(audio(source).samples[3].offset);it.writeByte(255)}
        reject{VideoIso.verify(source,VideoIso.readCompatible(changed))}
    }}
    @Test fun changedAudioTimingStillFailsFinalContainerVerification(){temp{d->
        val source=VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"source.mp4")))
        val changed=VideoIso.readCompatible(AudioDurationCompatFixture.movie(File(d,"timing-changed.mp4"),audioDurations=List(47){if(it==20)1023 else 1024}))
        reject{VideoIso.verify(source,changed)}
    }}
    @Test fun changedVideoPtsStillRejected(){temp{d->val m=read(d);reject{VideoMuxTiming.verifyMovie(longArrayOf(0,16641,33281,49922),listOf(0,16644,33288,49933),m,66563,66577)}}}
}
