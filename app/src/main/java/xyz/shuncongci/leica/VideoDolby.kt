package xyz.shuncongci.leica

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Dolby -> ordinary HEVC, with an explicitly compatible standard HDR base layer.
 * Never relabel profile 5 IPT as BT.2020. No Dolby encoder/old RPU reuse.
 * Syntax: Dolby Vision ISOBMFF DOVIDecoderConfigurationRecord and profiles 8.1/8.4.
 */
internal object VideoDolby {
    data class Config(val profile:Int,val level:Int,val rpu:Boolean,val enhancement:Boolean,val base:Boolean,val compatibility:Int,val advanced:Boolean=false) {
        val blockReason:String? get()=when {
            profile!=8 -> "杜比profile $profile 不是当前可直接转换的profile 8兼容基础层；不能仅改HEVC标签造成偏色。"
            !base || enhancement -> "杜比基础层缺失或含增强层，当前不能可靠合成为普通HEVC。"
            !rpu || advanced || level !in 1..13 -> "杜比配置缺少RPU标记或含未知级别/扩展，不能确认兼容基础层。"
            compatibility !in setOf(1,4) -> "杜比基础层兼容ID $compatibility 不是当前支持的HDR10/PQ或HLG。"
            else -> null
        }
        val transfer get()=when(compatibility){1->16;4->18;else->error("Unsupported Dolby compatibility")}
        val outputLabel get()="普通HEVC · ${if(compatibility==4)"HLG"else "PQ"} 10bit · 不含杜比视界"
        val notice get()="杜比视界输入将转换为$outputLabel。只使用核验通过的标准HDR基础层，移除旧RPU和杜比动态元数据，不保留杜比显示效果；原文件不修改。"
    }
    data class Base(val config:Config,val csd:ByteArray)
    fun config(bytes:ByteArray):Config {
        require(bytes.size==24 && bytes[0]==1.toByte() && bytes[1]==0.toByte()){"未知或截断的杜比配置记录"}
        fun u(i:Int)=bytes[i].toInt() and 255
        return Config(u(2) ushr 1,((u(2) and 1) shl 5) or (u(3) ushr 3),u(3) and 4!=0,u(3) and 2!=0,u(3) and 1!=0,u(4) ushr 4,
            (u(4) and 15)!=0 || (5 until bytes.size).any{u(it)!=0})
    }
    /** Strict HEVC configuration framing for the conversion path only. Ordinary videos
     * keep the existing parser. Unknown arrays/trailing data cannot hide an EL. */
    fun hevcRecord(bytes:ByteArray):ByteArray {
        require(bytes.size in 23..VideoIso.MAX_BOX && bytes[0]==1.toByte()){"HEVC配置记录截断"}
        fun u(i:Int)=bytes[i].toInt() and 255
        require(u(1) and 223==2 && u(16) and 3==1 && u(17) and 7==2 && u(18) and 7==2 && u(21) and 3==3){"基础层须为Main10/4:2:0及四字节NAL长度"}
        val out=ByteArrayOutputStream();var at=23
        repeat(u(22)){
            require(at<=bytes.size-3);val type=u(at++) and 63
            require(type in setOf(32,33,34,39,40,62)){"HEVC配置含未知或增强层数组"}
            val count=(u(at) shl 8) or u(at+1);at+=2;require(count in 1..1024)
            repeat(count){
                require(at<=bytes.size-2);val n=(u(at) shl 8) or u(at+1);at+=2
                require(n>=2 && n<=bytes.size-at && (u(at) ushr 1) and 63==type){"HEVC配置NAL长度或类型冲突"}
                out.write(byteArrayOf(0,0,0,1));out.write(bytes,at,n);at+=n
            }
        }
        require(at==bytes.size){"HEVC配置含未识别尾部"}
        return cleanConfig(out.toByteArray())
    }
    /** Malformed/unknown records remain unsafe in VideoTracks, never silently ordinary. */
    fun entry(entry:VideoIso.Atom):Base? {
        if(entry.body.size<78 || entry.type !in setOf("hvc1","hev1","dvh1","dvhe"))return null
        val kids=entry.children(78);val dv=kids.filter{it.type in setOf("dvcC","dvvC")}
        if(dv.isEmpty())return null
        return try {
            require(dv.size==1)
            val cfg=config(dv.single().body)
            val csd=hevcRecord(kids.single{it.type=="hvcC"}.body)
            val signal=VideoBitstream.config(csd,true)
            val w=VideoIso.u16(entry.body,24);val h=VideoIso.u16(entry.body,26)
            require(w==signal.width && h==signal.height){"容器与基础层尺寸冲突"}
            val colors=kids.filter{it.type=="colr"};require(colors.size<=1)
            colors.singleOrNull()?.body?.let{b->
                require(b.size>=10);val type=VideoIso.text(b,0,4)
                require((type=="nclx" && b.size==11) || (type=="nclc" && b.size==10)){"未知基础层容器色彩描述"}
                require(VideoIso.u16(b,4)==signal.primaries && VideoIso.u16(b,6)==signal.transfer && VideoIso.u16(b,8)==signal.matrix){"容器与基础层SPS色彩冲突"}
                if(type=="nclx")require((b[10].toInt() and 127)==0 && (b[10].toInt() and 128!=0)==signal.full){"容器与基础层量化范围冲突"}
            }
            Base(cfg,csd)
        } catch(_:IllegalArgumentException){null} catch(_:NoSuchElementException){null}
    }
    fun cleanConfig(csd:ByteArray):ByteArray {
        require(csd.size in 1..VideoIso.MAX_BOX && csd.size>=4 && csd[0]==0.toByte() && csd[1]==0.toByte() && (csd[2]==1.toByte() || (csd[2]==0.toByte() && csd[3]==1.toByte()))){"缺少完整Annex-B配置"}
        val units=VideoCodecConfig.annex(csd)
        val kept=units.filter{n->
            require(n.size>=2 && n[0].toInt() and 128==0 && n[0].toInt() and 1==0 && (n[1].toInt() and 255) ushr 3==0 && n[1].toInt() and 7!=0){"HEVC配置含增强层或非法NAL"}
            val t=(n[0].toInt() ushr 1) and 63
            require(t in setOf(32,33,34,39,40,62)){"HEVC配置含未知NAL"};t in 32..34
        }.sortedBy{(it[0].toInt() ushr 1) and 63}
        require(kept.map{(it[0].toInt() ushr 1) and 63}==listOf(32,33,34)){"兼容基础层须有唯一VPS/SPS/PPS"}
        val v=VideoCodecConfig.Bits(VideoCodecConfig.rbsp(kept[0],2));v.skip(4)
        require(v.n(1)==1 && v.n(1)==1 && v.n(6)==0){"HEVC VPS并非独立单层基础画面"}
        val s=VideoCodecConfig.Bits(VideoCodecConfig.rbsp(kept[1],2));s.skip(8)
        require(s.n(2)==0);s.skip(1);require(s.n(5)==2){"HEVC SPS并非标准Main10"}
        return kept.flatMap{(byteArrayOf(0,0,0,1)+it).asIterable()}.toByteArray()
    }
    fun requireSameConfig(system:ByteArray,container:ByteArray) {
        val a=VideoCodecConfig.annex(cleanConfig(system));val b=VideoCodecConfig.annex(cleanConfig(container))
        require(a.size==b.size && a.zip(b).all{(x,y)->VideoCodecConfig.rbsp(x,2).contentEquals(VideoCodecConfig.rbsp(y,2))}){"系统与容器的HEVC基础层编码配置不一致"}
    }
    fun color(base:Base,width:Int,height:Int):VideoContract.Color {
        require(base.config.blockReason==null){base.config.blockReason.orEmpty()}
        val s=VideoBitstream.config(base.csd,true)
        require(s.bits==10 && s.width==width && s.height==height){"杜比基础层不是所选原尺寸10bit画面"}
        require(s.primaries==9 && s.matrix==9 && s.transfer==base.config.transfer && s.full!=null){"杜比兼容ID与基础层SPS色彩不一致或缺少明确标记；不能猜测色彩"}
        return VideoContract.Color(9,base.config.transfer,9,requireNotNull(s.full),10).also{it.validate()}
    }
    /** Compact in place, keeping Annex-B or four-byte length framing. No frame-size
     * copy. Drop RPU and all input SEI BEFORE plain HEVC decoding; every VCL byte stays.
     * Inspection and feeding both call this. Never absorb cancellation or unknown EL.
     */
    fun cleanSample(buffer:ByteBuffer,size:Int,csd:ByteArray,check:()->Unit={}):Int {
        require(size in 1..VideoIso.MAX_SAMPLE && size<=buffer.capacity() && !buffer.isReadOnly)
        val known=VideoCodecConfig.annex(csd)
        val b=buffer.duplicate().apply{clear();limit(size)}
        fun u(i:Int)=b.get(i).toInt() and 255
        fun prefix(i:Int)=if(i+4<=size && u(i)==0 && u(i+1)==0 && u(i+2)==0 && u(i+3)==1)4 else if(i+3<=size && u(i)==0 && u(i+1)==0 && u(i+2)==1)3 else 0
        fun lengthFramed():Boolean {
            var p=0;var nals=0
            while(p<size){check();if(size-p<4 || ++nals>65536)return false;val n=(0..3).fold(0L){a,i->(a shl 8)+u(p+i)};p+=4;if(n<2 || n>size-p)return false;p+=n.toInt()}
            return true
        }
        // A four-byte length of 256..511 begins 00 00 01 too. Prove the entire
        // length-framed packet before treating that ambiguous prefix as Annex-B.
        val annex=prefix(0)>0 && (prefix(0)==4 || !lengthFramed());var at=0;var out=0;var count=0;var vcl=false
        while(at<size) {
            check();require(++count<=65536){"NAL数量超出安全容量"}
            val start=at;val head:Int;val end:Int
            if(annex){val n=prefix(at);require(n>0);head=at+n;at=head;while(at<size && prefix(at)==0){if(at and 65535==0)check();at++};end=at}
            else{require(size-at>=4);val n=(0..3).fold(0L){a,i->(a shl 8)+u(at+i)};head=at+4;require(n>=2 && n<=size-head);end=head+n.toInt();at=end}
            require(end-head>=2)
            val a=u(head);val c=u(head+1);val type=(a ushr 1) and 63
            require(a and 128==0 && a and 1==0 && c ushr 3==0 && c and 7!=0){"基础层样本含HEVC增强层或非法NAL，未导出错误画面"}
            require(type in 0..9 || type in 16..21 || type in 32..40 || type==62){"基础层样本含未知/专有增强NAL $type"}
            if(type in 32..34){
                val n=ByteArray(end-head);b.position(head);b.get(n)
                require(known.any{((it[0].toInt() ushr 1) and 63)==type && VideoCodecConfig.rbsp(it,2).contentEquals(VideoCodecConfig.rbsp(n,2))}){"逐帧VPS/SPS/PPS变化，不能确认原基础层"}
            }
            if(type<=31)vcl=true
            if(type !in setOf(39,40,62)){
                if(out==start)out+=end-start else for(i in start until end){if(i and 65535==0)check();b.put(out++,b.get(i))}
            }
        }
        require(vcl && out>0){"杜比样本没有独立基础层画面"}
        check();return out
    }
    fun verifyOutput(file:File,check:()->Unit={}) {
        val h=VideoTracks.headers(file,check).single()
        require(!h.unsafe && h.dolby==null && h.codecs.all{it in setOf("hvc1","hev1")}){"成片仍含杜比或未知视频描述，未发布"}
        RandomAccessFile(file,"r").use{f->
            var at=0L;var boxes=0;var ftypes=0
            while(at<f.length()){
                check();require(++boxes<=10000 && f.length()-at>=8);f.seek(at);var n=f.readInt().toLong() and 0xffffffffL
                val t=ByteArray(4).also{f.readFully(it)}.toString(Charsets.ISO_8859_1);var head=8
                if(n==1L){require(f.length()-at>=16);n=f.readLong();head=16};if(n==0L)n=f.length()-at;require(n>=head && n<=f.length()-at)
                if(t=="moov"){
                    require(n<=VideoIso.MAX_BOX);f.seek(at);val m=VideoIso.Atom(t,ByteArray(n.toInt()).also{f.readFully(it)},head)
                    val entries=m.children().filter{it.type=="trak"}.map{it.child("mdia")}.filter{VideoIso.text(it.child("hdlr").body,8,4)=="vide"}.flatMap{it.child("minf").child("stbl").child("stsd").children(8)}
                    for(e in entries){val record=e.children(78).single{it.type=="hvcC"}.body;hevcRecord(record)
                        require(VideoCodecConfig.annex(VideoBitstream.record(record,true)).all{((it[0].toInt() ushr 1) and 63) in setOf(32,33,34,39,40)}){"成片HEVC配置仍含旧RPU或增强数据，未发布"}}
                }
                if(t=="ftyp"){
                    require(++ftypes==1 && n<=1024*1024 && (n-head)%4==0L && n-head>=8)
                    val b=ByteArray((n-head).toInt()).also{f.readFully(it)}
                    val brands=(0 until b.size step 4).filter{it!=4}.map{String(b,it,4,Charsets.ISO_8859_1)}
                    require(brands.none{it.startsWith("dv") || it.matches(Regex("db[0-9]."))}){"成片错误沿用了杜比品牌，未发布"}
                };at+=n
            }
            require(ftypes==1){"成片缺少明确普通HEVC文件类型"}
        }
    }
}
