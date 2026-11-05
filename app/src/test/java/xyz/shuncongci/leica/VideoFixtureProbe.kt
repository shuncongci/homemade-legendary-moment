package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer

/** Only called explicitly with the local generated-fixture folder. No device or private media. */
object VideoFixtureProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 1..2);val dir=File(args[0]);val tag=args.getOrElse(1){"rewritten"};require(tag.matches(Regex("[a-z0-9-]+")));var checks=0
        for(name in listOf("sdr","pq","hlg")){
            val source=VideoIso.read(File(dir,"$name-source.mp4"));val encoded=VideoIso.read(File(dir,"$name-encoded.mp4"))
            for(movie in listOf(source,encoded)){
                val t=movie.video;val hevc=name!="sdr";val config=VideoIso.videoDescription(t).children(78).single{it.type==if(hevc)"hvcC" else "avcC"}.body
                val csd=VideoBitstream.record(config,hevc);val signal=VideoBitstream.config(csd,hevc)
                check(signal.width==64 && signal.height==48 && signal.bits==if(hevc)10 else 8);checks++
                check(VideoIso.color(t).transfer==when(name){"pq"->16;"hlg"->18;else->1});checks++
                for(i in t.samples.indices){val b=movie.sample(t,i);VideoBitstream.sample(ByteBuffer.wrap(b),b.size,hevc,csd);checks++}
            }
            val out=File(dir,"$name-$tag.mp4");check(!out.exists());VideoIso.rewrite(source,encoded,out);val result=VideoIso.read(out);VideoIso.verify(source,result);checks++
            check(encoded.video.samples.map{it.pts}.zipWithNext().all{it.second>it.first});checks++
            for(i in result.video.samples.indices){check(result.sample(result.video,i).contentEquals(encoded.sample(encoded.video,i)));checks++}
            println("$name: synthetic SPS, VFR, B-frame source metadata rewrite and encoded sample bytes PASS")
        }
        println("FIXTURE CHECKS=$checks; not Android MediaCodec/device validation")
    }
}
