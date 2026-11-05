package xyz.shuncongci.leica

import android.content.Context
import java.io.File
import java.util.UUID

/** The source is the durable ORIGINAL import, never the rendered PNG/HDR cache or a preview. */
internal object EditorExportMetadata {
    fun preserve(context:Context,encoded:File,original:File,size:Pair<Int,Int>,badge:Boolean,cancel:Cancel,outputSrgb:Boolean=true):File {
        cancel.check()
        val prepared=ExportMetadataCompatibility.prepare(original,size.first,size.second,badge,cancel,outputSrgb)
        val target=File(context.cacheDir,"export-metadata-${UUID.randomUUID()}.tmp")
        val written=ExportMetadataCompatibility.write(encoded,target,prepared,size.first,size.second,badge,cancel,outputSrgb)
        if(written.degraded)android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context.applicationContext,"拍摄信息已按兼容方式处理；可继续保存，异常详情见监测",android.widget.Toast.LENGTH_LONG).show()
        }
        return target
    }
}
