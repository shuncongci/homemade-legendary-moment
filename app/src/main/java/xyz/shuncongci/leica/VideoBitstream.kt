package xyz.shuncongci.leica

import java.nio.ByteBuffer

/** Bounded AVC/HEVC SPS and NAL inspection. Pixel-significant features fail closed;
 * SEI is not copied into the new encoder, so content-dependent HDR metadata cannot be stale. */
internal object VideoBitstream {
    data class Config(val bits:Int,val width:Int,val height:Int,val primaries:Int?,val transfer:Int?,val matrix:Int?,val full:Boolean?,val sarW:Int,val sarH:Int)
    private class Bits(bytes:ByteArray,head:Int) {
        private val b=VideoCodecConfig.Bits(VideoCodecConfig.rbsp(bytes,head))
        fun n(k:Int)=b.n(k)
        fun skip(k:Int)=b.skip(k)
        fun flag()=n(1)==1
        fun ue(max:Int=1_000_000)=b.ue().also{require(it<=max){"SPS超出有界解析范围"}}
        fun se():Int {val x=ue();return if(x%2==1)(x+1)/2 else -x/2}
    }
    fun record(bytes:ByteArray,hevc:Boolean):ByteArray {
        val out=java.io.ByteArrayOutputStream();var at=if(hevc)23 else 6
        require(bytes.size>=at && bytes[0]==1.toByte())
        fun unit(){require(at<=bytes.size-2);val n=((bytes[at].toInt() and 255) shl 8) or (bytes[at+1].toInt() and 255);at+=2;require(n>0 && n<=bytes.size-at);out.write(byteArrayOf(0,0,0,1));out.write(bytes,at,n);at+=n}
        if(hevc)repeat(bytes[22].toInt() and 255){require(at<=bytes.size-3);at++;val n=((bytes[at].toInt() and 255) shl 8) or (bytes[at+1].toInt() and 255);at+=2;require(n<=1024);repeat(n){unit()}}
        else {repeat(bytes[5].toInt() and 31){unit()};require(at<bytes.size);val n=bytes[at++].toInt() and 255;repeat(n){unit()}}
        return out.toByteArray()
    }
    fun config(csd:ByteArray,hevc:Boolean):Config {
        val nal=VideoCodecConfig.annex(csd).filter{if(hevc)(it[0].toInt() ushr 1) and 63==33 else it[0].toInt() and 31==7}.single()
        val b=Bits(nal,if(hevc)2 else 1)
        return if(hevc)hevc(b) else avc(b)
    }
    private fun avc(b:Bits):Config {
        val profile=b.n(8);b.skip(16);b.ue(31);var bits=8
        if(profile in setOf(100,110,122,244,44,83,86,118,128,138,139,134,135)){
            require(b.ue(3)==1){"仅支持原生4:2:0，不降低色度精度"};bits=b.ue(6)+8;require(b.ue(6)+8==bits);b.skip(1)
            if(b.flag())repeat(8){i->if(b.flag()){var last=8;var next=8;repeat(if(i<6)16 else 64){if(next!=0)next=(last+b.se()+256)%256;last=if(next==0)last else next}}}
        }else require(profile in setOf(66,77,88)){"未知AVC配置"}
        b.ue(12);when(b.ue(2)){0->b.ue(12);1->{b.skip(1);b.se();b.se();repeat(b.ue(255)){b.se()}}}
        b.ue(16);b.skip(1);val w=(b.ue(65535)+1)*16;val h=(b.ue(65535)+1)*16
        require(b.flag()){ "暂不支持隔行视频；不会去隔行或丢场" };b.skip(1)
        var left=0;var right=0;var top=0;var bottom=0
        if(b.flag()){left=b.ue();right=b.ue();top=b.ue();bottom=b.ue()}
        val out=Config(bits,w-2*(left+right),h-2*(top+bottom),null,null,null,null,1,1)
        require(out.width>0 && out.height>0)
        return if(b.flag())vui(b,out,false) else out
    }
    private fun hevc(b:Bits):Config {
        b.skip(4);val sub=b.n(3);require(sub<=6);b.skip(1);b.skip(96)
        val flags=List(sub){b.flag() to b.flag()};if(sub>0)b.skip((8-sub)*2)
        flags.forEach{(p,l)->if(p)b.skip(88);if(l)b.skip(8)}
        b.ue(15);require(b.ue(3)==1){"仅支持原生4:2:0，不降低色度精度"}
        var w=b.ue(1_048_576);var h=b.ue(1_048_576)
        if(b.flag()){val l=b.ue();val r=b.ue();val t=b.ue();val d=b.ue();w-=2*(l+r);h-=2*(t+d)}
        val depth=b.ue(8)+8;require(b.ue(8)+8==depth);val log=b.ue(12)+4
        val all=b.flag();for(i in (if(all)0 else sub)..sub){b.ue(16);b.ue(16);b.ue()}
        repeat(6){b.ue(32)}
        if(b.flag() && b.flag())for(size in 0..3){var matrix=0;while(matrix<6){if(!b.flag())b.ue(5) else {if(size>1)b.se();repeat(minOf(64,1 shl (4+2*size))){b.se()}};matrix+=if(size==3)3 else 1}}
        b.skip(2);if(b.flag()){b.skip(8);b.ue(32);b.ue(32);b.skip(1)}
        val rps=ArrayList<Int>();repeat(b.ue(64)){i->
            if(i>0 && b.flag()){b.skip(1);b.ue();var count=0;repeat(rps[i-1]+1){val used=b.flag();val delta=if(used)true else b.flag();if(delta)count++};require(count<=64);rps+=count}
            else {val neg=b.ue(32);val pos=b.ue(32);repeat(neg+pos){b.ue();b.skip(1)};rps+=neg+pos}
        }
        if(b.flag())repeat(b.ue(32)){b.skip(log);b.skip(1)}
        b.skip(2);val out=Config(depth,w,h,null,null,null,null,1,1);require(w>0 && h>0)
        return if(b.flag())vui(b,out,true) else out
    }
    private fun vui(b:Bits,c:Config,hevc:Boolean):Config {
        var sw=1;var sh=1
        if(b.flag()){
            val aspect=b.n(8)
            val ratios=listOf(1 to 1,1 to 1,12 to 11,10 to 11,16 to 11,40 to 33,24 to 11,20 to 11,32 to 11,80 to 33,18 to 11,15 to 11,64 to 33,160 to 99,4 to 3,3 to 2,2 to 1)
            if(aspect==255){sw=b.n(16);sh=b.n(16);require(sw>0 && sh>0)}else {require(aspect in ratios.indices);sw=ratios[aspect].first;sh=ratios[aspect].second}
        }
        if(b.flag())b.skip(1)
        var full:Boolean?=null;var p:Int?=null;var t:Int?=null;var m:Int?=null
        if(b.flag()){b.skip(3);full=b.flag();if(b.flag()){p=b.n(8);t=b.n(8);m=b.n(8)}}
        if(b.flag()){b.ue(5);b.ue(5)}
        if(hevc){b.skip(1);require(!b.flag()){ "暂不支持隔行HEVC；不会去隔行或丢场" };b.skip(1);if(b.flag()){val offsets=List(4){b.ue()};require(offsets.all{it==0}){"暂不支持非零默认显示窗口；不会静默裁切"}}}
        return c.copy(primaries=p,transfer=t,matrix=m,full=full,sarW=sw,sarH=sh)
    }
    /** Accept Annex B or 4-byte length framing; reject enhancement layers and midstream SPS changes. */
    fun sample(buffer:ByteBuffer,size:Int,hevc:Boolean,csd:ByteArray):Boolean {
        require(size in 1..VideoIso.MAX_SAMPLE && size<=buffer.capacity())
        val b=buffer.duplicate().apply{position(0);limit(size)}
        val ranges=ArrayList<IntRange>()
        fun u(i:Int)=b.get(i).toInt() and 255
        fun prefix(i:Int):Int=if(i+4<=size && u(i)==0 && u(i+1)==0 && u(i+2)==0 && u(i+3)==1)4 else if(i+3<=size && u(i)==0 && u(i+1)==0 && u(i+2)==1)3 else 0
        if(prefix(0)>0){var at=0;while(at<size){val n=prefix(at);require(n>0);val start=at+n;at=start;while(at<size && prefix(at)==0)at++;require(at>start);ranges+=start until at;require(ranges.size<=65536)}}
        else {var at=0;while(at<size){require(size-at>=4);val n=(0..3).fold(0L){a,j->(a shl 8)+u(at+j)};at+=4;require(n>0 && n<=size-at);ranges+=at until at+n.toInt();at+=n.toInt();require(ranges.size<=65536)}}
        val known=VideoCodecConfig.annex(csd);var sei=false;var vcl=false
        for(r in ranges){val first=u(r.first);require(first and 128==0);val type=if(hevc)(first ushr 1) and 63 else first and 31
            if(hevc){require(r.last>r.first);val second=u(r.first+1);require((first and 1)==0 && (second ushr 3)==0 && second and 7!=0){"不支持HEVC增强层或非法NAL"};require(type !in setOf(62,63)){"检测到杜比视界/专有增强数据；不能作为普通HDR静默处理"};if(type<=31)vcl=true;if(type==39 || type==40)sei=true}
            else {require(type !in setOf(14,15,19,20,21)){"不支持AVC扩展层"};if(type in 1..5)vcl=true;if(type==6)sei=true}
            if(type==if(hevc)33 else 7){val bytes=ByteArray(r.last-r.first+1);b.position(r.first);b.get(bytes);require(known.any{VideoCodecConfig.rbsp(it,if(hevc)2 else 1).contentEquals(VideoCodecConfig.rbsp(bytes,if(hevc)2 else 1))}){"逐帧SPS发生变化，不能保证尺寸与色彩一致"}}
        }
        require(vcl){"样本没有独立视频画面；不能按一帧处理"};return sei
    }
}
