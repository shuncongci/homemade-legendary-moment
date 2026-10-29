package xyz.shuncongci.leica

/** Output-only linear exposure. The original, fused SDR and raw render checkpoints stay untouched.
 * Apply only at LeicaEngine's final HDR handoff, before storing the finished pair/JPEG. Finished
 * pairs and JPEGs are already exposed: crop, restore and publish must never apply this again. */
internal object HdrOutputBrightness {
    const val GAIN=1.25f // approximately +0.322 EV; same scalar for R/G/B, no new tone curve
    const val ID="hdr-output-linear-1.25-v1"
    fun renderKey(base:String,hdr:Boolean)=if(hdr)"$base|$ID"else base
    fun apply(input:HdrPixels):HdrPixels {
        HdrPixels.dimensions(input.w,input.h)
        return if(input is Brightened)input else Brightened(input)
    }
    private class Brightened(private val source:HdrPixels):HdrPixels {
        override val w=source.w
        override val h=source.h
        override fun read(channel:Int,y:Int,rows:Int,values:FloatArray,cancel:Cancel){
            HdrPixels.bounds(this,channel,y,rows,values);cancel.check()
            source.read(channel,y,rows,values,cancel)
            for(i in 0 until rows*w){
                if(i and 4095==0)cancel.check()
                val v=values[i];HdrPixels.valid(v)
                val exposed=v*GAIN
                // No clipping, peak-dependent dimming or automatic SDR success on exposure overflow.
                if(!exposed.isFinite() || exposed>65504f)throw ArithmeticException("HDR提亮后超出安全范围；未截断或转SDR")
                values[i]=exposed
            }
            cancel.check()
        }
    }
}
