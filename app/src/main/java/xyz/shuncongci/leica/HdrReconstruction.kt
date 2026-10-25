package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Pure native-pixel reconstruction shared by Android and equivalence tests.
 * Byte EOTF table is exhaustive (256 exact inputs), NOT an interpolated colour approximation.
 * Bilinear gain stays encoded until interpolation; radiance is reconstructed before exact area integration.
 */
internal object HdrReconstruction {
    fun interface PixelCodes { fun get(x:Int,y:Int,c:Int):Int }
    class Input(val width:Int,val height:Int,val gainWidth:Int,val gainHeight:Int,
        val raw:PixelCodes,val gain:PixelCodes,val eotf:FloatArray,
        val minimum:FloatArray,val maximum:FloatArray,val gamma:FloatArray,
        val epsilonSdr:FloatArray,val epsilonHdr:FloatArray,val p3:Boolean) {
        init {
            require(width>0 && height>0 && gainWidth>0 && gainHeight>0 && eotf.size==256)
            require(eotf.all{it.isFinite() && it>=0f})
            for(a in arrayOf(minimum,maximum,gamma,epsilonSdr,epsilonHdr))require(a.size==3 && a.all{it.isFinite()})
            for(c in 0..2){require(minimum[c]>0f && maximum[c]>=minimum[c] && gamma[c]>0f && epsilonSdr[c]>=0f && epsilonHdr[c]>=0f)}
        }
    }
    data class Result(val hdr:Frame,val sdr:Frame?)
    private class Axis(size:Int,gainSize:Int){
        val low=IntArray(size);val high=IntArray(size);val fraction=FloatArray(size)
        init {for(i in 0 until size){val p=(i+.5)*gainSize/size-.5;val b=floor(p).toInt()
            low[i]=b.coerceIn(0,gainSize-1);high[i]=(b+1).coerceIn(0,gainSize-1);fraction[i]=(p-b).toFloat()}}
    }
    fun render(input:Input,exif:Int,w:Int,h:Int,colored:Planes?,retainSdr:Boolean,cancel:Cancel):Result {
        cancel.check();HdrPixels.dimensions(w,h)
        val hdr=Frame(w,h,3,FloatArray(w*h*3));val sdr=if(retainSdr)Frame(w,h,3,FloatArray(w*h*3))else null
        renderRows(input,exif,w,h,colored,retainSdr,cancel){y,light,base->
            for(c in 0..2){
                System.arraycopy(light,c*w,hdr.data,c*hdr.size+y*w,w)
                if(sdr!=null)System.arraycopy(checkNotNull(base),c*w,sdr.data,c*sdr.size+y*w,w)
            }
        }
        return Result(HdrMath.checked(hdr),sdr)
    }
    /** Exact same per-pixel integration; at most four borrowed RGB Float32 rows, never a full raster.
     * Callbacks may run concurrently for distinct rows and must finish before returning. */
    fun renderRows(input:Input,exif:Int,w:Int,h:Int,colored:Planes?,retainSdr:Boolean,cancel:Cancel,
                   write:(Int,FloatArray,FloatArray?)->Unit) {
        cancel.check()
        val sw=input.width;val sh=input.height;val ow=if(exif in 5..8)sh else sw;val oh=if(exif in 5..8)sw else sh
        require(w in 1..ow && h in 1..oh && w.toLong()*h*3<=Int.MAX_VALUE)
        require(colored==null || colored.width==w && colored.height==h)
        val ax=Axis(sw,input.gainWidth);val ay=Axis(sh,input.gainHeight)
        val logLow=FloatArray(3){ln(input.minimum[it])}
        val logRange=FloatArray(3){ln(input.maximum[it])-logLow[it]}
        val sx=ow.toDouble()/w;val sy=oh.toDouble()/h
        val workers=min(4,min(h,Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        parallelFor(workers){worker->try{
            val a=FloatArray(3);val z=FloatArray(3);val sums=DoubleArray(6)
            val hdr=FloatArray(w*3);val sdr=if(retainSdr)FloatArray(w*3)else null
            fun sample(xx:Int,yy:Int){
                val u:Int;val v:Int
                when(exif){
                    2->{u=sw-1-xx;v=yy};3->{u=sw-1-xx;v=sh-1-yy};4->{u=xx;v=sh-1-yy}
                    5->{u=yy;v=xx};6->{u=yy;v=sh-1-xx};7->{u=sw-1-yy;v=sh-1-xx};8->{u=sw-1-yy;v=xx};else->{u=xx;v=yy}
                }
                val fx=ax.fraction[u];val fy=ay.fraction[v]
                val x0=ax.low[u];val x1=ax.high[u];val y0=ay.low[v];val y1=ay.high[v]
                for(c in 0..2){
                    a[c]=input.eotf[input.raw.get(u,v,c)]
                    // Keep the four multiply/multiply/add steps and their order from 2.5.1.
                    var encoded=0f
                    encoded+=(input.gain.get(x0,y0,c)/255f)*(1-fx)*(1-fy)
                    encoded+=(input.gain.get(x1,y0,c)/255f)*fx*(1-fy)
                    encoded+=(input.gain.get(x0,y1,c)/255f)*(1-fx)*fy
                    encoded+=(input.gain.get(x1,y1,c)/255f)*fx*fy
                    val gain=exp(logLow[c]+logRange[c]*encoded.coerceIn(0f,1f).pow(input.gamma[c]))
                    z[c]=max(0f,(a[c]+input.epsilonSdr[c])*gain-input.epsilonHdr[c])
                }
                if(input.p3){convert(a);convert(z)}
            }
            // Near-native area output (e.g. 8192 -> 8164) otherwise reconstructs most
            // source pixels four times. Reuse exact Float32 samples in <=4 source rows.
            // No full raster, no approximate gain LUT, no changed Double integration order.
            val slots=if(sy<=3 && (w<ow || h<oh))min(oh,ceil(sy).toInt()+1)else 0
            val ids=IntArray(slots){-1};val rows=Array(slots){FloatArray(ow*6)}
            fun sourceRow(yy:Int):FloatArray? {
                if(slots==0)return null
                val slot=yy%slots
                if(ids[slot]!=yy){
                    val row=rows[slot]
                    for(xx in 0 until ow){if(xx and 255==0)cancel.check();sample(xx,yy)
                        for(c in 0..2){row[c*ow+xx]=a[c];row[(c+3)*ow+xx]=z[c]}}
                    ids[slot]=yy
                }
                return rows[slot]
            }
            // Contiguous worker bands allow the boundary source row to be reused too.
            for(y in (h.toLong()*worker/workers).toInt() until (h.toLong()*(worker+1)/workers).toInt()){
                if(failure.get()!=null)return@parallelFor
                cancel.check();val t=y*sy;val b=(y+1)*sy
                for(x in 0 until w){val l=x*sx;val r=(x+1)*sx;sums.fill(0.0)
                    for(yy in floor(t).toInt() until min(oh,ceil(b).toInt())){
                        if(yy and 31==0)cancel.check()
                        val row=sourceRow(yy)
                        for(xx in floor(l).toInt() until min(ow,ceil(r).toInt())){
                            if(row==null)sample(xx,yy)
                            val weight=(min(r,xx+1.0)-max(l,xx.toDouble()))*(min(b,yy+1.0)-max(t,yy.toDouble()))/(sx*sy)
                            for(c in 0..2){sums[c]+=(row?.get(c*ow+xx)?:a[c])*weight;sums[c+3]+=(row?.get((c+3)*ow+xx)?:z[c])*weight}
                        }
                    }
                    for(c in 0..2){val at=c*w+x
                        val s=sums[c].toFloat();val light=sums[c+3].toFloat()
                        valid(s);valid(light)
                        sdr?.set(at,s)
                        val value=if(colored==null)light else HdrMath.transfer(s,light,HdrMath.linearByte(colored.plane(c)[y*w+x].toInt()and 255))
                        valid(value);hdr[at]=value
                    }
                }
                write(y,hdr,sdr)
            }
        }catch(t:Throwable){recordFailure(failure,t)}}
        // Join every producer before the caller closes/deletes its file on error or cancellation.
        failure.get()?.let{throw it};cancel.check()
    }
    /** Join still happens first; a racing cancellation/I/O exception must never mask a fatal worker Error. */
    internal fun recordFailure(failure:java.util.concurrent.atomic.AtomicReference<Throwable?>,t:Throwable){
        failure.updateAndGet{old->if(old==null || (t is Error && old !is Error))t else old}
    }
    private fun valid(v:Float){require(v.isFinite() && v>=0f && v<=65504f){"HDR线性像素无效或超出安全范围，未截断/降SDR"}}
    private fun convert(v:FloatArray){
        val r=1.2249402f*v[0]-.2249402f*v[1];val g=-.04205696f*v[0]+1.042057f*v[1];val b=-.01963755f*v[0]-.07863605f*v[1]+1.0982736f*v[2]
        val y=max(0f,.2126f*r+.7152f*g+.0722f*b);val low=min(r,min(g,b));val t=if(low<0f)y/(y-low).coerceAtLeast(1e-12f)else 1f
        v[0]=max(0f,y+(r-y)*t);v[1]=max(0f,y+(g-y)*t);v[2]=max(0f,y+(b-y)*t)
    }
}
