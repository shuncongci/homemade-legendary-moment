package xyz.shuncongci.leica

/** UI routing is separate from the engine's three non-empty rendering modes. */
enum class PhotoWorkflow(val label:String,val processing:ProcessingMode?,val composeAfter:Boolean) {
    COLOR("仅调色",ProcessingMode.COLOR_ONLY,false),
    BLUR("仅虚化",ProcessingMode.BLUR_ONLY,false),
    COLOR_BLUR("调色＋虚化",ProcessingMode.COLOR_AND_BLUR,false),
    COMPOSITION("仅构图",null,false),
    COLOR_COMPOSITION("调色＋构图",ProcessingMode.COLOR_ONLY,true),
    BLUR_COMPOSITION("虚化＋构图",ProcessingMode.BLUR_ONLY,true),
    ALL("调色＋虚化＋构图",ProcessingMode.COLOR_AND_BLUR,true);
    val allowsOutputSelection get()=processing?.blurEnabled==true
    fun output(selected:BokehOutput)=if(allowsOutputSelection)selected else BokehOutput.ORIGINAL
    val onlyComposition get()=processing==null
    fun canStart(flow:PhotoFlow)=if(onlyComposition)!flow.busy && flow.hasPhoto else flow.canGenerate
    fun shouldOpenComposition(completed:Boolean,cancelled:Boolean)=composeAfter && completed && !cancelled
}

/** Display-only interpolation; these intermediate frames never become export coordinates. */
object CompositionMotion {
    fun ease(progress:Float):Double {
        require(progress.isFinite())
        val t=progress.toDouble().coerceIn(0.0,1.0)
        return t*t*t*(t*(t*6-15)+10)
    }
    fun mix(a:CompositionGeometry.Crop,b:CompositionGeometry.Crop,progress:Float):CompositionGeometry.Crop {
        val t=ease(progress)
        if(t<=0)return a
        if(t>=1)return b
        fun v(x:Double,y:Double)=(x+(y-x)*t).coerceIn(0.0,1.0)
        return CompositionGeometry.Crop(v(a.l,b.l),v(a.t,b.t),v(a.r,b.r),v(a.b,b.b))
    }
}
