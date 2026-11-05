package xyz.shuncongci.leica

import java.io.File

/** Generated MP4 and independent FFmpeg packet-timing TSV only, never user media. */
object VideoMuxTimingProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2)
        val rows=File(args[1]).readLines().filter{it.isNotBlank()}.map{it.split('\t').map(String::toLong)}
        val end=rows.first();val frames=rows.drop(1)
        val expected=frames.map{it[0]}.toLongArray();val actual=frames.map{it[1]}
        var oldRejected=false
        try{VideoContract.verifyFrames(expected,actual)}catch(_:IllegalArgumentException){oldRejected=true}
        VideoMuxTiming.verifyMovie(expected,actual,VideoIso.read(File(args[0])),end[0],end[1])
        println("PASS frames=${frames.size} oldExactMicrosecondsRejected=$oldRejected maxUsDifference=${frames.maxOf{kotlin.math.abs(it[0]-it[1])}}")
    }
}
