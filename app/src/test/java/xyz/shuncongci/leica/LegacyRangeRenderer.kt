package xyz.shuncongci.leica
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*
object LegacyRangeRenderer {
    // Historical fixtures keep their original fixed .25-linear extraction. They are
    // not an oracle for the intentional 2.1.12 adaptive extraction (tested separately).
    private fun historicalSplit(p:Planes,cancel:Cancel):Pair<Frame,Frame>{
        fun linear(x:Float)=if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)
        fun srgb(x:Float)=if(x<=.0031308f)12.92f*max(x,0f) else 1.055f*max(x,0f).pow(1/2.4f)-.055f
        val base=Frame(p.width,p.height,3,FloatArray(3*p.size));val high=Frame(p.width,p.height,3,FloatArray(3*p.size))
        for(i in 0 until p.size){if(i%p.width==0)cancel.check()
            val v=FloatArray(3){linear((p.plane(it)[i].toInt() and 255)/255f)};val ratio=min(1f,.25f/max(v.maxOrNull()!!,1e-8f))
            for(ch in 0..2){val at=ch*p.size+i;base.data[at]=(Math.rint((srgb(v[ch]*ratio).coerceIn(0f,1f)*255).toDouble())/255).toFloat();high.data[at]=max(0f,v[ch]-linear(base.data[at]))}
        };return base to high
    }
    fun render(colored: Planes,depth: Frame,focus: Float,aperture: Float,cancel: Cancel,progress: (String)->Unit = {},highlightRenderer:(Frame,Frame,Float,Cancel)->Frame = LegendaryMath::scatter,highlightSplitter:(Planes,Cancel)->Pair<Frame,Frame> = ::historicalSplit,focusRangeSteps:Int=0,highlightBoost:Float=1f,infer: (Frame,Frame)->Frame): Planes {
        val coc=FocusRangeMath.coc(depth,focus,aperture,colored.width,colored.height,focusRangeSteps,cancel)
        return LegendaryMath.renderCoc(colored,coc,aperture,cancel,progress=progress,highlightRenderer=highlightRenderer,highlightSplitter=highlightSplitter,highlightBoost=highlightBoost,infer=infer)
    }
}
