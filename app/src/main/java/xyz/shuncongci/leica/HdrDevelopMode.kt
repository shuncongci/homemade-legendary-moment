package xyz.shuncongci.leica

/** AUTO is retained for existing diagnostic callers. The editor always chooses HDR or SDR explicitly. */
enum class HdrDevelopMode {
    AUTO, HDR, SDR;
    val strict get()=this==HDR
    fun wantsHdr(detected:()->Boolean):Boolean=when(this){AUTO->detected();HDR->true;SDR->false}
    fun requirePlatform(sdk:Int){require(!strict || sdk>=34){"HDR显影需要Android 14或更高版本"}}
    fun requireOutput(hdrReady:Boolean,externalReceiver:Boolean=false){
        check(!strict || hdrReady || externalReceiver){"HDR显影未生成HDR成片，未发布为SDR"}
        check(this!=SDR || (!hdrReady && !externalReceiver)){"SDR显影不能复用HDR成片"}
    }
    fun renderKey(base:String)="$base|dynamic-range=$name"
    companion object {
        fun restore(value:String?,sourceHdr:Boolean):HdrDevelopMode =
            if(!sourceHdr)SDR else if(value==SDR.name)SDR else HDR
    }
}

/** Pure display/routing policy, independently tested without treating it as device/display acceptance. */
object HdrDisplayPolicy {
    fun splitActions(sourceHdr:Boolean,onlyComposition:Boolean)=sourceHdr && !onlyComposition
    // Three former equal buttons -> 1/6, 1/3, 1/3, 1/6 (excluding inter-button spacing).
    fun actionWeights(split:Boolean)=if(split)listOf(.5f,1f,1f,.5f) else listOf(1f,1f,1f)
    fun reuseForComposition(composeAfter:Boolean,hasResult:Boolean,stale:Boolean,requested:HdrDevelopMode,resultHdr:Boolean)=
        composeAfter && hasResult && !stale && requested!=HdrDevelopMode.AUTO && ((requested==HdrDevelopMode.HDR)==resultHdr)
    fun windowHdr(sdk:Int,visible:Boolean,originalShown:Boolean,resultShown:Boolean,originalGain:Boolean,resultGain:Boolean)=
        sdk>=34 && visible && ((originalShown && originalGain)||(resultShown && resultGain))
    fun nativeDetailAllowed(hasGainmap:Boolean)=!hasGainmap
    fun resultFile(sdr:java.io.File,hdr:java.io.File,ready:Boolean)=if(ready)hdr else sdr
}
