package xyz.shuncongci.leica

import java.math.BigInteger
import java.nio.ByteBuffer

/** MediaCodec PTS stay exact. BMFF stores integral ticks; only demonstrable rational
 * clock conversion is allowed. No fixed tolerance, FPS regeneration, sorting or offset fitting. */
internal object VideoMuxTiming {
    data class Edit(val mediaTime:Long=0,val empty:Long=0,val movieScale:Long=1,val segment:Long?=null) {
        init { require(mediaTime>=0 && empty>=0 && movieScale>0 && (segment==null || segment>0)) }
    }
    private fun b(v:Long)=BigInteger.valueOf(v)
    private fun bounds(n:BigInteger,d:BigInteger):LongRange {
        require(d.signum()>0)
        val (q,r)=n.divideAndRemainder(d)
        val lo=if(r.signum()<0)q.subtract(BigInteger.ONE)else q
        val hi=if(r.signum()>0)q.add(BigInteger.ONE)else q
        return lo.longValueExact()..hi.longValueExact()
    }
    private fun interval(v:Long,n:Long,d:Long)=bounds(b(v)*b(n),b(d))
    private fun tickInterval(us:Long,scale:Long,e:Edit):LongRange = bounds(
        b(us)*b(scale)*b(e.movieScale)+b(e.mediaTime)*b(1_000_000)*b(e.movieScale)-b(e.empty)*b(scale)*b(1_000_000),
        b(1_000_000)*b(e.movieScale))
    private fun usInterval(t:Long,scale:Long,e:Edit):LongRange = bounds(
        (b(t)-b(e.mediaTime))*b(1_000_000)*b(e.movieScale)+b(e.empty)*b(1_000_000)*b(scale),b(scale)*b(e.movieScale))

    /** Only an explicit rate-one single segment, optionally preceded by one empty edit.
     * Never infer an offset from sample values or normalize the first timestamp to zero. */
    fun edit(mvhd:ByteArray,edts:VideoIso.Atom?):Edit {
        if(edts==null)return Edit()
        require(mvhd.isNotEmpty() && mvhd[0].toInt() in 0..1)
        val scale=VideoIso.u32(mvhd,if(mvhd[0].toInt()==1)20 else 12);require(scale>0)
        val fields=edts.children();require(fields.size==1 && fields[0].type=="elst"){"输出编辑时序不明确，已阻止保存"}
        val a=fields.single().body;require(a.size>=8 && a[0].toInt() in 0..1 && a.sliceArray(1..3).all{it==0.toByte()})
        val v1=a[0].toInt()==1;val count=VideoIso.u32(a,4);val stride=if(v1)20 else 12
        require(count in 1..2 && a.size==8+count.toInt()*stride){"输出编辑分段不能完整核验"}
        var empty=0L;var media=0L;var segment=0L
        for(i in 0 until count.toInt()){
            val p=8+i*stride
            val duration=if(v1)ByteBuffer.wrap(a,p,8).long else VideoIso.u32(a,p)
            val time=if(v1)ByteBuffer.wrap(a,p+8,8).long else VideoIso.i32(a,p+4).toLong()
            val rate=VideoIso.i32(a,p+if(v1)16 else 8)
            require(duration>0 && rate==65536){"输出包含变速、停帧或无效编辑，已阻止保存"}
            if(i==0 && count==2L){require(time==-1L);empty=duration}
            else {require(time>=0);media=time;segment=duration}
        }
        return Edit(media,empty,scale,segment)
    }
    /** A delay must be explicitly encoded, with no media trim, rate change or inferred
     * offset. Only rational conversion of the two declared clocks is accepted. An
     * integral delay admits exactly one value: no +/- tick or millisecond allowance. */
    fun delayedHeader(header:Long,media:Long,scale:Long,e:Edit):Boolean {
        val segment=e.segment?:return false
        return header>media && media>0 && scale>0 && e.empty>0 && e.mediaTime==0L &&
            header-media in interval(e.empty,scale,e.movieScale) &&
            segment in interval(media,e.movieScale,scale)
    }
    fun verifyMovie(expected:LongArray,actual:List<Long>,movie:VideoIso.Movie,durationUs:Long,actualDurationUs:Long) {
        val t=movie.video;val edits=t.atom.children().filter{it.type=="edts"};require(edits.size<=1)
        val e=edit(movie.moov.child("mvhd").body,edits.singleOrNull())
        val end=Math.addExact(t.samples.last().pts,t.samples.last().duration)
        verify(expected,actual,t.samples.map{it.pts},t.scale,durationUs,actualDurationUs,e,end)
    }
    fun verify(expected:LongArray,actual:List<Long>,ticks:List<Long>,scale:Long,
               durationUs:Long,actualDurationUs:Long,e:Edit=Edit(),endTicks:Long?=null) {
        require(scale in 10_000..0xffffffffL){"输出时间基过粗或无效（$scale tick/s），不能核验原帧时序"}
        require(expected.isNotEmpty() && expected.size==actual.size && actual.size==ticks.size){
            "输出帧数与原片不同，已阻止保存：原片${expected.size}帧，回读${actual.size}帧，容器${ticks.size}帧"
        }
        require(expected.first()>=0 && actual.first()>=0 && ticks.first()>=e.mediaTime &&
            (1 until expected.size).all{expected[it]>expected[it-1] && actual[it]>actual[it-1] && ticks[it]>ticks[it-1]}){
            "输出帧顺序、重复、裁去样本或负时间戳异常，已阻止保存"
        }
        for(i in expected.indices){
            require(ticks[i] in tickInterval(expected[i],scale,e)){
                "输出帧时间戳变化，已阻止保存：MP4回读第${i+1}帧，原片${expected[i]}µs，回读${actual[i]}µs，容器${ticks[i]} tick，时间基$scale tick/s；超出单tick量化范围"
            }
            require(actual[i] in usInterval(ticks[i],scale,e)){
                "输出编辑时序与样本表不一致，已阻止保存：第${i+1}帧，回读${actual[i]}µs，容器${ticks[i]} tick/$scale"
            }
        }
        require(durationUs>expected.last() && actualDurationUs>actual.last()){"输出末帧时长无效，已阻止保存"}
        val ends=tickInterval(durationUs,scale,e)
        val outputEnds=if(endTicks==null)ends else endTicks..endTicks
        require((endTicks==null || endTicks in ends) && outputEnds.any{actualDurationUs in usInterval(it,scale,e)}){
            "输出末帧时长或总时长发生变化，已阻止保存：原片${durationUs}µs，回读${actualDurationUs}µs，时间基$scale tick/s"
        }
        e.segment?.let{segment->
            require(b(ticks.last()-e.mediaTime)*b(e.movieScale)<b(segment)*b(scale) &&
                // The endpoint is already quantized to the media clock. Convert it
                // to the movie clock, not a second exact comparison to source us.
                outputEnds.any{segment in bounds((b(it)-b(e.mediaTime))*b(e.movieScale),b(scale))}){
                "输出编辑段裁切或改变了末帧/总时长，已阻止保存"
            }
        }
    }
}
