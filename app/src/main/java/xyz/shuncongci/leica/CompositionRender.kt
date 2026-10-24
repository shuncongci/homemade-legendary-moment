package xyz.shuncongci.leica

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/** Re-render the full scene at enough source-derived pixels, then crop to the final budget. */
object CompositionRender {
    fun save(context:Context,original:File,plan:CompositionOutput.Plan,appearance:CompositionAppearance,
             output:BokehOutput,cancel:Cancel,format:ExportFormat=ExportPreferences.load(context),keepHdr:Boolean=false,progress:(String)->Unit={},engineChanged:(LeicaEngine?)->Unit):String {
        if(keepHdr)require(android.os.Build.VERSION.SDK_INT>=34){"HDR需要Android14+"}
        if(!appearance.color && !appearance.blur){progress("正在保存裁剪像素");return if(keepHdr)HdrPhotoExport.saveSource(context,Uri.fromFile(original),plan.crop,cancel,plan.width to plan.height)else CompositionFiles.save(context,original,plan.crop,cancel,plan.width to plan.height,format)}
        val directory=File(context.cacheDir,"composition-render-${UUID.randomUUID()}")
        var rendered:LeicaEngine.Result?=null
        try {
            if(keepHdr)HdrSource.checkInput(context,Uri.fromFile(original),plan.workWidth,plan.workHeight)
            LeicaEngine(context,resultDirectory=directory).use{engine->
                engineChanged(engine)
                cancel.check()
                var pair:Pair<Planes,LegendaryMath.Frame>?=null
                rendered=engine.process(Uri.fromFile(original),appearance.style,cancel,appearance.blur,appearance.focus,
                    appearance.aperture,output,appearance.color,plan.workWidth to plan.workHeight,
                    hdrOutput=if(keepHdr)({sdr,hdr->pair=sdr to hdr})else null){progress(it)}
                val result=checkNotNull(rendered)
                check(!appearance.blur || result.rendered){"该焦点无法生成虚化，请返回重新选焦"}
                cancel.check()
                progress("正在裁剪并保存")
                if(keepHdr){val (sdr,hdr)=checkNotNull(pair);return HdrPairExport.save(context,sdr,hdr,plan.crop,plan.width to plan.height,cancel)}
                return CompositionFiles.save(context,result.file,plan.crop,cancel,plan.width to plan.height,format)
            }
        }finally{
            engineChanged(null);rendered?.original?.recycle();rendered?.result?.recycle();directory.deleteRecursively()
        }
    }
}
