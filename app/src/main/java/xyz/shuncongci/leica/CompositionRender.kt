package xyz.shuncongci.leica

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/** Re-render the full scene at enough source-derived pixels, then crop to the final budget. */
object CompositionRender {
    fun save(context:Context,original:File,plan:CompositionOutput.Plan,appearance:CompositionAppearance,
             output:BokehOutput,cancel:Cancel,progress:(String)->Unit={},engineChanged:(LeicaEngine?)->Unit):String {
        if(!appearance.color && !appearance.blur){progress("正在保存裁剪像素");return CompositionFiles.save(context,original,plan.crop,cancel,plan.width to plan.height)}
        val directory=File(context.cacheDir,"composition-render-${UUID.randomUUID()}")
        var rendered:LeicaEngine.Result?=null
        try {
            LeicaEngine(context,resultDirectory=directory).use{engine->
                engineChanged(engine)
                cancel.check()
                rendered=engine.process(Uri.fromFile(original),LeicaStyle.LEGENDARY,cancel,appearance.blur,appearance.focus,
                    appearance.aperture,output,appearance.color,plan.workWidth to plan.workHeight){progress(it)}
                val result=checkNotNull(rendered)
                check(!appearance.blur || result.rendered){"该焦点无法生成虚化，请返回重新选焦"}
                cancel.check()
                progress("正在裁剪并保存")
                return CompositionFiles.save(context,result.file,plan.crop,cancel,plan.width to plan.height)
            }
        }finally{
            engineChanged(null);rendered?.original?.recycle();rendered?.result?.recycle();directory.deleteRecursively()
        }
    }
}
