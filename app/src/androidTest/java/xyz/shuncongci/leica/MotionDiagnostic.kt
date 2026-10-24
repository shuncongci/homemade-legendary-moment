package xyz.shuncongci.leica

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Android test APK only. Checks actual Canvas endpoint pixels and real View.matrix composition.
 * This is NOT a WindowManager/fluidity acceptance test and writes no gallery files. */
object MotionDiagnostic {
    fun run(instrumentation:Instrumentation,context:Context):File {
        val result=AtomicReference<Result<JSONObject>>()
        instrumentation.runOnMainSync{result.set(runCatching{
            var exactPixels=0;val density=context.resources.displayMetrics.density
            for(primary in listOf(false,true)){
                val button=StudioButton(context,primary).apply{text="导出"}
                val w=(120*density).toInt();val h=(56*density).toInt()
                button.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));button.layout(0,0,w,h)
                button.scaleX=.965f;button.scaleY=.965f
                val source=button.beginSharedMorph()
                check(button.scaleX==1f && button.scaleY==1f && button.alpha==0f)
                val panel=ExportUi.MorphSurface(context).apply{
                    origin=ExportMotion.Rect(19f,83f,19f+w,83f+h)
                    target=ExportMotion.Rect(3f,2f,19f+w,83f+h)
                    this.source=source
                }
                panel.layout(0,0,w+40,h+110)
                try{
                    val state=ExportMotion.State();val open=state.open();state.complete(open);val close=state.close()!!;state.complete(close)
                    for(progress in listOf(0f,state.progress)){
                        panel.progress=progress
                        val output=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                        try{
                            panel.draw(Canvas(output));val a=IntArray(w*h);val b=IntArray(w*h)
                            source.bitmap.getPixels(a,0,w,0,0,w,h);output.getPixels(b,0,w,19,83,w,h)
                            check(a.contentEquals(b)){"Native button/morph endpoint pixels differ"};exactPixels+=a.size
                        }finally{output.recycle()}
                    }
                }finally{panel.source=null;button.endSharedMorph()}
                check(button.alpha==1f && button.scaleX==1f && button.scaleY==1f)
            }
            val decor=FrameLayout(context);val parent=FrameLayout(context);val child=View(context)
            decor.addView(parent);parent.addView(child)
            decor.layout(0,0,500,900);parent.layout(10,24,410,824);child.layout(250,600,370,656);parent.scrollTo(0,100)
            val mapped=ExportUi.inDecor(child,decor)
            check(mapped==ExportMotion.Rect(260f,524f,380f,580f)){"Native parent/scroll mapping mismatch: $mapped"}
            JSONObject().put("status","PASS").put("nativeEndpointPixelsChecked",exactPixels).put("sourcePressScaleNormalized",true).put("normalAndPrimaryStylesChecked",true).put("nativeParentScrollMatrixChecked",true)
                .put("windowManagerOrFluidityAcceptance",false)
        })}
        val report=checkNotNull(result.get()).getOrThrow()
        val directory=File(context.filesDir,"export-motion-diagnostic-${UUID.randomUUID()}");check(directory.mkdirs())
        File(directory,"result.json").writeText(report.toString(2));return directory
    }
}
