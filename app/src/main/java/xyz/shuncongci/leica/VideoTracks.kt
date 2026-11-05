package xyz.shuncongci.leica

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Track discovery/choice is not frame decoding. Never pick the first/largest stream,
 * or treat a Dolby/base-layer alias as an independent SDR/HDR video. */
internal object VideoTracks {
    data class Track(val index:Int,val id:Int,val mime:String,val width:Int=0,val height:Int=0,
        val durationUs:Long=0,val rotation:Int=0) {
        val normalizedMime get()=mime.trim().lowercase(Locale.ROOT)
        val video get()=normalizedMime.startsWith("video/")
        val supported get()=normalizedMime in setOf("video/avc","video/hevc")
        val label get()="轨道 ${index+1} · ${normalizedMime.ifBlank{"未知编码"}} · ${width}×${height} · ${durationUs/1_000_000.0}秒"
    }
    data class Header(val id:Int,val codecs:List<String>,val dependencies:Set<Int>,val unsafe:Boolean,val rotation:Int=0,val dolby:VideoDolby.Base?=null)
    data class Inventory(val tracks:List<Track>,val headers:List<Header>?,val headerIssue:String="") {
        val videos get()=tracks.filter{it.video}
        // Diagnostic candidates only: equal IDs do not authorize dropping an encoding view.
        val dolbyAliasIds get()=videos.filter{it.id>0}.groupBy{it.id}.filterValues{group->
            group.size==2 && group.map{it.normalizedMime}.toSet()==setOf("video/dolby-vision","video/hevc") &&
                group.map{listOf(it.width.toLong(),it.height.toLong(),it.durationUs)}.distinct().size==1 &&
                group.all{it.width>0 && it.height>0 && it.durationUs>0}
        }.keys
        val hasDolby get()=videos.any{it.normalizedMime=="video/dolby-vision"} || headers.orEmpty().any{it.dolby!=null || it.codecs.any{c->c in setOf("dvh1","dvhe")}}
        val dolbyBase get()=headers?.singleOrNull()?.dolby
        val processingBlockReason:String? get(){
            if(!hasDolby)return null
            val h=headers?.singleOrNull()?:return "应用能力限制：杜比转HEVC需要可核验的单条容器视频基础层；当前轨道结构尚不能确认，尚未尝试设备编码。"
            val d=h.dolby?:return "缺少有效杜比配置与HEVC基础层证据，不能仅按系统编码名强制导出。"
            d.config.blockReason?.let{return it}
            if(h.unsafe || h.id in h.dependencies)return "杜比视频含未知结构、加密或层间依赖，当前不能安全转换为普通HEVC。"
            if(videos.size==1){
                val t=videos.single()
                if(t.normalizedMime !in setOf("video/dolby-vision","video/hevc") || (t.id>0 && t.id!=h.id))return "杜比系统轨与容器基础层ID或编码不一致。"
            }else if(videos.size!=2 || dolbyAliasIds!=setOf(h.id) || videos.filter{it.rotation!=-1}.map{it.rotation}.distinct().size>1)
                return "杜比/HEVC不是已核验的同一容器基础层视图，不能擅自丢弃其他画面。"
            try{videos.forEach{VideoDolby.color(d,it.width,it.height)}}catch(e:IllegalArgumentException){return e.message?:"无法核验杜比标准HDR基础层"}
            return null
        }
        // Only collapse the OS alias after a single matching, compatible container BL is proven.
        val processingVideos get()=if(hasDolby && processingBlockReason==null)listOf(videos.firstOrNull{it.normalizedMime=="video/hevc"}?:videos.single()) else videos
        val conversionNotice get()=if(hasDolby && processingBlockReason==null)dolbyBase?.config?.notice else null
        val summary get()=buildString {
            append("系统识别 ${tracks.size} 条轨道，其中 ${videos.size} 条视频轨。\n")
            tracks.forEach{append(it.label);append(" · ID ${it.id}\n")}
            if(dolbyAliasIds.isNotEmpty())append("Dolby/HEVC同ID为疑似系统编码视图别名；只有容器与标准HDR基础层核验通过后才合并处理。\n")
            if(headerIssue.isNotBlank())append("容器轨道核对：${headerIssue.take(500)}\n")
            processingBlockReason?.let{append(it);append('\n')}
            conversionNotice?.let{append(it);append('\n')}
        }
        fun choose(index:Int?=null):Track {
            require(tracks.size<=64 && tracks.all{it.index>=0} && tracks.map{it.index}.distinct().size==tracks.size){"轨道索引重复、无效或超出安全容量"}
            require(videos.isNotEmpty()){"系统没有识别到视频画面轨道（共 ${tracks.size} 条轨道）。请查看处理记录中的编码信息；不会把音频或未知数据当作视频。"}
            require(processingBlockReason==null){requireNotNull(processingBlockReason)}
            val choices=processingVideos
            val chosen=if(index==null){
                require(choices.size==1){"检测到 ${choices.size} 条视频轨，请先在视频参数中选择要调色的独立画面轨道。"};choices.single()
            }else requireNotNull(choices.singleOrNull{it.index==index}){"所选视频轨道不存在，请重新导入。"}
            require(chosen.supported || (hasDolby && chosen.normalizedMime=="video/dolby-vision")){"所选轨道编码为 ${chosen.normalizedMime}；当前支持AVC/HEVC，不会将不支持的HDR编码自动转为SDR。"}
            require(chosen.width>0 && chosen.height>0 && chosen.durationUs>0){"所选轨道缺少有效画幅或时长，不能作为完整画面处理。"}
            if(headers!=null){
                require(headers.size==choices.size && headers.map{it.id}.distinct().size==headers.size){"系统与容器的视频轨道数量不一致或ID重复，不能将隐藏的画面或增强层忽略。"}
            }
            val header=headers?.firstOrNull{it.id==chosen.id} ?: headers?.singleOrNull()?.takeIf{choices.size==1 && chosen.id<=0}
            require(headers==null || header!=null){"所选系统轨道ID与容器不一致，不能可靠选择画面。"}
            val rotation=if(chosen.rotation==-1)header?.rotation?:0 else chosen.rotation
            require(rotation in setOf(0,90,180,270) && (header==null || header.rotation==rotation)){"所选轨道方向冲突或不能安全保留。"}
            // A multi-video export is a user-authorized single-view export. Prove that
            // each stream is independently addressed, not an enhancement/depth alias.
            if(choices.size>1){
                val h=requireNotNull(headers){"多视频轨的独立性尚不能核验：${headerIssue.take(240)}。未擅自选取第一条轨道。"}
                require(videos.all{it.id>0} && videos.map{it.id}.distinct().size==videos.size){"系统视频轨道ID重复或缺失，可能是同一编码的不同基础层视图；不能擅自拆分。"}
                require(h.map{it.id}.toSet()==videos.map{it.id}.toSet() && h.size==videos.size){"系统与容器的视频轨道ID不一致，不能可靠选择主画面。"}
                val ids=h.map{it.id}.toSet()
                require(h.none{it.unsafe || it.dependencies.any{d->d in ids}}){"视频轨道含相互依赖、增强层、深度或未知描述，不能安全拆分为独立画面。"}
                h.forEach{entry->
                    val t=videos.single{it.id==entry.id}
                    val accepted=when(t.normalizedMime){"video/avc"->setOf("avc1","avc3");"video/hevc"->setOf("hvc1","hev1");else->emptySet()}
                    require(entry.codecs.isNotEmpty() && entry.codecs.all{it in accepted}){"系统与容器的编码描述不一致或不支持独立拆轨。"}
                }
            }
            header?.let{
                require(!it.unsafe){"所选视频含动态增强、加密或未知编码描述，不能按普通AVC/HEVC处理。"}
                val accepted=if(chosen.normalizedMime=="video/avc")setOf("avc1","avc3")else if(hasDolby)setOf("hvc1","hev1","dvh1","dvhe")else setOf("hvc1","hev1")
                require(it.codecs.isNotEmpty() && it.codecs.all{codec->codec in accepted}){"系统与容器的编码描述不一致，不能可靠处理。"}
            }
            return chosen.copy(rotation=rotation)
        }
    }
    /** Bounded, header-only ISO evidence. No sample decoding or metadata values returned. */
    fun headers(file:File,check:()->Unit={}):List<Header> = RandomAccessFile(file,"r").use{f->
        var p=0L;var count=0;var moov:VideoIso.Atom?=null
        while(p<f.length()){
            check();require(++count<=10000 && f.length()-p>=8){"容器头部截断或超出容量"};f.seek(p)
            var n=f.readInt().toLong() and 0xffffffffL;val type=ByteArray(4).also{f.readFully(it)}.toString(Charsets.ISO_8859_1);var head=8
            if(n==1L){require(f.length()-p>=16);n=f.readLong();head=16};if(n==0L)n=f.length()-p
            require(n>=head && n<=f.length()-p){"容器字段长度错误"}
            if(type=="moov"){
                require(moov==null && n<=VideoIso.MAX_BOX){"电影头重复或超出安全容量"};f.seek(p)
                moov=VideoIso.Atom(type,ByteArray(n.toInt()).also{f.readFully(it)},head)
            };p+=n
        }
        val m=requireNotNull(moov){"未找到MP4/MOV轨道头；多轨仅在可核验独立性时处理"}
        require(m.children().none{it.type in setOf("mvex","cmov")}){"分片或压缩多轨结构暂不支持"}
        val tracks=m.children().filter{it.type=="trak"};require(tracks.size in 1..64)
        tracks.mapNotNull{a->
            check();val mdia=a.child("mdia");val handler=VideoIso.text(mdia.child("hdlr").body,8,4)
            if(handler!="vide")null else {
                val tk=a.child("tkhd").body;require(tk.isNotEmpty() && tk[0].toInt() in 0..1)
                val id=VideoIso.u32(tk,if(tk[0].toInt()==1)20 else 12);require(id in 1..Int.MAX_VALUE.toLong())
                val minf=mdia.child("minf");val stbl=minf.child("stbl");val stsd=stbl.child("stsd");val entries=stsd.children(8)
                require(VideoIso.u32(stsd.body,0)==0L && entries.size==VideoIso.u32(stsd.body,4).toInt() && entries.isNotEmpty())
                val refs=a.children().filter{it.type=="tref"}.flatMap{it.children()}
                val dependencies=refs.flatMap{r->val b=r.body;require(b.size%4==0 && b.size<=256);b.indices.step(4).map{VideoIso.i32(b,it)}}.toSet()
                val dolby=entries.singleOrNull()?.let{VideoDolby.entry(it)}
                val ordinary=setOf("avcC","hvcC","colr","pasp","clap","btrt","fiel","mdcv","clli")+if(dolby!=null)setOf("dvcC","dvvC")else emptySet()
                val dref=minf.child("dinf").child("dref");val locations=dref.children(8)
                val external=VideoIso.u32(dref.body,0)!=0L || VideoIso.u32(dref.body,4)!=1L || locations.size!=1 || locations.any{it.type!="url " || it.body.size!=4 || VideoIso.u32(it.body,0)!=1L}
                val unknown=a.children().any{it.type !in setOf("tkhd","mdia","edts","tref","udta","meta","free")} ||
                    stbl.children().any{it.type !in setOf("stsd","stts","ctts","cslg","stsc","stsz","stz2","stco","co64","stss","sdtp","free")}
                val unsafe=external || unknown || entries.any{e->e.type !in (setOf("avc1","avc3","hvc1","hev1")+if(dolby!=null)setOf("dvhe","dvh1")else emptySet()) || e.body.size<78 || VideoIso.u16(e.body,6)!=1 || e.children(78).any{it.type !in ordinary}} || refs.any{it.type !in setOf("tmcd","chap")}
                val matrix=if(tk[0].toInt()==1)52 else 40
                require(tk.size>=matrix+36)
                val values=listOf(0,1,3,4).map{VideoIso.i32(tk,matrix+4*it)}
                val rotation=when(values){listOf(65536,0,0,65536)->0;listOf(0,65536,-65536,0)->90;listOf(-65536,0,0,-65536)->180;listOf(0,-65536,65536,0)->270;else->-1}
                val perspective=VideoIso.i32(tk,matrix+8)!=0 || VideoIso.i32(tk,matrix+20)!=0 || VideoIso.i32(tk,matrix+32)!=0x40000000
                Header(id.toInt(),entries.map{it.type},dependencies,unsafe || perspective || rotation<0,rotation,dolby)
            }
        }.also{h->require(h.map{it.id}.distinct().size==h.size){"容器视频轨道ID重复"}}
    }
}
