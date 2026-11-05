package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Explicit generated PQ/HLG test files only. Descriptors simulate OS aliases and
 * RPU payloads are inert sentinels, NOT real Dolby metadata or Android codec tests. */
object VideoDolbyProbe {
    private fun writeMovie(m:VideoIso.Movie,file:File,samples:List<ByteArray>,entry:VideoIso.Atom,brand:String,descriptions:Boolean=true) {
        check(!file.exists())
        val ftyp=VideoIso.atom("ftyp",VideoIso.bytes{writeBytes(brand);writeInt(0);writeBytes("isom");writeBytes(if(brand=="isom")"hvc1"else "dvhe")})
        var offset=ftyp.raw.size+8;val data=java.io.ByteArrayOutputStream()
        val tracks=m.tracks.map{t->
            val bytes=if(t.handler=="vide")samples else t.samples.indices.map{m.sample(t,it)}
            val start=offset;bytes.forEach{data.write(it);offset+=it.size}
            val replacements=mapOf(
                "stsd" to if(t.handler=="vide")VideoIso.atom("stsd",VideoIso.bytes{writeInt(0);writeInt(1);write(entry.raw)})else t.stsd,
                "stsz" to VideoIso.atom("stsz",VideoIso.bytes{writeInt(0);writeInt(0);writeInt(bytes.size);bytes.forEach{writeInt(it.size)}}),
                "stsc" to VideoIso.atom("stsc",VideoIso.bytes{writeInt(0);writeInt(1);writeInt(1);writeInt(bytes.size);writeInt(1)}),
                "stco" to VideoIso.atom("stco",VideoIso.bytes{writeInt(0);writeInt(1);writeInt(start)})
            )
            val table=t.stbl.withChildren(t.stbl.children().filter{it.type !in setOf("co64","stco","stsc","stsz","stsd") }+replacements.values)
            t.replaceTable(table)
        }
        var index=0
        val moov=m.moov.withChildren(m.moov.children().filter{descriptions || it.type !in setOf("udta","meta")}.map{if(it.type=="trak")tracks[index++]else it})
        file.writeBytes(ftyp.raw+VideoIso.atom("mdat",data.toByteArray()).raw+moov.raw)
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==3);val src=File(args[0]);val dir=File(args[1]);val kind=args[2];require(kind in setOf("pq","hlg") && dir.isDirectory)
        val originalHash=MessageDigest.getInstance("SHA-256").digest(src.readBytes())
        val m=VideoIso.read(src);val t=m.video;val entry=t.entries.single();val hvcc=entry.children(78).single{it.type=="hvcC"}.body
        val cfg=VideoDolbyFixture.cfg(id=if(kind=="pq")1 else 4)
        val sourceEntry=VideoIso.atom("dvhe",entry.body+VideoIso.atom("dvvC",cfg).raw)
        val rpu=VideoDolbyFixture.framed(VideoDolbyFixture.nal(62,"SYNTHETIC_NOT_REAL_RPU".toByteArray()),annex=false)
        val packed=t.samples.indices.map{m.sample(t,it)+rpu}
        val synthetic=File(dir,"$kind-synthetic-dolby.mp4");writeMovie(m,synthetic,packed,sourceEntry,"dvh1")
        val h=VideoTracks.headers(synthetic).single();check(!h.unsafe && h.dolby!=null)
        val a=VideoTracks.Track(0,h.id,"video/dolby-vision",64,48,720000,-1)
        val inventory=VideoTracks.Inventory(listOf(a,a.copy(index=1,mime="video/hevc")),listOf(h))
        check(inventory.choose().index==1 && inventory.processingVideos.size==1)
        val base=inventory.dolbyBase!!;check(VideoDolby.color(base,64,48).transfer==if(kind=="pq")16 else 18)
        VideoDolby.requireSameConfig(VideoBitstream.record(hvcc,true),base.csd)
        val input=VideoIso.read(synthetic);val cleaned=input.video.samples.indices.map{i->
            val b=ByteBuffer.wrap(input.sample(input.video,i));val n=VideoDolby.cleanSample(b,b.capacity(),base.csd)
            ByteArray(n).also{b.position(0);b.get(it);VideoBitstream.sample(ByteBuffer.wrap(it),it.size,true,base.csd)}
        }
        val c=hvcc.copyOfRange(0,23);val units=VideoCodecConfig.annex(base.csd);c[22]=units.size.toByte()
        val cleanHvcc=c+VideoIso.bytes{for(n in units){writeByte(128 or ((n[0].toInt() ushr 1) and 63));writeShort(1);writeShort(n.size);write(n)}}
        val plainEntry=VideoIso.atom("hvc1",entry.body.copyOfRange(0,78)+entry.children(78).filter{it.type!="hvcC"}.flatMap{it.raw.asIterable()}.toByteArray()+VideoIso.atom("hvcC",cleanHvcc).raw)
        val plain=File(dir,"$kind-clean-hevc.mp4");writeMovie(input,plain,cleaned,plainEntry,"isom",descriptions=false);VideoDolby.verifyOutput(plain)
        val out=VideoIso.read(plain)
        check(input.tracks.size==out.tracks.size)
        for(i in input.tracks.indices){val x=input.tracks[i];val y=out.tracks[i];check(x.samples.size==y.samples.size && x.scale==y.scale && x.duration==y.duration);for(k in x.samples.indices){check(x.samples[k].pts==y.samples[k].pts && x.samples[k].duration==y.samples[k].duration);if(x.handler=="soun")check(input.sample(x,k).contentEquals(out.sample(y,k)))}}
        val described=File(dir,"$kind-described.mp4");VideoIso.salvage(synthetic,plain,described,VideoMetadata());VideoDolby.verifyOutput(described)
        check(MessageDigest.getInstance("SHA-256").digest(src.readBytes()).contentEquals(originalHash))
        println("PASS $kind frames=${cleaned.size} audioTracks=${out.tracks.count{it.handler=="soun"}}; synthetic configuration/alias and sentinel-RPU stripping only; not real Dolby/Android.")
    }
}
