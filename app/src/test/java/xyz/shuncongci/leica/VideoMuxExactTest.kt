package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VideoMuxExactTest {
    private val p=longArrayOf(0,16641,33281,49922)
    private val tick=listOf(0L,1498,2996,4494)
    private val iso=VideoIso
    private fun data(f:java.io.DataOutputStream.()->Unit)=iso.bytes(f)
    private fun box(t:String,vararg a:VideoIso.Atom)=iso.atom(t,a.flatMap{it.raw.asIterable()}.toByteArray())
    private fun reject(f:()->Unit){try{f();fail("must reject")}catch(_:IllegalArgumentException){}}
    private inline fun temp(f:(File)->Unit){val d=Files.createTempDirectory("mux-exact-").toFile();try{f(d)}finally{d.deleteRecursively()}}
    private fun nal(i:Int,hevc:Boolean=false)=if(hevc)byteArrayOf(2,1,(40+i).toByte())else byteArrayOf(if(i==0)0x65 else 0x41,(40+i).toByte())
    private fun packet(i:Int,hevc:Boolean=false,annex:Boolean=false):ByteArray {val n=nal(i,hevc);return (if(annex)byteArrayOf(0,0,0,1)else ByteBuffer.allocate(4).putInt(n.size).array())+n}
    private fun seal(pts:LongArray=p,hevc:Boolean=false,order:List<Int> = pts.indices.toList(),sync:Boolean=true):VideoMuxExact.Seal {
        val w=VideoMuxExact.Witness(hevc);for(i in pts.indices){val b=packet(order[i],hevc,true);w.add(ByteBuffer.wrap(b),0,b.size,pts[i],sync && i==0)};return w.finish()
    }
    private fun movie(file:File,hevc:Boolean=false,audio:Boolean=false,ms:Long=10000,reorder:Boolean=false):VideoIso.Movie {
        val ft=iso.atom("ftyp","isom\u0000\u0000\u0000\u0000isom".toByteArray())
        val payload=(0..3).flatMap{packet(it,hevc).asIterable()}.toByteArray()+if(audio)ByteArray(8){90}else byteArrayOf()
        fun track(sound:Boolean):VideoIso.Atom {
            val scale=if(sound)48000 else 90000;val count=if(sound)2 else 4;val stride=if(sound)4 else packet(0,hevc).size
            val dt=if(sound)1024 else 1498;val total=dt*count
            val md=iso.atom("mdhd",data{writeInt(0);writeLong(0);writeInt(scale);writeInt(total);writeInt(0)})
            val tk=iso.atom("tkhd",data{writeInt(7);writeLong(0);writeInt(if(sound)2 else 1);writeInt(0);writeInt(if(sound)500 else 666);write(ByteArray(60))})
            val hd=iso.atom("hdlr",data{writeLong(0);writeBytes(if(sound)"soun" else "vide");write(ByteArray(12))})
            val cfg=if(hevc)ByteArray(23).apply{this[0]=1;this[21]=3}else byteArrayOf(1,0,0,0,3,0,0)
            val entry=iso.atom(if(sound)"mp4a" else if(hevc)"hvc1" else "avc1",ByteArray(if(sound)28 else 78)+(if(sound)byteArrayOf()else iso.atom(if(hevc)"hvcC" else "avcC",cfg).raw))
            val stsd=iso.atom("stsd",data{writeInt(0);writeInt(1);write(entry.raw)})
            val stts=iso.atom("stts",data{writeInt(0);writeInt(1);writeInt(count);writeInt(dt)})
            val stsz=iso.atom("stsz",data{writeInt(0);writeInt(stride);writeInt(count)})
            val stsc=iso.atom("stsc",data{writeInt(0);writeInt(1);writeInt(1);writeInt(count);writeInt(1)})
            val co=iso.atom("stco",data{writeInt(0);writeInt(1);writeInt(ft.raw.size+8+if(sound)(0..3).sumOf{packet(it,hevc).size}else 0)})
            val ss=iso.atom("stss",data{writeInt(0);writeInt(1);writeInt(1)})
            val extras=if(reorder && !sound)arrayOf(iso.atom("ctts",data{writeInt(1 shl 24);writeInt(4);for(n in listOf(0,3000,-1000,0)){writeInt(1);writeInt(n)}}))else emptyArray()
            val tab=box("stbl",stsd,stts,stsz,stsc,co,ss,*extras)
            val ref=iso.atom("dref",data{writeInt(0);writeInt(1);write(iso.atom("url ",data{writeInt(1)}).raw)})
            return box("trak",tk,box("mdia",md,hd,box("minf",box("dinf",ref),tab)))
        }
        val mv=iso.atom("mvhd",data{writeInt(0);writeLong(0);writeInt(ms.toInt());writeInt(666);write(ByteArray(80))})
        file.writeBytes(ft.raw+iso.atom("mdat",payload).raw+box("moov",mv,track(false),*(if(audio)arrayOf(track(true))else emptyArray())).raw)
        return iso.read(file)
    }
    private fun editedAudio(file:File,ms:Long=10000,delay:Long=150,segment:Long=427):VideoIso.Movie {
        val m=movie(file,audio=true,ms=ms)
        val tracks=m.moov.children().map{a->if(a.type!="trak" || a.child("mdia").child("hdlr").body.copyOfRange(8,12).toString(Charsets.ISO_8859_1)!="soun")a else {
            val edit=box("edts",iso.atom("elst",data{writeInt(1 shl 24);writeInt(if(delay>0)2 else 1);if(delay>0){writeLong(delay);writeLong(-1);writeInt(65536)};writeLong(segment);writeLong(0);writeInt(65536)}))
            a.withChildren(a.children().map{if(it.type=="tkhd"){val b=it.body;ByteBuffer.wrap(b).putInt(20,(delay+segment).toInt());iso.atom("tkhd",b)}else it}+edit)
        }}
        val top=m.top.single{it.type=="moov"};file.writeBytes(file.readBytes().copyOfRange(0,top.offset.toInt())+m.moov.withChildren(tracks).raw)
        return iso.read(file)
    }
    private fun check(pts:LongArray=p,end:Long=66563,hevc:Boolean=false,audio:Boolean=false){temp{d->
        val m=movie(File(d,"system.mp4"),hevc,audio);val original=m.file.readBytes();val target=File(d,"exact.mp4")
        VideoMuxExact.write(m,target,pts,end,seal(pts,hevc));val r=iso.read(target)
        val e=VideoMuxTiming.edit(r.moov.child("mvhd").body,r.video.atom.child("edts"))
        val actual=r.video.samples.map{it.pts+e.empty};assertEquals(pts.toList(),actual);assertEquals(1000000L,r.video.scale)
        assertEquals(end,actual.last()+r.video.samples.last().duration);assertArrayEquals(original,m.file.readBytes())
        VideoMuxTiming.verifyMovie(pts,actual,r,end,end)
        for(i in m.video.samples.indices)assertArrayEquals(m.sample(m.video,i),r.sample(r.video,i))
        if(audio){val a=m.tracks.single{it.handler=="soun"};val b=r.tracks.single{it.handler=="soun"};assertArrayEquals(a.mdia.raw,b.mdia.raw);for(i in a.samples.indices)assertArrayEquals(m.sample(a,i),r.sample(b,i))}
    }}
    @Test fun loggedFourthFrameStillRejectedByUnchangedGate(){try{VideoMuxTiming.verify(p,tick.map{it*1000000/90000},tick,90000,66563,66577);fail()}catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("第4帧"));assertTrue(e.message.orEmpty().contains("4494"))}}
    @Test fun aospCoalescingReproducesLogged4494(){var lastUs=0L;var lastDt=0L;var total=0L;val actual=ArrayList<Long>();for(value in p){var us=value;var dt=(us*90000+500000)/1000000-(lastUs*90000+500000)/1000000;if(lastDt!=0L && dt!=lastDt){val delta=((lastDt-dt)*1000000+45000)/90000;if(delta>-100 && delta<100){dt=lastDt;us+=delta}};total+=dt;actual+=total;lastDt=dt;lastUs=us};assertEquals(tick,actual)}
    @Test fun exactAvcPreservesEveryPts(){check()}
    @Test fun exactHevcPreservesEveryPts(){check(hevc=true)}
    @Test fun audioBytesTablesAndMediaClockUntouched(){check(audio=true)}
    @Test fun explicitNonzeroStartNotNormalized(){check(p.map{it+12345}.toLongArray(),78908)}
    @Test fun longTimelineUpgradesHeaders(){check(p.map{it+6000000000}.toLongArray(),6000066563)}
    @Test fun nonuniformFrameDurationsAreNotSmoothed(){check(longArrayOf(0,16641,42000,101123),140007)}
    @Test fun nanoscopicFrameIntervalsRemainExact(){check(longArrayOf(0,1,3,7),8)}
    @Test fun witnessNormalizesOnlyNalLengthPrefixes(){val a=VideoMuxExact.Witness(false);val b=VideoMuxExact.Witness(false);for(i in p.indices){val x=packet(i,annex=true);val y=packet(i);a.add(ByteBuffer.wrap(x),0,x.size,p[i],i==0);b.add(ByteBuffer.wrap(y),0,y.size,p[i],i==0)};assertArrayEquals(a.finish().hash,b.finish().hash)}
    @Test fun bufferOffsetLimitAndPositionRetained(){val x=packet(0,annex=true);val b=ByteBuffer.allocateDirect(x.size+9);b.position(5);b.put(x);b.limit(5+x.size);b.position(5);val w=VideoMuxExact.Witness(false);w.add(b,5,x.size,0,true);assertEquals(5,b.position());assertEquals(5+x.size,b.limit());val a=VideoMuxExact.Witness(false);a.add(ByteBuffer.wrap(x),0,x.size,0,true);assertArrayEquals(a.finish().hash,w.finish().hash)}
    @Test fun sameCountDifferentVideoRejectedBeforeTarget(){temp{d->val m=movie(File(d,"system.mp4"));val f=File(d,"bad.mp4");reject{VideoMuxExact.write(m,f,p,66563,seal(order=listOf(0,1,3,2)))};assertFalse(f.exists())}}
    @Test fun trustedPtsBindingIsNotRefitted(){temp{d->val m=movie(File(d,"system.mp4"));reject{VideoMuxExact.write(m,File(d,"bad.mp4"),p.map{it+1}.toLongArray(),66564,seal())}}}
    @Test fun syncFlagBindingRetained(){temp{d->val m=movie(File(d,"system.mp4"));reject{VideoMuxExact.write(m,File(d,"bad.mp4"),p,66563,seal(sync=false))}}}
    @Test fun droppedFrameCannotBeRetimed(){temp{d->val m=movie(File(d,"system.mp4"));reject{VideoMuxExact.write(m,File(d,"bad.mp4"),p.dropLast(1).toLongArray(),66563,seal())}}}
    @Test fun duplicatePtsCannotBeRetimed(){temp{d->val m=movie(File(d,"system.mp4"));reject{VideoMuxExact.write(m,File(d,"bad.mp4"),longArrayOf(0,16641,16641,49922),66563,seal())}}}
    @Test fun reorderedSystemSamplesRejected(){temp{d->val m=movie(File(d,"system.mp4"),reorder=true);reject{VideoMuxExact.write(m,File(d,"bad.mp4"),p,66563,seal())}}}
    @Test fun zeroLastDurationRejected(){temp{d->val m=movie(File(d,"system.mp4"));reject{VideoMuxExact.write(m,File(d,"bad.mp4"),p,p.last(),seal())}}}
    @Test fun cannotOverwriteExistingFile(){temp{d->val m=movie(File(d,"system.mp4"));val f=File(d,"existing.mp4");f.writeText("keep");reject{VideoMuxExact.write(m,f,p,66563,seal())};assertEquals("keep",f.readText())}}
    @Test fun cannotOverwriteInput(){temp{d->val m=movie(File(d,"system.mp4"));val b=m.file.readBytes();reject{VideoMuxExact.write(m,m.file,p,66563,seal())};assertArrayEquals(b,m.file.readBytes())}}
    @Test fun cancellationDuringCopyRemovesOnlyPartial(){temp{d->val m=movie(File(d,"system.mp4"));val b=m.file.readBytes();val f=File(d,"partial.mp4");var calls=0;try{VideoMuxExact.write(m,f,p,66563,seal()){if(f.exists())throw InterruptedException("cancel");calls++};fail()}catch(_:InterruptedException){};assertTrue(calls>0);assertFalse(f.exists());assertArrayEquals(b,m.file.readBytes())}}
    @Test fun nonintegralAudioMovieClockCannotBeRounded(){temp{d->val m=movie(File(d,"system.mp4"),audio=true,ms=90000);val f=File(d,"bad.mp4");reject{VideoMuxExact.write(m,f,p,66563,seal())};assertFalse(f.exists())}}
    @Test fun witnessCannotBeUsedAfterSealing(){val w=VideoMuxExact.Witness(false);val b=packet(0);w.add(ByteBuffer.wrap(b),0,b.size,0,true);w.finish();reject{w.finish()};reject{w.add(ByteBuffer.wrap(b),0,b.size,1,true)}}
    @Test fun emptyWitnessRejected(){reject{VideoMuxExact.Witness(false).finish()}}
    @Test fun witnessDoesNotAcceptRepeatedPts(){val w=VideoMuxExact.Witness(false);val b=packet(0);w.add(ByteBuffer.wrap(b),0,b.size,0,true);reject{w.add(ByteBuffer.wrap(b),0,b.size,0,true)}}
    @Test fun malformedNalLengthRejected(){val w=VideoMuxExact.Witness(false);reject{w.add(ByteBuffer.wrap(byteArrayOf(0,0,0,10,0x65)),0,5,0,true)}}
    @Test fun noPictureNalRejected(){val w=VideoMuxExact.Witness(false);reject{w.add(ByteBuffer.wrap(byteArrayOf(0,0,0,1,6,4)),0,6,0,true)}}
    @Test fun dolbyEnhancementRejected(){val w=VideoMuxExact.Witness(true);reject{w.add(ByteBuffer.wrap(byteArrayOf(0,0,0,1,124,1)),0,6,0,true)}}
    @Test fun completeLengthParsingNotThreeBytePrefixGuess(){for(hevc in listOf(false,true)){
        val n=ByteArray(348){7}.apply{this[0]=if(hevc)2 else 0x41;if(hevc)this[1]=1}
        val a=VideoMuxExact.Witness(hevc);val b=VideoMuxExact.Witness(hevc)
        val length=ByteBuffer.allocate(4).putInt(n.size).array()+n;val annex=byteArrayOf(0,0,0,1)+n
        a.add(ByteBuffer.wrap(length),0,length.size,0,false);b.add(ByteBuffer.wrap(annex),0,annex.size,0,false)
        assertArrayEquals(a.finish().hash,b.finish().hash)
    }}
    @Test fun trulyAmbiguousFramingRefusesToGuess(){val n=ByteArray(357){7}.apply{this[0]=0x41};val b=ByteBuffer.allocate(4).putInt(n.size).array()+n;reject{VideoMuxExact.Witness(false).add(ByteBuffer.wrap(b),0,b.size,0,false)}}
    @Test fun audioExplicitDelayedEditHasExactlyEqualRationalTimes(){temp{d->
        val m=editedAudio(File(d,"system.mp4"));val f=File(d,"exact.mp4");val a=m.tracks.single{it.handler=="soun"}
        val before=VideoMuxTiming.edit(m.moov.child("mvhd").body,a.atom.child("edts"))
        VideoMuxExact.write(m,f,p,66563,seal());val r=iso.read(f);val b=r.tracks.single{it.handler=="soun"};val after=VideoMuxTiming.edit(r.moov.child("mvhd").body,b.atom.child("edts"))
        assertEquals(before.empty*100L,after.empty);assertEquals(before.segment!!*100L,after.segment);assertEquals(before.mediaTime,after.mediaTime);assertArrayEquals(a.mdia.raw,b.mdia.raw)
        assertEquals(15000L,after.empty);assertEquals(42700L,after.segment)
    }}
    @Test fun audioEditNonintegralMicrosecondNotRoundedEvenWhenHeaderIntegral(){temp{d->
        val m=editedAudio(File(d,"system.mp4"),ms=90000,delay=660,segment=3840);val f=File(d,"bad.mp4")
        reject{VideoMuxExact.write(m,f,p,66563,seal())};assertFalse(f.exists())
    }}
    @Test fun changedCompressedSampleFailsWitnessWithoutChangingSource(){temp{d->
        val m=movie(File(d,"system.mp4"));val s=m.video.samples[2];val bytes=m.file.readBytes();bytes[(s.offset+s.size-1).toInt()]=99;m.file.writeBytes(bytes)
        val f=File(d,"bad.mp4");reject{VideoMuxExact.write(iso.read(m.file),f,p,66563,seal())};assertFalse(f.exists());assertArrayEquals(bytes,m.file.readBytes())
    }}
    @Test fun singleFrameKeepsItsEntireExactDuration(){temp{d->
        val m=movie(File(d,"system.mp4"));val one=longArrayOf(12345);val t=m.video
        val table=t.stbl.withChildren(t.stbl.children().map{a->when(a.type){
            "stts"->iso.atom("stts",data{writeInt(0);writeInt(1);writeInt(1);writeInt(1498)})
            "stsz"->iso.atom("stsz",data{writeInt(0);writeInt(packet(0).size);writeInt(1)})
            "stsc"->iso.atom("stsc",data{writeInt(0);writeInt(1);writeInt(1);writeInt(1);writeInt(1)})
            else->a
        }})
        val track=t.replaceTable(table);val fixed=track.withChildren(track.children().map{a->if(a.type!="mdia")a else a.withChildren(a.children().map{if(it.type!="mdhd")it else iso.atom("mdhd",it.body.apply{ByteBuffer.wrap(this).putInt(16,1498)})})})
        val moov=m.moov.withChildren(m.moov.children().map{if(it.type=="trak")fixed else it});val top=m.top.single{it.type=="moov"}
        m.file.writeBytes(m.file.readBytes().copyOfRange(0,top.offset.toInt())+moov.raw)
        val f=File(d,"exact.mp4");VideoMuxExact.write(iso.read(m.file),f,one,28986,seal(one));val r=iso.read(f)
        assertEquals(1,r.video.samples.size);assertEquals(16641L,r.video.samples.single().duration)
        val e=VideoMuxTiming.edit(r.moov.child("mvhd").body,r.video.atom.child("edts"));assertEquals(12345L,e.empty)
    }}
    @Test fun nalBoundaryAndSeiBytesArePartOfWitness(){val a=VideoMuxExact.Witness(false);val b=VideoMuxExact.Witness(false);val x=packet(0,annex=true)+byteArrayOf(0,0,0,1,6,4);val y=x.copyOf().apply{this[lastIndex]=5};a.add(ByteBuffer.wrap(x),0,x.size,0,true);b.add(ByteBuffer.wrap(y),0,y.size,0,true);assertFalse(a.finish().hash.contentEquals(b.finish().hash))}
}
