package xyz.shuncongci.leica

import kotlin.math.*

/** 2.8.6 output-only joint enhancement, after the existing 1.25 exposure.
 * Production supplies JPEG-decoded SDR, NOT a preview or the pre-JPEG model input.
 * The baseline display budget is frozen before enhancement; finished pairs keep that budget.
 * This is an intentional look change, not a claim of lossless colour/contrast or free computation. */
internal object HdrColor286Reference {
    const val ID="hdr-output-bounded-light-chroma-v1"
    private const val E=1.0/64.0
    fun renderKey(base:String,hdr:Boolean)=if(hdr)"$base|$ID"else base
    fun checkedCapacity(value:Float):Float {
        require(value.isFinite() && value>1f){"HDR显示容量无效"};return value
    }
    /** Diagnostic/numeric adapter. Normal Android output uses the final JPEG readback overload. */
    fun apply(base:Planes,hdr:HdrPixels,cancel:Cancel):HdrPixels {
        require(base.width==hdr.w && base.height==hdr.h)
        return apply(hdr,cancel){y,rows,out->base.toArgbRows(y,rows,out)}
    }
    fun apply(hdr:HdrPixels,cancel:Cancel,readBase:(Int,Int,IntArray)->Unit):HdrPixels {
        cancel.check();HdrPixels.dimensions(hdr.w,hdr.h)
        // A capacity-bearing stream is an already-finished output, including a restored HDR2 pair.
        // Do not re-enhance it (or accidentally change its original JPEG reference).
        if(hdr.displayCapacity!=null){checkedCapacity(checkNotNull(hdr.displayCapacity));return hdr}
        val block=min(16,hdr.h);val base=IntArray(hdr.w*block);val row=FloatArray(base.size)
        var high=1e-6f;var peak=1.0001f
        for(y in 0 until hdr.h step block){
            val rows=min(block,hdr.h-y);cancel.check();readBase(y,rows,base)
            for(c in 0..2){hdr.read(c,y,rows,row,cancel)
                for(i in 0 until rows*hdr.w){
                    if(i and 4095==0)cancel.check()
                    val h=row[i];HdrPixels.valid(h)
                    val s=HdrMath.linearByte((base[i] ushr (16-c*8))and 255)
                    high=max(high,ln((h+HdrMath.EPSILON)/(s+HdrMath.EPSILON)));peak=max(peak,h)
                }
            }
        }
        cancel.check();return Enhanced(hdr,checkedCapacity(max(peak,exp(high))),readBase)
    }
    private class Enhanced(private val source:HdrPixels,override val displayCapacity:Float,
                           private val readBase:(Int,Int,IntArray)->Unit):HdrPixels {
        override val w=source.w;override val h=source.h
        private val block=min(16,h)
        private val base=IntArray(w*block)
        private val rgb=Array(3){FloatArray(w*block)}
        private val s=DoubleArray(3);private val original=DoubleArray(3)
        private val out=DoubleArray(3);private val scratch=DoubleArray(12)
        private var cachedY=-1;private var cachedRows=0
        override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){
            HdrPixels.bounds(this,channel,y,rows,values);cancel.check()
            var done=0
            while(done<rows){
                val top=y+done;val start=(top/block)*block;val count=min(block,h-start)
                if(cachedY!=start){
                    // Never publish a partly produced strip, even after cancellation or I/O failure.
                    cachedY=-1;readBase(start,count,base);cancel.check()
                    for(c in 0..2)source.read(c,start,count,rgb[c],cancel)
                    for(i in 0 until count*w){
                        if(i and 255==0)cancel.check()
                        for(c in 0..2){HdrPixels.valid(rgb[c][i]);original[c]=rgb[c][i].toDouble();s[c]=HdrMath.linearByte((base[i] ushr(16-c*8))and 255).toDouble()}
                        transform(s,original,displayCapacity.toDouble(),out,scratch)
                        for(c in 0..2){val value=out[c].toFloat();HdrPixels.valid(value);rgb[c][i]=value}
                    }
                    cancel.check();cachedRows=count;cachedY=start
                }
                val n=min(rows-done,cachedRows-(top-start))
                rgb[channel].copyInto(values,done*w,(top-start)*w,(top-start+n)*w);done+=n
            };cancel.check()
        }
    }
    private fun smooth(a:Double,b:Double,x:Double):Double {val t=((x-a)/(b-a)).coerceIn(0.0,1.0);return t*t*(3-2*t)}
    private fun y(r:Double,g:Double,b:Double)=.2126*r+.7152*g+.0722*b
    /** Allocation-free scalar reference to the approved study. Inputs are endpoint radiance,
     * so no original hidden negative-radiance gain is available or fabricated here. */
    internal fun transform(s:DoubleArray,h:DoubleArray,capacity:Double,out:DoubleArray,t:DoubleArray){
        require(capacity.isFinite() && capacity>1 && s.size>=3 && h.size>=3 && out.size>=3 && t.size>=12)
        for(c in 0..2)require(s[c].isFinite() && s[c] in 0.0..1.0 && h[c].isFinite() && h[c] in 0.0..65504.0)
        val gate=smooth(.12,.60,y(s[0],s[1],s[2]));val B=ln(capacity)/(1+E)
        var margin=1e6;var maxLog=Double.NEGATIVE_INFINITY
        for(c in 0..2){t[c]=(s[c]+E)*exp(B)-E;margin=min(margin,(t[c]-h[c])/max(h[c],1e-30));maxLog=max(maxLog,ln((h[c]+E)/(s[c]+E)))}
        margin=margin.coerceIn(0.0,1e6);val desired=.24*gate
        val delta=desired*margin/max(desired+margin,1e-30)
        for(c in 0..2)out[c]=h[c]*(1+delta)
        if(gate==0.0 || maxLog>=B-1e-9*ln(2.0))return
        val r=out[0]/capacity;val g=out[1]/capacity;val b=out[2]/capacity
        val l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b)
        val m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b)
        val q=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b)
        val L=.2104542553*l+.7936177850*m-.0040720468*q
        val a=1.9779984951*l-2.4285922050*m+.4505937099*q
        val bb=.0259040371*l+.7827717662*m-.8086757660*q
        val want=.08*gate*smooth(.015,.05,hypot(a,bb)/max(L,1e-15))
        if(want<=1e-12)return
        val targetY=y(out[0],out[1],out[2])
        fun proposal(amount:Double):Boolean {
            val aa=a*(1+amount);val ab=bb*(1+amount)
            val v0=.9999999984505196*L+.39633779217376774*aa+.2158037580607588*ab
            val v1=1.0000000088817607*L-.10556134232365633*aa-.0638541747717059*ab
            val v2=1.0000000546724108*L-.08948418209496574*aa-1.2914855378640917*ab
            val ll=v0*v0*v0;val mm=v1*v1*v1;val ss=v2*v2*v2
            val rr=(4.076741661347994*ll-3.3077115904081933*mm+.23096992872942793*ss)*capacity
            val gg=(-1.2684380040921763*ll+2.6097574006633715*mm-.3413193963102196*ss)*capacity
            val bc=(-.00419608654183708*ll-.7034186144594495*mm+1.7076147009309446*ss)*capacity
            val scale=targetY/max(y(rr,gg,bc),1e-30)
            t[3]=rr*scale;t[4]=gg*scale;t[5]=bc*scale
            return (0..2).all{t[it+3].isFinite() && t[it+3]>=0 && t[it+3]<=t[it]}
        }
        var low=0.0;var high=want
        repeat(16){val mid=(low+high)*.5;if(proposal(mid))low=mid else high=mid}
        val applied=low*.98
        if(applied>1e-10 && proposal(applied))for(c in 0..2)out[c]=t[c+3]
    }
}
