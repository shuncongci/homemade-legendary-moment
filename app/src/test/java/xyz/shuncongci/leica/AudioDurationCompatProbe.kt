package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer

/** Called only with generated synthetic local media by verify_audio_duration_compat.py. */
object AudioDurationCompatProbe {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==4)
        val input=File(args[0]);val exact=File(args[1]);val migrated=File(args[2])
        val rows=File(args[3]).readLines().map{it.toLong()};val end=rows.first();val pts=rows.drop(1).toLongArray()
        val movie=VideoIso.readCompatible(input)
        check(movie.warnings.size==1 && movie.warnings.single().contains("soun"))
        try{VideoIso.read(input);error("legacy strict audio rejection expected")}catch(e:IllegalArgumentException){
            // Zero mdhd is rejected by the older positive-duration requirement before
            // its detailed discrepancy diagnostic. Nonzero cases must name soun.
            check(if(movie.tracks.single{it.handler=="soun"}.duration==0L)e.message=="Failed requirement." else e.message.orEmpty().contains("soun"))
        }
        val hevc=movie.video.entries.single().type in setOf("hvc1","hev1")
        val w=VideoMuxExact.Witness(hevc)
        for(i in movie.video.samples.indices){val sample=movie.video.samples[i];val b=movie.sample(movie.video,i);w.add(ByteBuffer.wrap(b),0,b.size,pts[i],sample.sync)}
        VideoMuxExact.write(movie,exact,pts,end,w.finish())
        val result=VideoIso.readCompatible(exact)
        VideoMuxTiming.verifyMovie(pts,pts.toList(),result,end,end)
        val a=movie.tracks.single{it.handler=="soun"};val b=result.tracks.single{it.handler=="soun"}
        check(a.mdia.raw.contentEquals(b.mdia.raw) && a.samples==b.samples)
        for(i in a.samples.indices)check(movie.sample(a,i).contentEquals(result.sample(b,i)))
        VideoIso.rewrite(movie,result,migrated)
        VideoIso.verify(movie,VideoIso.readCompatible(migrated))
        val metadata=VideoMetadata();movie.warnings.forEach(metadata::warn)
        check(metadata.summary(true).startsWith("视频已导出"))
        println("AUDIO_HEADER_COMPAT_EXACT_AND_METADATA_REWRITE_OK ${a.samples.size} samples ${a.scale} Hz")
        println(movie.warnings.single())
    }
}
