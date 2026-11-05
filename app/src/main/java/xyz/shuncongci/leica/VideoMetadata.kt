package xyz.shuncongci.leica

import java.util.concurrent.CancellationException

/** Optional metadata only. Required pixel/codec/I/O/publishing never use this policy.
 * Optional metadata I/O may fail after a complete verified encode; retain that valid fallback. */
internal class VideoMetadata {
    private val entries=LinkedHashSet<String>()
    private val information=LinkedHashSet<String>()
    val records:List<String> get()=warnings+information.toList()
    fun note(message:String){if(information.size<24)information+=message.take(2048)}
    val warnings:List<String> get()=entries.toList()
    fun warn(message:String){if(entries.size<24)entries+=message.take(2048)}
    fun <T> attempt(stage:String,action:()->T):T? = try {action()} catch(e:Exception) {
        if(e is CancellationException || e is SecurityException || e is InterruptedException || e is java.io.InterruptedIOException)throw e
        warn("$stage：${e.javaClass.simpleName} · ${e.message.orEmpty()}；已跳过这部分信息，仍可导出。")
        null
    }
    fun summary(saved:Boolean):String = (if(saved)"视频已导出。" else "成片已就绪，可导出。")+
        if(entries.isEmpty())"" else "\n有元数据保留问题（${entries.size}项），仍可导出；具体错误见处理记录。"
}

/** Reads the precision fields before configuring a decoder: no requested-format 10→8-bit disguise. */
internal object VideoCodecConfig {
    class Bits(private val bytes:ByteArray) {
        private var at=0
        fun n(count:Int):Int{require(count in 0..31 && at.toLong()+count<=bytes.size.toLong()*8);var x=0;repeat(count){x=(x shl 1) or ((bytes[at/8].toInt() ushr (7-at%8)) and 1);at++};return x}
        fun skip(count:Int){require(count>=0 && at.toLong()+count<=bytes.size.toLong()*8);at+=count}
        fun ue():Int{var z=0;while(n(1)==0){z++;require(z<=29)};return ((1L shl z)-1+n(z)).toInt()}
    }
    fun rbsp(nal:ByteArray,head:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();var zeros=0
        for(i in head until nal.size){val x=nal[i].toInt() and 255;if(zeros>=2 && x==3){require(i+1<nal.size && (nal[i+1].toInt() and 255)<=3);zeros=0;continue};out.write(x);zeros=if(x==0)zeros+1 else 0}
        return out.toByteArray()
    }
    fun annex(bytes:ByteArray):List<ByteArray> {
        val marks=ArrayList<Pair<Int,Int>>();var p=0
        while(p+3<=bytes.size){val n=if(p+4<=bytes.size && bytes[p]==0.toByte() && bytes[p+1]==0.toByte() && bytes[p+2]==0.toByte() && bytes[p+3]==1.toByte())4 else if(bytes[p]==0.toByte() && bytes[p+1]==0.toByte() && bytes[p+2]==1.toByte())3 else 0
            if(n>0){marks+=p to n;p+=n}else p++}
        require(marks.isNotEmpty()){"缺少可核验的编码配置"}
        return marks.indices.map{i->bytes.copyOfRange(marks[i].first+marks[i].second,if(i+1<marks.size)marks[i+1].first else bytes.size)}.also{require(it.all{n->n.isNotEmpty()})}
    }
    fun bits(csd:ByteArray,hevc:Boolean):Int {
        val units=annex(csd);val sps=units.filter{if(hevc)((it[0].toInt() ushr 1) and 63)==33 else (it[0].toInt() and 31)==7};require(sps.size==1){"多SPS配置暂不支持"}
        val b=Bits(rbsp(sps.single(),if(hevc)2 else 1))
        if(hevc){b.skip(4);val sub=b.n(3);b.skip(1);b.skip(96);val flags=List(sub){b.n(1) to b.n(1)};if(sub>0)b.skip((8-sub)*2);flags.forEach{(p,l)->if(p==1)b.skip(88);if(l==1)b.skip(8)};b.ue();require(b.ue()==1){"不支持降低原始色度精度"};b.ue();b.ue();if(b.n(1)==1)repeat(4){b.ue()};val depth=b.ue()+8;require(b.ue()+8==depth);return depth}
        val profile=b.n(8);b.skip(16);b.ue()
        if(profile in setOf(100,110,122,244,44,83,86,118,128,138,139,134,135)){require(b.ue()==1){"不支持降低原始色度精度"};val depth=b.ue()+8;require(b.ue()+8==depth);return depth}
        require(profile in setOf(66,77,88)){"未知AVC位深"};return 8
    }
}
