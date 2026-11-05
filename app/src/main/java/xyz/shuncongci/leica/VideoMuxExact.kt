package xyz.shuncongci.leica

import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Use the exact MediaCodec timestamps, not MPEG4Writer's duration coalescing.
 * A sealed ordered NAL/PTS/key-frame witness is mandatory before changing any table.
 * No tolerance, inferred offset, frame-rate grid, source-media rewrite or pixel change. */
internal object VideoMuxExact {
    private const val CLOCK=1_000_000L
    class Seal internal constructor(internal val count:Int,internal val hash:ByteArray)
    class Witness(private val hevc:Boolean) {
        private val digest=MessageDigest.getInstance("SHA-256")
        private var count=0;private var previous=-1L;private var closed=false
        fun add(buffer:ByteBuffer,offset:Int,size:Int,pts:Long,sync:Boolean) {
            require(!closed && count<VideoIso.MAX_SAMPLES && pts>=0 && pts>previous)
            sample(digest,buffer,offset,size,pts,sync,hevc,false)
            previous=pts;count++
        }
        fun finish():Seal {require(!closed && count>0);closed=true;return Seal(count,digest.digest())}
    }
    /** Length framing is part of the container, not the compressed NAL payload. Every
     * NAL (including SEI/parameter sets), its order and each sample boundary is hashed. */
    private fun sample(hash:MessageDigest,buffer:ByteBuffer,offset:Int,size:Int,pts:Long,sync:Boolean,hevc:Boolean,lengthOnly:Boolean) {
        require(offset>=0 && size in 1..VideoIso.MAX_SAMPLE && offset<=buffer.limit()-size)
        val b=buffer.duplicate().apply{position(offset);limit(offset+size)}.slice()
        fun u(i:Int)=b.get(i).toInt() and 255
        fun prefix(i:Int)=if(i<=size-4 && u(i)==0 && u(i+1)==0 && u(i+2)==0 && u(i+3)==1)4 else if(i<=size-3 && u(i)==0 && u(i+1)==0 && u(i+2)==1)3 else 0
        fun parse(length:Boolean):List<IntRange>? = try {
            val out=ArrayList<IntRange>();var at=0
            while(at<size){
                if(length){require(at<=size-4);val n=(0..3).fold(0L){a,j->(a shl 8)+u(at+j)};at+=4;require(n>0 && n<=size-at);out+=at until at+n.toInt();at+=n.toInt()}
                else {val n=prefix(at);require(n>0);val start=at+n;at=start;while(at<size && prefix(at)==0)at++;require(at>start);out+=start until at}
                require(out.size<=65536)
            }
            var vcl=false
            for(r in out){val n=r.last-r.first+1;val first=u(r.first);require(first and 128==0)
                val kind=if(hevc)(first ushr 1) and 63 else first and 31
                if(hevc){require(n>=2 && first and 1==0 && u(r.first+1) ushr 3==0 && u(r.first+1) and 7!=0 && kind !in setOf(62,63));if(kind<=31)vcl=true}
                else {require(kind !in setOf(0,14,15,19,20,21));if(kind in 1..5)vcl=true}
            }
            require(vcl);out
        }catch(_:IllegalArgumentException){null}
        // A four-byte NAL length may itself start with 00 00 01. A prefix alone
        // cannot select framing. Parse the complete sample and reject ambiguity.
        val options=listOfNotNull(parse(true),if(lengthOnly)null else parse(false)).distinct()
        require(options.size==1){"编码样本NAL格式不完整、缺少独立画面或存在歧义，不能绑定原帧时序"}
        val ranges=options.single()
        hash.update(ByteBuffer.allocate(16).putLong(pts).putInt(if(sync)1 else 0).putInt(ranges.size).array())
        for(r in ranges){
            val n=r.last-r.first+1
            hash.update(ByteBuffer.allocate(4).putInt(n).array());hash.update(b.duplicate().apply{position(r.first);limit(r.last+1)})
        }
    }
    private fun exact(value:Long,n:Long,d:Long):Long {
        require(value>=0 && n>0 && d>0)
        val(q,r)=(BigInteger.valueOf(value)*BigInteger.valueOf(n)).divideAndRemainder(BigInteger.valueOf(d))
        require(r.signum()==0){"系统电影时间基不能精确迁移，未放宽音轨时序"};return q.longValueExact()
    }
    private fun duration(a:VideoIso.Atom):Long {
        val b=a.body;require(b[0].toInt() in 0..1);val v=b[0].toInt()==1
        val p=if(a.type=="tkhd")if(v)28 else 20 else if(v)24 else 16
        return if(v)ByteBuffer.wrap(b,p,8).long.also{require(it>=0)} else VideoIso.u32(b,p)
    }
    /** Preserve creation/modification/flags and the entire non-clock header suffix;
     * upgrade to version 1 only when the exact duration no longer fits version 0. */
    private fun header(a:VideoIso.Atom,end:Long,scale:Long?=null):VideoIso.Atom {
        require(end>0);val b=a.body;require(b[0].toInt() in 0..1);val old=b[0].toInt()==1;val wide=old || end>0xffffffffL
        val tk=a.type=="tkhd";require(a.type in setOf("mvhd","mdhd","tkhd") && (tk==(scale==null)))
        val tail=if(tk)if(old)36 else 24 else if(old)32 else 20
        require(b.size>=tail)
        fun time(p:Int)=if(old)ByteBuffer.wrap(b,p,8).long else VideoIso.u32(b,p)
        return VideoIso.atom(a.type,VideoIso.bytes{
            writeByte(if(wide)1 else 0);write(b,1,3)
            for(t in listOf(time(4),time(if(old)12 else 8))){if(wide)writeLong(t)else writeInt(t.toInt())}
            if(tk)write(b,if(old)20 else 12,8)else {require(scale!! in 1..0xffffffffL);writeInt(scale.toInt())}
            if(wide)writeLong(end)else writeInt(end.toInt());write(b,tail,b.size-tail)
        })
    }
    private fun audioEdit(a:VideoIso.Atom,oldScale:Long):VideoIso.Atom {
        val e=a.children().single();require(e.type=="elst");val b=e.body;val v=b[0].toInt();require(v in 0..1 && b.sliceArray(1..3).all{it==0.toByte()})
        val count=VideoIso.u32(b,4).toInt();val stride=if(v==1)20 else 12;require(count in 1..2 && b.size==8+count*stride)
        return VideoIso.atom("edts",VideoIso.atom("elst",VideoIso.bytes{
            writeInt(1 shl 24);writeInt(count)
            repeat(count){i->val p=8+i*stride;val d=if(v==1)ByteBuffer.wrap(b,p,8).long else VideoIso.u32(b,p);val t=if(v==1)ByteBuffer.wrap(b,p+8,8).long else VideoIso.i32(b,p+4).toLong();val rate=VideoIso.i32(b,p+if(v==1)16 else 8)
                require(d>0 && rate==65536 && (t>=0 || (i==0 && count==2 && t==-1L)))
                writeLong(exact(d,CLOCK,oldScale));writeLong(t);writeInt(rate)
            }
        }).raw)
    }
    private fun videoTrack(t:VideoIso.Track,pts:LongArray,end:Long):VideoIso.Atom {
        val span=end-pts.first();val d=pts.indices.map{i->if(i+1<pts.size)pts[i+1]-pts[i]else end-pts[i]}
        require(d.all{it in 1..0xffffffffL}){"逐帧精确间隔超出MP4范围，未重建帧率"}
        val runs=ArrayList<Pair<Int,Long>>();for(v in d){if(runs.isNotEmpty() && runs.last().second==v){val last=runs.removeAt(runs.lastIndex);runs+=last.first+1 to v}else runs+=1 to v}
        val stts=VideoIso.atom("stts",VideoIso.bytes{writeInt(0);writeInt(runs.size);for((n,v)in runs){writeInt(n);writeInt(v.toInt())}})
        val table=t.stbl.withChildren(t.stbl.children().filter{it.type !in setOf("stts","ctts","cslg")}+stts)
        val track=t.replaceTable(table)
        val edit=VideoIso.atom("edts",VideoIso.atom("elst",VideoIso.bytes{
            writeInt(1 shl 24);writeInt(if(pts.first()>0)2 else 1)
            if(pts.first()>0){writeLong(pts.first());writeLong(-1);writeInt(65536)}
            writeLong(span);writeLong(0);writeInt(65536)
        }).raw)
        return track.withChildren(track.children().filter{it.type!="edts"}.map{a->when(a.type){
            "tkhd"->header(a,end)
            "mdia"->a.withChildren(a.children().map{if(it.type=="mdhd")header(it,span,CLOCK)else it})
            else->a
        }}+edit)
    }
    /** Mandatory, not an optional-metadata fallback. Reuse all mdat offsets and bytes.
     * The source is the task's private system-mux output, never the user's original. */
    fun write(movie:VideoIso.Movie,target:File,pts:LongArray,end:Long,seal:Seal,check:()->Unit={}) {
        require(!target.exists() && target.canonicalFile!=movie.file.canonicalFile)
        require(pts.isNotEmpty() && pts.size==seal.count && pts.first()>=0 && end>pts.last() && (1 until pts.size).all{pts[it]>pts[it-1]})
        val t=movie.video;require(movie.tracks.all{it.handler in setOf("vide","soun")} && t.samples.size==pts.size && t.samples.all{it.description==1})
        require((1 until t.samples.size).all{t.samples[it].pts>t.samples[it-1].pts}){"系统样本重排，不能重写时序"}
        val entry=VideoIso.videoDescription(t);val hevc=entry.type in setOf("hvc1","hev1")
        val config=entry.children(78).single{it.type==if(hevc)"hvcC" else "avcC"}.body
        require(config[if(hevc)21 else 4].toInt() and 3==3){"系统NAL长度格式不能完整核验"}
        val witnessed=MessageDigest.getInstance("SHA-256")
        RandomAccessFile(movie.file,"r").use{f->for(i in pts.indices){check();val s=t.samples[i];f.seek(s.offset);val data=ByteArray(s.size);f.readFully(data);sample(witnessed,ByteBuffer.wrap(data),0,data.size,pts[i],s.sync,hevc,true)}}
        require(MessageDigest.isEqual(witnessed.digest(),seal.hash)){"系统封装样本与编码见证不一致，已阻止保存"}
        val mv=movie.moov.child("mvhd");val oldScale=VideoIso.u32(mv.body,if(mv.body[0].toInt()==1)20 else 12);require(oldScale>0)
        val tracks=movie.tracks.map{a->check();if(a===t)videoTrack(a,pts,end)else {
            val children=a.atom.children();require(children.count{it.type=="edts"}<=1)
            children.singleOrNull{it.type=="edts"}?.let{VideoMuxTiming.edit(mv.body,it)}
            a.atom.withChildren(children.map{b->when(b.type){"tkhd"->header(b,exact(duration(b),CLOCK,oldScale));"edts"->audioEdit(b,oldScale);else->b}})
        }}
        val movieEnd=tracks.maxOf{duration(it.child("tkhd"))};var index=0
        val moov=movie.moov.withChildren(movie.moov.children().map{when(it.type){"mvhd"->header(it,movieEnd,CLOCK);"trak"->tracks[index++];else->it}})
        val location=movie.top.single{it.type=="moov"}
        // A size-zero terminal box would swallow an appended moov. Never reinterpret it.
        RandomAccessFile(movie.file,"r").use{f->for(box in movie.top){f.seek(box.offset);require(f.readInt()!=0){"系统容器尾部长度不明确，未修改时序"}}}
        try {
            RandomAccessFile(movie.file,"r").use{input->RandomAccessFile(target,"rw").use{out->
                val b=ByteArray(256*1024);while(true){check();val n=input.read(b);if(n<0)break;out.write(b,0,n)}
                out.seek(location.offset+4);out.writeBytes("free");out.seek(out.length());out.write(moov.raw);out.fd.sync()
            }}
            val result=VideoIso.readCompatible(target,check)
            require(result.video.scale==CLOCK && result.video.samples.size==pts.size)
            val e=VideoMuxTiming.edit(result.moov.child("mvhd").body,result.video.atom.child("edts"))
            val actual=result.video.samples.map{Math.addExact(it.pts-e.mediaTime,e.empty)}
            VideoContract.verifyFrames(pts,actual)
            VideoMuxTiming.verifyMovie(pts,actual,result,end,end)
            // Every existing media byte and every audio media-clock table stays unchanged.
            val copied=MessageDigest.getInstance("SHA-256")
            RandomAccessFile(target,"r").use{f->for(i in pts.indices){check();val s=result.video.samples[i];f.seek(s.offset);val data=ByteArray(s.size);f.readFully(data);sample(copied,ByteBuffer.wrap(data),0,data.size,pts[i],s.sync,hevc,true)}}
            require(MessageDigest.isEqual(copied.digest(),seal.hash)){"精确封装后编码样本回读不一致"}
            require(result.tracks.size==movie.tracks.size)
            for(i in movie.tracks.indices){check();val a=movie.tracks[i];val b=result.tracks[i]
                require(a.samples.size==b.samples.size)
                for(j in a.samples.indices)require(a.samples[j].offset==b.samples[j].offset && a.samples[j].size==b.samples[j].size && a.samples[j].sync==b.samples[j].sync)
                if(a!==t)require(a.mdia.raw.contentEquals(b.mdia.raw)){"音轨媒体内容或时间基发生变化"}
            }
        }catch(error:Throwable){target.delete();throw error}
    }
}
