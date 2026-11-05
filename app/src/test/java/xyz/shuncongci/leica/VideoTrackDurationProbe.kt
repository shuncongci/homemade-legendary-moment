package xyz.shuncongci.leica

import java.io.File

/** Only synthetic local MP4s supplied by the verification script. */
object VideoTrackDurationProbe {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==3)
        val source=VideoIso.read(File(args[0]));val target=File(args[1]);val rows=File(args[2]).readLines().map{it.split(',').map(String::toLong)}
        val end=rows.first();val points=rows.drop(1).map{it.single()}
        VideoMuxTiming.verifyMovie(points.toLongArray(),points,source,end[0],end[1])
        VideoIso.rewrite(source,source,target)
        val result=VideoIso.read(target);VideoIso.verify(source,result)
        if(source.video.samples.first().pts==0L && source.video.stbl.children().none{it.type=="ctts"})
            check(result.video.stbl.children().none{it.type=="ctts"})
        VideoMuxTiming.verifyMovie(points.toLongArray(),points,result,end[0],end[1])
        for((a,b) in source.tracks.zip(result.tracks)){
            check(a.samples.size==b.samples.size && a.mediaDuration==b.mediaDuration)
            check(a.samples.map{it.pts}==b.samples.map{it.pts})
            check(a.samples.map{it.duration}==b.samples.map{it.duration})
            for(i in a.samples.indices)check(source.sample(a,i).contentEquals(result.sample(b,i)))
            println("PASS handler=${a.handler} scale=${a.scale} header=${a.duration} media=${a.mediaDuration} samples=${a.samples.size}")
        }
    }
}
