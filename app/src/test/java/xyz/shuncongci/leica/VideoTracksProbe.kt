package xyz.shuncongci.leica

import java.io.File

/** Header selection + safe descriptive transfer with real synthetic ISO files.
 * Track descriptors below are a declared stand-in, NOT Android MediaExtractor results. */
object VideoTracksProbe {
    @JvmStatic fun main(args:Array<String>){
        val src=File(args[0]);val enc=File(args[1]);val out=File(args[2]);val selected=args[3].toInt()
        val headers=VideoTracks.headers(src);check(headers.size==2);check(headers.none{it.unsafe})
        val tracks=headers.mapIndexed{i,h->VideoTracks.Track(i,h.id,if(h.codecs.all{it in setOf("hvc1","hev1")})"video/hevc"else "video/avc",64,48,720000,-1)}
        val inventory=VideoTracks.Inventory(tracks,headers)
        try{inventory.choose();error("multi video must require a choice")}catch(e:IllegalArgumentException){check(e.message.orEmpty().contains("请先"))}
        val chosen=inventory.choose(selected);check(chosen.index==selected && chosen.rotation==if(selected==0)0 else 90)
        val before=VideoIso.read(enc);val metadata=VideoMetadata();VideoIso.salvage(src,enc,out,metadata)
        val after=VideoIso.read(out)
        // Salvage intentionally changes global descriptive fields. Its stronger media
        // invariant is exact original track atoms (including every sample table/CSD).
        check(before.tracks.size==after.tracks.size)
        before.tracks.zip(after.tracks).forEach{(a,b)->
            check(a.atom.raw.contentEquals(b.atom.raw))
            a.samples.indices.forEach{i->check(before.sample(a,i).contentEquals(after.sample(b,i)))}
        }
        check(String(after.moov.raw,Charsets.ISO_8859_1).contains("Synthetic independent views"))
        check(VideoTracks.headers(out).single().rotation==chosen.rotation)
        check(after.tracks.size==2 && after.tracks.count{it.handler=="vide"}==1 && after.tracks.count{it.handler=="soun"}==1)
        println("PASS selected=$selected id=${chosen.id} rotation=${chosen.rotation}; source views=2 output views=1; source bytes untouched")
    }
}
