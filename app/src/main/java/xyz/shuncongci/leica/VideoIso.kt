package xyz.shuncongci.leica

import java.io.File
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Non-fragmented ISO BMFF rewrite. Preserve original metadata boxes and non-video
 * samples, not an opaque archive of the old movie. Only codec/sample tables change.
 * No filesystem path is taken from media bytes. Unknown structural features fail closed.
 */
internal object VideoIso {
    const val MAX_BOX=32*1024*1024
    const val MAX_SAMPLES=300_000
    const val MAX_SAMPLE=32*1024*1024
    fun u32(b:ByteArray,p:Int):Long { require(p>=0 && p<=b.size-4);return ByteBuffer.wrap(b,p,4).int.toLong() and 0xffffffffL }
    fun i32(b:ByteArray,p:Int)=u32(b,p).toInt()
    fun u16(b:ByteArray,p:Int):Int {require(p>=0 && p<=b.size-2);return ((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255)}
    fun text(b:ByteArray,p:Int,n:Int):String {require(p>=0 && n>=0 && p<=b.size-n);return String(b,p,n,Charsets.ISO_8859_1)}
    fun bytes(block:DataOutputStream.()->Unit):ByteArray=ByteArrayOutputStream().also{DataOutputStream(it).use(block)}.toByteArray()
    data class Atom(val type:String,val raw:ByteArray,val head:Int=8) {
        val body get()=raw.copyOfRange(head,raw.size)
        fun children(offset:Int=0)=parse(raw,head+offset,raw.size)
        fun child(type:String)=children().single{it.type==type}
        fun withChildren(items:List<Atom>)=atom(type,items.fold(ByteArrayOutputStream()){o,a->o.apply{write(a.raw)}}.toByteArray())
    }
    fun atom(type:String,body:ByteArray):Atom {
        require(type.length==4 && body.size<=MAX_BOX-8)
        return Atom(type,bytes{writeInt(body.size+8);write(type.toByteArray(Charsets.ISO_8859_1));write(body)})
    }
    fun parse(b:ByteArray,start:Int=0,end:Int=b.size):List<Atom> {
        require(start>=0 && end<=b.size && start<=end);var p=start;val out=ArrayList<Atom>()
        while(p<end){
            require(end-p>=8 && out.size<10000){"容器字段截断或数量超限"}
            var n=u32(b,p);var head=8
            if(n==1L){require(end-p>=16);n=ByteBuffer.wrap(b,p+8,8).long;head=16}
            if(n==0L)n=(end-p).toLong()
            require(n>=head && n<=end-p && n<=MAX_BOX){"容器字段长度不支持"}
            out+=Atom(text(b,p+4,4),b.copyOfRange(p,p+n.toInt()),head);p+=n.toInt()
        }
        return out
    }
    data class Top(val type:String,val offset:Long,val size:Long,val head:Int)
    data class Sample(val offset:Long,val size:Int,val pts:Long,val duration:Long,val description:Int,val sync:Boolean)
    data class Track(val atom:Atom,val handler:String,val scale:Long,val duration:Long,val stbl:Atom,val samples:List<Sample>,val mediaDuration:Long=duration) {
        val mdia get()=atom.child("mdia")
        val stsd get()=stbl.child("stsd")
        val entries get()=stsd.children(8)
        fun replaceTable(table:Atom):Atom {
            val minf=mdia.child("minf")
            val minf2=minf.withChildren(minf.children().map{if(it.type=="stbl")table else it})
            val mdia2=mdia.withChildren(mdia.children().map{if(it.type=="minf")minf2 else it})
            return atom.withChildren(atom.children().map{if(it.type=="mdia")mdia2 else it})
        }
    }
    data class Movie(val file:File,val top:List<Top>,val moov:Atom,val tracks:List<Track>,val warnings:List<String> = emptyList()) {
        val video get()=tracks.single{it.handler=="vide"}
        fun sample(t:Track,i:Int):ByteArray=RandomAccessFile(file,"r").use{f->val s=t.samples[i];f.seek(s.offset);ByteArray(s.size).also{f.readFully(it)}}
    }
    /** Strict reader retained for diagnostics and callers requiring header agreement. */
    fun read(file:File,check:()->Unit={}):Movie=readFile(file,false,check)
    /** Compatibility for copied audio only: mdhd is descriptive, not a packet endpoint.
     * Keep every sample and edit byte; the writer and final extractor still check actual
     * payloads/PTS. This does not permit missing tables, bad offsets or video PTS drift. */
    fun readCompatible(file:File,check:()->Unit={}):Movie=readFile(file,true,check)
    private fun readFile(file:File,audioCompatibility:Boolean,check:()->Unit):Movie=RandomAccessFile(file,"r").use { f->
        val top=ArrayList<Top>();var p=0L;val length=f.length()
        while(p<length){
            check();require(length-p>=8 && top.size<10000){"不是受支持的完整MP4/MOV"};f.seek(p)
            var size=f.readInt().toLong() and 0xffffffffL;val type=ByteArray(4).also{f.readFully(it)}.toString(Charsets.ISO_8859_1);var head=8
            if(size==1L){require(length-p>=16);size=f.readLong();head=16};if(size==0L)size=length-p
            require(size>=head && size<=length-p){"视频容器截断"}
            require(type in setOf("ftyp","moov","mdat","free","skip","wide")){"包含尚不能安全保留的容器字段 $type"}
            top+=Top(type,p,size,head);p+=size
        }
        require(top.count{it.type=="moov"}==1 && top.count{it.type=="ftyp"}==1 && top.any{it.type=="mdat"}){"只支持完整的非分片MP4/MOV"}
        val m=top.single{it.type=="moov"};require(m.size<=MAX_BOX);f.seek(m.offset)
        val moov=Atom("moov",ByteArray(m.size.toInt()).also{f.readFully(it)},m.head)
        require(moov.children().none{it.type in setOf("mvex","cmov")}){"分片或压缩容器暂不支持"}
        val mvhd=moov.child("mvhd").body;require(mvhd.isNotEmpty() && mvhd[0].toInt() in 0..1)
        require(u32(mvhd,if(mvhd[0].toInt()==1)20 else 12)>0){"容器时间基无效"}
        val warnings=if(audioCompatibility)ArrayList<String>()else null
        val tracks=moov.children().filter{it.type=="trak"}.map{readTrack(it,top,mvhd,check,warnings)}
        require(tracks.size in 1..16 && tracks.count{it.handler=="vide"}==1 && tracks.sumOf{it.samples.size.toLong()}<=MAX_SAMPLES){"视频轨道或样本索引超出安全容量"}
        require(tracks.all{it.handler in setOf("vide","soun","meta","text","sbtl","subt","tmcd","clcp")}){"包含无法核验保留的附加轨道"}
        Movie(file,top,moov,tracks,warnings?.toList()?:emptyList())
    }
    private fun readTrack(a:Atom,top:List<Top>,movieHeader:ByteArray,check:()->Unit,warnings:MutableList<String>?):Track {
        val movieScale=u32(movieHeader,if(movieHeader[0].toInt()==1)20 else 12)
        require(a.children().all{it.type in setOf("tkhd","mdia","edts","udta","meta","tref")}){"未知轨道结构，保留系统封装成片"}
        val mdia=a.child("mdia");require(mdia.children().all{it.type in setOf("mdhd","hdlr","minf","elng")})
        val mdhd=mdia.child("mdhd").body;val v=mdhd[0].toInt();require(v in 0..1)
        val scale=u32(mdhd,if(v==1)20 else 12)
        val duration=if(v==1)ByteBuffer.wrap(mdhd,24,8).long else u32(mdhd,16)
        val handler=text(mdia.child("hdlr").body,8,4)
        require(scale>0 && duration>=0 && duration<Long.MAX_VALUE/1_000_000 &&
            (duration>0 || (handler=="soun" && warnings!=null)))
        val minf=mdia.child("minf");val table=minf.child("stbl");val fields=table.children()
        val ordinaryTables=setOf("stsd","stts","ctts","stsc","stsz","stco","co64","stss","sdtp","cslg")
        require(fields.all{it.type in ordinaryTables || (handler=="soun" && it.type in setOf("sgpd","sbgp"))}){"加密或特殊样本索引不迁移，使用系统封装"}
        val dref=minf.child("dinf").child("dref")
        require(dref.children(8).all{it.type=="url " && it.body.size==4 && (u32(it.body,0) and 1)==1L}){"不支持外部数据引用"}
        val size=table.child("stsz").body;val fixed=u32(size,4);val count=u32(size,8).toInt()
        require(count in 1..MAX_SAMPLES && fixed<=MAX_SAMPLE)
        if(handler=="soun")audioRollGroups(fields,count)
        require(size.size==12+(if(fixed==0L)count*4 else 0))
        val sizes=IntArray(count){i->(if(fixed!=0L)fixed else u32(size,12+i*4)).also{require(it in 1..MAX_SAMPLE.toLong())}.toInt()}
        val stts=table.child("stts").body;val durations=LongArray(count);var at=0
        tableRows(stts,8){row->val n=u32(stts,row).toInt();val dt=u32(stts,row+4);require(n>0 && n<=count-at && dt>0);repeat(n){durations[at++]=dt}}
        require(at==count){"轨道时长索引的样本数量不一致"}
        val ctts=fields.singleOrNull{it.type=="ctts"}?.body;val composition=LongArray(count)
        if(ctts!=null){require(ctts[0].toInt() in 0..1);at=0;tableRows(ctts,8){row->val n=u32(ctts,row).toInt();val dt=if(ctts[0].toInt()==1)i32(ctts,row+4).toLong() else u32(ctts,row+4);require(n>0 && n<=count-at);repeat(n){composition[at++]=dt}};require(at==count)}
        val chunk=fields.singleOrNull{it.type=="stco" || it.type=="co64"}?:error("缺少轨道位置索引")
        val cb=chunk.body;val nChunks=u32(cb,4).toInt();val stride=if(chunk.type=="co64")8 else 4
        require(nChunks in 1..MAX_SAMPLES && cb.size==8+nChunks*stride)
        val chunks=LongArray(nChunks){i->if(stride==8)ByteBuffer.wrap(cb,8+i*8,8).long else u32(cb,8+i*4)}
        val stsc=table.child("stsc").body;val rows=ArrayList<IntArray>()
        tableRows(stsc,12){row->rows+=intArrayOf(u32(stsc,row).toInt(),u32(stsc,row+4).toInt(),u32(stsc,row+8).toInt())}
        val entries=table.child("stsd").children(8);require(entries.size==u32(table.child("stsd").body,4).toInt())
        require(entries.none{it.type in setOf("encv","enca")}){"不支持加密视频"}
        require(rows.isNotEmpty() && rows[0][0]==1 && rows.zipWithNext().all{(a,b)->b[0]>a[0]} && rows.all{it[0] in 1..nChunks && it[1]>0 && it[2] in 1..entries.size})
        val sync=fields.singleOrNull{it.type=="stss"}?.body;val key=BooleanArray(count){sync==null}
        if(sync!=null)tableRows(sync,4){row->val k=u32(sync,row).toInt();require(k in 1..count);key[k-1]=true}
        val samples=ArrayList<Sample>(count);at=0;var r=0;var dts=0L
        for(c in chunks.indices){
            check();while(r+1<rows.size && rows[r+1][0]<=c+1)r++
            var offset=chunks[c];repeat(rows[r][1]){
                require(at<count);val n=sizes[at]
                require(offset>=0 && top.any{it.type=="mdat" && offset>=it.offset+it.head && n<=it.offset+it.size-offset}){"样本地址越界"}
                samples+=Sample(offset,n,dts+composition[at],durations[at],rows[r][2],key[at]);offset+=n;dts+=durations[at];at++
            }
        }
        require(at==count)
        // Keep media span separate from a header that includes an explicit empty edit.
        // AOSP MPEG4Writer::getDurationUs includes a track's start delay in mdhd;
        // its stts contains only media. Do not add that delay to the last sample on rewrite.
        val order=samples.sortedBy{it.pts}
        val decodeDuration=durations.sum()
        val displayDuration=order.last().pts-order.first().pts+order.last().duration
        var exactEdit=false;var delayed=false;var editEvidence="无可核验编辑"
        if(duration!=decodeDuration && !(handler=="vide" && duration==displayDuration) && movieScale>0){
            val edits=a.children().filter{it.type=="edts"}
            val edit=if(edits.size==1)try{VideoMuxTiming.edit(movieHeader,edits.single())}catch(_:IllegalArgumentException){null}else null
            if(edit!=null){
                editEvidence="空段${edit.empty}+活动段${edit.segment}@${edit.movieScale},media_time=${edit.mediaTime}"
                edit.segment?.let{segment->
                    exactEdit=handler=="vide" && edit.empty==0L && edit.mediaTime==order.first().pts &&
                        duration>order.last().pts-order.first().pts && segment<=Long.MAX_VALUE/scale &&
                        duration<=Long.MAX_VALUE/movieScale && segment*scale==duration*movieScale
                    // Only unreordered, untrimmed video/audio. B-frame and trim semantics
                    // cannot be inferred from a start-delay discrepancy.
                    delayed=handler in setOf("vide","soun") && composition.all{it==0L} &&
                        VideoMuxTiming.delayedHeader(duration,decodeDuration,scale,edit)
                }
            }
        }
        val consistent=duration==decodeDuration || (handler=="vide" && duration==displayDuration) || exactEdit || delayed
        val compatibleAudio=handler=="soun" && warnings!=null
        if(!consistent){
            val h=a.child("tkhd").body;val p=if(h.firstOrNull()?.toInt()==1)20 else 12
            val id=if(h.size>=p+4)u32(h,p).toString()else"?"
            val detail="ID$id $handler，时间基$scale，mdhd=$duration，stts=$decodeDuration，显示=$displayDuration，${samples.size}样本，首PTS=${order.first().pts}，末PTS=${order.last().pts}；$editEvidence"
            require(compatibleAudio){"轨道时长与解码/显示时序或精确编辑时长不一致：$detail"}
            // Do not hide an invalid/ambiguous edit behind a header warning. Supported
            // rate-one trim/delay semantics are retained, never expanded or shortened.
            val edits=a.children().filter{it.type=="edts"};require(edits.size<=1)
            edits.singleOrNull()?.let{VideoMuxTiming.edit(movieHeader,it)}
            warnings!!.add("音轨头部时长描述不一致，已兼容继续；保留全部音频样本、时间戳和原编辑列表，不以头部时长单独阻止导出：$detail")
        }
        return Track(a,handler,scale,duration,table,samples,if(delayed || compatibleAudio)decodeDuration else duration)
    }
    /** Audio packets/order/timing are copied unchanged, so validated roll/prol recovery
     * groups remain valid. Never allow these (or arbitrary groups) on re-encoded video.
     * Deliberately narrow: fixed two-byte v1 descriptions and complete v0 membership. */
    internal fun audioRollGroups(fields:List<Atom>,count:Int) {
        val descriptions=fields.filter{it.type=="sgpd"};val membership=fields.filter{it.type=="sbgp"}
        if(descriptions.isEmpty() && membership.isEmpty())return
        require(count in 1..MAX_SAMPLES && descriptions.size==1 && membership.size==1){"音频预滚分组表缺失或重复"}
        val d=descriptions.single().body;val m=membership.single().body
        require(d.size>=16 && u32(d,0)==0x01000000L && u32(d,8)==2L){"不支持的音频预滚描述格式"}
        val kind=text(d,4,4);require(kind in setOf("roll","prol")){"未知音频分组类型"}
        val entries=u32(d,12);require(entries in 1..MAX_SAMPLES.toLong() && d.size.toLong()==16+2*entries){"音频预滚描述长度错误"}
        require(m.size>=12 && u32(m,0)==0L && text(m,4,4)==kind){"音频预滚分组版本或类型不一致"}
        val rows=u32(m,8);require(rows in 1..MAX_SAMPLES.toLong() && m.size.toLong()==12+8*rows){"音频预滚成员表截断"}
        var total=0L
        repeat(rows.toInt()){i->val n=u32(m,12+i*8);val index=u32(m,16+i*8)
            require(n>0 && n<=count-total && index<=entries){"音频预滚样本数或描述引用越界"};total+=n}
        require(total==count.toLong()){"音频预滚成员未覆盖全部样本"}
    }
    private fun tableRows(body:ByteArray,stride:Int,action:(Int)->Unit) {
        require(body.size>=8);val n=u32(body,4);require(n<=MAX_SAMPLES && body.size.toLong()==8+n*stride)
        repeat(n.toInt()){action(8+it*stride)}
    }
    fun videoDescription(t:Track):Atom {
        require(t.entries.size==1 && t.samples.all{it.description==1}){"多段编码描述不能原生保留"}
        val e=t.entries.single();require(e.type in setOf("avc1","avc3","hvc1","hev1")){"不支持此视频编码；杜比视界不会自动降级为HDR10"}
        require(e.body.size>=78);require(t.stbl.children().all{it.type in setOf("stsd","stts","ctts","stsc","stsz","stco","co64","stss","sdtp","cslg")}){"包含不能原样迁移的逐帧元数据"}
        require(e.children(78).all{it.type in setOf("avcC","hvcC","colr","pasp","clap","btrt","fiel","mdcv","clli","sv3d","st3d")}){"包含动态HDR或未知视频描述，无法安全保留"}
        e.children(78).singleOrNull{it.type=="fiel"}?.let{require(it.body.firstOrNull()?.toInt()==1){"暂不支持隔行视频"}}
        return e
    }
    fun color(t:Track):VideoContract.Color {
        val e=videoDescription(t);val meta=e.children(78);val c=meta.singleOrNull{it.type=="colr"}?.body?:error("视频缺少明确色彩标记，不能猜测HDR或色域")
        val kind=text(c,0,4);require(kind in setOf("nclx","nclc") && c.size>=10){"ICC/未知视频色彩描述暂不支持"}
        val config=meta.single{it.type==if(e.type.startsWith("avc"))"avcC" else "hvcC"}.body
        val signal=VideoBitstream.config(VideoBitstream.record(config,!e.type.startsWith("avc")),!e.type.startsWith("avc"))
        val color=VideoContract.Color(u16(c,4),u16(c,6),u16(c,8),kind=="nclx" && c.size>=11 && c[10].toInt() and 128!=0,signal.bits).also{it.validate()}
        require((signal.primaries==null || signal.primaries==color.primaries) && (signal.transfer==null || signal.transfer==color.transfer) && (signal.matrix==null || signal.matrix==color.matrix) && (signal.full==null || signal.full==color.fullRange)){"容器色彩与SPS冲突"}
        return color
    }
    private fun table(type:String,rows:List<LongArray>,version:Int=0)=atom(type,bytes{writeInt(version shl 24);writeInt(rows.size);rows.forEach{r->r.forEach{writeInt(it.toInt())}}})
    private fun runs(values:List<Long>):List<LongArray> {
        val out=ArrayList<LongArray>();values.forEach{v->if(out.isNotEmpty() && out.last()[1]==v)out.last()[0]++ else out+=longArrayOf(1,v)};return out
    }
    private fun relocated(t:Track,offsets:LongArray,video:Track?=null):Atom {
        val out=ArrayList<Atom>();val count=offsets.size
        var replacement:Map<String,Atom> = emptyMap()
        if(video!=null){
            val order=t.samples.sortedBy{it.pts};require(order.size==video.samples.size)
            val durations=order.indices.map{i->if(i+1<order.size)order[i+1].pts-order[i].pts else t.mediaDuration-(order.last().pts-order.first().pts)}
            require(durations.all{it in 1..0xffffffffL}){"帧时序不能无损保留"}
            val old=videoDescription(t);val enc=video.entries.single();require(enc.type.take(3)==old.type.take(3))
            require(u16(old.body,24)==u16(enc.body,24) && u16(old.body,26)==u16(enc.body,26)){"编码器改变了分辨率"}
            val cs=enc.children(78).single{it.type=="avcC" || it.type=="hvcC"}
            val kids=old.children(78).filter{it.type !in setOf("btrt","mdcv","clli")}.map{if(it.type==cs.type)cs else it}
            val header=old.body.copyOfRange(0,78);enc.body.copyInto(header,42,42,78)
            val entry=atom(old.type,header+kids.flatMap{it.raw.asIterable()}.toByteArray())
            val stsd=atom("stsd",bytes{writeInt(0);writeInt(1);write(entry.raw)})
            val c=order.first().pts;require(c in 0..Int.MAX_VALUE.toLong())
            // An absent zero-offset CTTS is already an exact representation. Adding
            // one can change a demuxer's terminal-duration interpretation on delayed
            // tracks; preserve absence when no composition offset is needed.
            replacement=mapOf("stsd" to stsd,"stts" to table("stts",runs(durations)),"stsz" to atom("stsz",bytes{writeInt(0);writeInt(0);writeInt(count);video.samples.forEach{writeInt(it.size)}}),"stss" to table("stss",video.samples.mapIndexedNotNull{i,s->if(s.sync)longArrayOf(i+1L) else null}))+
                if(c==0L && t.stbl.children().none{it.type=="ctts"})emptyMap() else mapOf("ctts" to table("ctts",listOf(longArrayOf(count.toLong(),c))))
        }
        val replaced=setOf("stco","co64","stsc")+if(video==null)emptySet() else setOf("stsd","stts","ctts","stsz","stss","sdtp","cslg")
        out+=t.stbl.children().filter{it.type !in replaced}
        out+=replacement.values
        val descriptions=if(video==null)t.samples.map{it.description} else List(count){1}
        val sc=descriptions.mapIndexedNotNull{i,d->if(i==0 || descriptions[i-1]!=d)longArrayOf(i+1L,1,d.toLong()) else null}
        out+=table("stsc",sc)
        out+=atom("co64",bytes{writeInt(0);writeInt(count);offsets.forEach{writeLong(it)}})
        return t.replaceTable(t.stbl.withChildren(out))
    }
    fun rewrite(source:Movie,encoded:Movie,target:File,check:()->Unit={}) {
        require(!target.exists() && target.canonicalFile!=source.file.canonicalFile)
        require(source.tracks.none{it.handler=="meta"}){"原片含内容相关或未知定时元数据，改为逐项迁移拍摄描述"}
        require(encoded.video.samples.size==source.video.samples.size)
        require(color(source.video)==color(encoded.video)){"编码前后色彩描述不同，不迁移原容器描述"}
        require(source.moov.children().all{it.type in setOf("mvhd","trak","udta","meta")}){"未知全局容器结构，保留系统封装成片"}
        val positions=HashMap<Track,LongArray>()
        RandomAccessFile(target,"rw").use { out->
            RandomAccessFile(source.file,"r").use { input->
                for(t in source.top.filter{it.type !in setOf("moov","mdat","free","skip","wide")})copy(input,out,t.offset,t.size,check)
                val start=out.filePointer;out.writeInt(1);out.writeBytes("mdat");out.writeLong(0)
                RandomAccessFile(encoded.file,"r").use { rendered->
                    for(t in source.tracks){
                        val feed=if(t===source.video)encoded.video else t
                        val offsets=LongArray(feed.samples.size)
                        for(i in feed.samples.indices){check();offsets[i]=out.filePointer;val s=feed.samples[i];copy(if(t===source.video)rendered else input,out,s.offset,s.size.toLong(),check)}
                        positions[t]=offsets
                    }
                }
                val end=out.filePointer;out.seek(start+8);out.writeLong(end-start);out.seek(end)
                var index=0
                val moov=source.moov.withChildren(source.moov.children().map{a->if(a.type!="trak")a else {val t=source.tracks[index++];relocated(t,positions.getValue(t),if(t===source.video)encoded.video else null)}})
                out.write(moov.raw);out.fd.sync()
            }
        }
        verify(source,readCompatible(target,check),check)
    }
    fun verify(source:Movie,result:Movie,check:()->Unit={}) {
        require(source.tracks.size==result.tracks.size)
        require(source.moov.children().filter{it.type!="trak"}.map{it.raw.toList()}==result.moov.children().filter{it.type!="trak"}.map{it.raw.toList()}){"全局元数据保留校验失败"}
        for(i in source.tracks.indices){
            check();val a=source.tracks[i];val b=result.tracks[i]
            require(a.handler==b.handler && a.scale==b.scale && a.duration==b.duration && a.mediaDuration==b.mediaDuration && a.samples.size==b.samples.size)
            // Normalize sample tables out, then byte-compare every remaining nested metadata box.
            require(a.replaceTable(atom("stbl",byteArrayOf())).raw.contentEquals(b.replaceTable(atom("stbl",byteArrayOf())).raw)){"轨道元数据保留校验失败"}
            require(a.samples.map{it.pts}.sorted()==b.samples.map{it.pts}.sorted()){"原帧时序未保留"}
            if(a.handler!="vide"){
                val left=a.stbl.withChildren(a.stbl.children().filter{it.type !in setOf("stco","co64","stsc")})
                val right=b.stbl.withChildren(b.stbl.children().filter{it.type !in setOf("stco","co64","stsc")})
                require(left.raw.contentEquals(right.raw) && sampleHash(source,a,check).contentEquals(sampleHash(result,b,check))){"音轨或附加轨道未完整保留"}
            }else {
                val x=videoDescription(a);val y=videoDescription(b)
                require(x.body.copyOfRange(0,42).contentEquals(y.body.copyOfRange(0,42)))
                require(x.children(78).filter{it.type !in setOf("avcC","hvcC","btrt","mdcv","clli")}.map{it.raw.toList()}==y.children(78).filter{it.type !in setOf("avcC","hvcC","btrt","mdcv","clli")}.map{it.raw.toList()}){"视频色彩或描述信息未保留"}
            }
        }
    }
    private fun sampleHash(m:Movie,t:Track,check:()->Unit):ByteArray {
        val hash=MessageDigest.getInstance("SHA-256");val buf=ByteArray(256*1024)
        RandomAccessFile(m.file,"r").use{f->for(s in t.samples){check();f.seek(s.offset);var n=s.size;while(n>0){val k=minOf(n,buf.size);f.readFully(buf,0,k);hash.update(buf,0,k);n-=k}}};return hash.digest()
    }
    /** Copy only independently readable descriptive fields into a verified encoded movie.
     * Existing mdat offsets stay fixed: old moov becomes free and the new moov is appended.
     * This path is optional and never modifies source or the verified fallback. */
    fun salvage(source:File,encoded:File,target:File,metadata:VideoMetadata,check:()->Unit={}) {
        require(!target.exists() && target.canonicalFile!=source.canonicalFile && target.canonicalFile!=encoded.canonicalFile)
        fun header(file:File):Pair<Top,Atom> = RandomAccessFile(file,"r").use{f->
            var p=0L;var found:Pair<Top,Atom>?=null;var count=0
            while(p<f.length()){check();require(++count<=10000 && f.length()-p>=8);f.seek(p);var n=f.readInt().toLong() and 0xffffffffL;val type=ByteArray(4).also{f.readFully(it)}.toString(Charsets.ISO_8859_1);var h=8
                if(n==1L){require(f.length()-p>=16);n=f.readLong();h=16};if(n==0L)n=f.length()-p;require(n>=h && n<=f.length()-p)
                if(type=="moov"){require(found==null && n<=MAX_BOX);f.seek(p);found=Top(type,p,n,h) to Atom(type,ByteArray(n.toInt()).also{f.readFully(it)},h)};p+=n
            };requireNotNull(found){"未找到可独立读取的拍摄信息"}
        }
        val (_,src)=header(source);val (location,dst)=header(encoded)
        val safeTags=setOf("©day","©xyz","©mak","©mod","©swr","©nam","©ART","©cmt","©cpy","cprt","name")
        val safeKeys=setOf("com.apple.quicktime.creationdate","com.apple.quicktime.location.ISO6709","com.apple.quicktime.make","com.apple.quicktime.model","com.apple.quicktime.software","com.apple.quicktime.author","com.apple.quicktime.description","com.apple.quicktime.title","com.apple.quicktime.copyright")
        fun meta(a:Atom):Atom? {
            require(a.body.size>=4 && u32(a.body,0)==0L);val kids=a.children(4)
            require(kids.all{it.type in setOf("hdlr","keys","ilst")})
            val handler=text(kids.single{it.type=="hdlr"}.body,8,4);require(handler in setOf("mdta","mdir"))
            val keys=kids.singleOrNull{it.type=="keys"};val keep=HashSet<String>()
            if(keys!=null){val body=keys.body;require(body.size>=8 && u32(body,0)==0L);var p=8;val n=u32(body,4);require(n<=10000)
                repeat(n.toInt()){i->val size=u32(body,p).toInt();require(size>=8 && size<=body.size-p);val namespace=text(body,p+4,4);val name=text(body,p+8,size-8);if(namespace=="mdta" && name in safeKeys)keep+=String(bytes{writeInt(i+1)},Charsets.ISO_8859_1) else metadata.warn("跳过不识别的键值元数据字段，避免沿用旧画面分析或私有偏移。");p+=size};require(p==body.size)
            }else keep+=safeTags
            val ilst=kids.single{it.type=="ilst"};val entries=ilst.children().mapNotNull{item->
                if(item.type !in keep){metadata.warn("部分非拍摄描述字段不能安全迁移，已跳过。");null}else metadata.attempt("拍摄字段读取"){require(item.children().all{it.type=="data" && it.body.size>=8});item}
            }
            if(entries.isEmpty())return null
            return atom("meta",a.body.copyOfRange(0,4)+kids.map{if(it.type=="ilst")it.withChildren(entries) else it}.flatMap{it.raw.asIterable()}.toByteArray())
        }
        val additions=ArrayList<Atom>()
        for(a in src.children())if(a.type=="udta"){
            val values=metadata.attempt("udta拍摄信息读取"){a.children().mapNotNull{v->when {v.type in safeTags->v;v.type=="meta"->metadata.attempt("键值拍摄信息读取"){meta(v)};else->{metadata.warn("未迁移不能独立核验的udta字段 ${v.type.map{if(it.code in 32..255)it else '?'}.joinToString("")}。");null}}}}
            if(!values.isNullOrEmpty())additions+=a.withChildren(values)
        }else if(a.type=="meta")metadata.attempt("全局键值拍摄信息读取"){meta(a)}?.let{additions+=it}
        val existingUdta=dst.children().singleOrNull{it.type=="udta"}
        if(existingUdta!=null){val i=additions.indexOfFirst{it.type=="udta"};if(i>=0){val fresh=additions[i].children();additions[i]=additions[i].withChildren(existingUdta.children().filter{old->fresh.none{it.type==old.type}}+fresh)}}
        val srcMv=src.children().singleOrNull{it.type=="mvhd"}
        val merged=dst.children().filter{d->additions.none{it.type==d.type}}.map{a->
            if(a.type=="mvhd" && srcMv!=null){metadata.attempt("拍摄创建时间读取"){
                val s=srcMv.body;val d=a.body;require(s.isNotEmpty() && d.isNotEmpty() && s[0]==d[0] && s[0].toInt() in 0..1);val n=if(s[0].toInt()==1)16 else 8;require(s.size>=4+n && d.size>=4+n);s.copyInto(d,4,4,4+n);atom(a.type,d)
            }?:a}else a
        }+additions
        val replacement=dst.withChildren(merged)
        require(!replacement.raw.contentEquals(dst.raw)){"没有额外的可安全迁移字段；已保留系统封装信息"}
        RandomAccessFile(encoded,"r").use{input->RandomAccessFile(target,"rw").use{out->copy(input,out,0,input.length(),check);if(u32(dst.raw,0)==0L){out.seek(location.offset);out.writeInt(location.size.toInt())};out.seek(location.offset+4);out.writeBytes("free");out.seek(out.length());out.write(replacement.raw);out.fd.sync()}}
        val (_,actual)=header(target);require(actual.raw.contentEquals(replacement.raw)){"描述信息回读不一致"}
        metadata.note("已逐项恢复可独立核验的拍摄描述和创建时间；未知附加轨道与私有字段不承诺保留。")
    }
    private fun copy(input:RandomAccessFile,out:RandomAccessFile,at:Long,n:Long,check:()->Unit) {
        require(at>=0 && n>=0 && n<=input.length()-at);input.seek(at);var left=n;val buf=ByteArray(minOf(256*1024L,n).toInt())
        while(left>0){check();val k=minOf(left,buf.size.toLong()).toInt();input.readFully(buf,0,k);out.write(buf,0,k);left-=k}
    }
}
