package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Marker: this callback consumes full-resolution, signed pixel-radius CoC,
 * not the normalized [-1,1] ARNet defocus. Renderer identity belongs in the job key. */
internal interface FullCocRenderer : (Frame,Frame)->Frame

/** 64 soft signed-CoC planes, far -> near, matching the selected local prototype.
 * Disk PSFs use the same 4x4 pixel-area samples and REFLECT_101 boundary.
 * Only one plane and bounded source-row prefixes are live; never 64 full-size images.
 * This is the SDR base renderer ONLY: production highlight/subject/HDR code stays outside.
 */
object LayeredBokeh {
    const val ID="layered64-circle4x4-gamma22-v1"
    const val LAYERS=64
    private fun reflect(x:Int,n:Int):Int {
        if(n==1)return 0
        val period=2L*(n-1);var v=x.toLong()%period;if(v<0)v+=period
        return (if(v<n)v else period-v).toInt()
    }
    internal fun weight(value:Float,z:Float,step:Float)=max(0f,1f-abs(value-z)/step)
    internal fun disk(radius:Float):LegendaryMath.Kernel {
        require(radius.isFinite() && radius>=0f)
        if(radius<.35f)return LegendaryMath.Kernel(0,floatArrayOf(1f))
        val half=ceil(radius.toDouble()+.5).toInt();val w=2*half+1
        require(w.toLong()*w<=Int.MAX_VALUE)
        val k=FloatArray(w*w);var sum=0
        val rr=radius.toDouble()*radius
        for(y in -half..half)for(x in -half..half){
            var hits=0
            for(yy in 0..3)for(xx in 0..3){val dx=x+(xx+.5)/4-.5;val dy=y+(yy+.5)/4-.5;if(dx*dx+dy*dy<=rr)hits++}
            k[(y+half)*w+x+half]=hits.toFloat();sum+=hits
        }
        require(sum>0)
        for(i in k.indices)k[i]/=sum.toFloat()
        return LegendaryMath.Kernel(half,k)
    }
    private class Row(val dy:Int,val a:Int,val b:Int,val run:Float,val tx:IntArray,val tw:FloatArray,val lo:Int,val hi:Int)
    private fun rows(k:LegendaryMath.Kernel):Array<Row> {
        val out=ArrayList<Row>();val full=k.data.maxOrNull()!!
        for(y in 0 until k.w){
            var a=-1;var b=-2;val offset=y*k.w
            for(x in 0 until k.w)if(k.data[offset+x]==full){if(a<0)a=x;b=x}
            if(a>=0)for(x in a..b)if(k.data[offset+x]!=full){a=-1;b=-2;break}
            val tx=ArrayList<Int>();val tw=ArrayList<Float>()
            for(x in 0 until k.w){val v=k.data[offset+x];if(v!=0f && (a<0 || x<a || x>b)){tx.add(x);tw.add(v)}}
            if(a<0 && tx.isEmpty())continue
            out.add(Row(y-k.half,a,b,full,tx.toIntArray(),tw.toFloatArray(),min(if(a<0)Int.MAX_VALUE else a,tx.minOrNull()?:Int.MAX_VALUE),max(b,tx.maxOrNull()?:-1)))
        }
        return out.toTypedArray()
    }
    /** Float32 geometry/colors with Double local row sums, no precision/size downgrade.
     * Uniform-depth inputs are a single opaque plane (the Python prototype's zero-step
     * division was undefined); zero/near-zero defocus returns an exact source copy.
     */
    internal data class Field(val w:Int,val h:Int,val low:Float,val high:Float){init{require(w>0 && h>0 && low.isFinite() && high.isFinite() && low<=high)}}
    fun render(rgb:Frame,coc:Frame,cancel:Cancel,onLayer:(Int,Int)->Unit={_,_->}):Frame = renderField(rgb,coc,cancel,null,onLayer)
    internal fun renderRegion(rgb:Frame,coc:Frame,cancel:Cancel,field:Field,onLayer:(Int,Int)->Unit={_,_->}):Frame = renderField(rgb,coc,cancel,field,onLayer)
    private fun renderField(rgb:Frame,coc:Frame,cancel:Cancel,field:Field?,onLayer:(Int,Int)->Unit):Frame {
        cancel.check()
        require(rgb.channels==3 && coc.channels==1 && rgb.w==coc.w && rgb.h==coc.h)
        require(rgb.data.all{it.isFinite() && it>=0f && it<=1f} && coc.data.all{it.isFinite()})
        val w=rgb.w;val h=rgb.h;val n=rgb.size
        var low=Float.POSITIVE_INFINITY;var high=Float.NEGATIVE_INFINITY
        for(y in 0 until h){cancel.check();for(i in y*w until (y+1)*w){low=min(low,coc.data[i]);high=max(high,coc.data[i])}}
        if(max(abs(low),abs(high))<=.25f)return Frame(w,h,3,rgb.data.copyOf())
        // Thin-lens maximum is dimension-derived. Do not silently clip a user's CoC.
        require(max(abs(low),abs(high))<=max(54f,FullFrameOptics.maxRadius(field?.w?:w,field?.h?:h)+.01f)){"虚化半径超出光学范围"}
        if(field!=null){require(low>=field.low && high<=field.high);low=field.low;high=field.high}
        val uniform=high==low
        val nominal=if(uniform)floatArrayOf(low) else FloatArray(LAYERS){i->(low.toDouble()+i*(high.toDouble()-low)/(LAYERS-1)).toFloat()}
        // A sub-ULP 64-bin grid cannot have a positive Float32 step. Retain the
        // two exact endpoints instead of dividing by zero or rounding input CoC.
        val grid=if(nominal.size>1 && nominal[1]<=nominal[0])floatArrayOf(low,high) else nominal
        val step=if(uniform)1f else grid[1]-grid[0]
        require(step.isFinite() && step>0f){"深度分层间隔无效"}
        val present=BooleanArray(grid.size*h)
        // Only adjacent tent planes have support. Check a conservative +/-1 index
        // margin to retain Float32 linspace boundary rounding, never threshold pixels.
        for(y in 0 until h){cancel.check();for(i in y*w until (y+1)*w){
            if(uniform){present[y]=true;continue}
            val q=floor(((coc.data[i]-low)/step).toDouble()).toInt().coerceIn(0,grid.lastIndex)
            for(level in max(0,q-1)..min(grid.lastIndex,q+2))if(weight(coc.data[i],grid[level],step)>0f)present[level*h+y]=true
        }}
        val linear=FloatArray(rgb.data.size)
        parallelFor(3*h){row->cancel.check();for(i in row*w until (row+1)*w)linear[i]=rgb.data[i].pow(2.2f)}
        val out=Frame(w,h,3,FloatArray(rgb.data.size));val coverage=FloatArray(n)
        val maxHalf=ceil(max(abs(low),abs(high)).toDouble()+.5).toInt();val stride=w+2*maxHalf+1
        val band=64;val capacity=min(h,band+2*maxHalf)
        require(stride.toLong()*capacity<=Int.MAX_VALUE)
        val mp=DoubleArray(stride*capacity);val rp=DoubleArray(mp.size);val gp=DoubleArray(mp.size);val bp=DoubleArray(mp.size)
        val tags=IntArray(capacity){-1};val pmin=IntArray(h);val pmax=IntArray(h)
        val accumulator=ThreadLocal.withInitial{DoubleArray(4*w)}
        for(level in grid.indices){
            cancel.check();val z=grid[level];val li=level*h
            if(!(0 until h).any{present[li+it]}){onLayer(level+1,grid.size);continue}
            val k=disk(abs(z));val plans=rows(k);val half=k.half;val padded=w+2*half
            val col=IntArray(padded){reflect(it-half,w)};tags.fill(-1)
            for(y0 in 0 until h step band){
                cancel.check();val y1=min(h,y0+band);val sy0=max(0,y0-half);val sy1=min(h,y1+half)
                parallelFor(sy1-sy0){r->
                    val sy=sy0+r;val slot=sy%capacity
                    if(tags[slot]==sy)return@parallelFor
                    cancel.check();tags[slot]=sy
                    if(!present[li+sy]){pmin[sy]=-1;return@parallelFor}
                    val base=slot*stride;val src=sy*w;var lo=-1;var hi=-1
                    var m=0.0;var red=0.0;var green=0.0;var blue=0.0
                    mp[base]=0.0;rp[base]=0.0;gp[base]=0.0;bp[base]=0.0
                    for(x in 0 until padded){
                        val i=src+col[x];val a=if(uniform)1f else weight(coc.data[i],z,step)
                        if(a>0f){
                            // Round premultiplication to Float32 like the prototype before summation.
                            m+=a;red+=(linear[i]*a).toDouble();green+=(linear[n+i]*a).toDouble();blue+=(linear[2*n+i]*a).toDouble()
                            if(lo<0)lo=x;hi=x
                        }
                        mp[base+x+1]=m;rp[base+x+1]=red;gp[base+x+1]=green;bp[base+x+1]=blue
                    }
                    pmin[sy]=lo;pmax[sy]=hi
                }
                parallelFor(y1-y0){r->
                    val y=y0+r;cancel.check();val a=checkNotNull(accumulator.get());java.util.Arrays.fill(a,0.0)
                    var touched=false
                    for(row in plans){
                        val sy=reflect(y+row.dy,h);if(!present[li+sy] || pmin[sy]<0)continue
                        val x0=max(0,pmin[sy]-row.hi);val x1=min(w-1,pmax[sy]-row.lo);if(x0>x1)continue
                        touched=true;val base=(sy%capacity)*stride;val src=sy*w
                        if(row.a>=0)for(x in x0..x1){
                            val p0=base+x+row.a;val p1=base+x+row.b+1;val mass=mp[p1]-mp[p0]
                            if(mass<=0.0)continue
                            val kw=row.run.toDouble();a[x]+=kw*mass;a[w+x]+=kw*(rp[p1]-rp[p0]);a[2*w+x]+=kw*(gp[p1]-gp[p0]);a[3*w+x]+=kw*(bp[p1]-bp[p0])
                        }
                        for(t in row.tx.indices){val kx=row.tx[t];val kw=row.tw[t].toDouble()
                            for(x in x0..x1){
                                val i=src+col[x+kx];val alpha=if(uniform)1f else weight(coc.data[i],z,step);if(alpha<=0f)continue
                                a[x]+=kw*alpha;a[w+x]+=kw*(linear[i]*alpha);a[2*w+x]+=kw*(linear[n+i]*alpha);a[3*w+x]+=kw*(linear[2*n+i]*alpha)
                            }
                        }
                    }
                    if(!touched)return@parallelFor
                    for(x in 0 until w){
                        val alpha=a[x].toFloat().coerceIn(0f,1f);if(alpha<=0f)continue
                        val i=y*w+x;val keep=1f-alpha
                        coverage[i]=coverage[i]*keep+alpha
                        for(c in 0..2){val at=c*n+i;out.data[at]=out.data[at]*keep+max(0.0,a[(c+1)*w+x]).toFloat()}
                    }
                }
            }
            cancel.check();onLayer(level+1,grid.size)
        }
        parallelFor(h){y->cancel.check();for(i in y*w until (y+1)*w){
            val mass=max(coverage[i],1e-8f);val mix=((abs(coc.data[i])-.25f)/.75f).coerceIn(0f,1f)
            for(c in 0..2){val at=c*n+i;val blurred=(out.data[at]/mass).coerceIn(0f,1f).pow(1f/2.2f)
                out.data[at]=rgb.data[at]*(1f-mix)+blurred*mix
            }
        }}
        cancel.check();return out
    }
}
