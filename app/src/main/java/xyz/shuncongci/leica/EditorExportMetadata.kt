package xyz.shuncongci.leica

import android.content.Context
import java.io.File
import java.util.UUID

/** The source is the durable ORIGINAL import, never the rendered PNG/HDR cache or a preview. */
internal object EditorExportMetadata {
    fun preserve(context:Context,encoded:File,original:File,size:Pair<Int,Int>,badge:Boolean,cancel:Cancel,outputSrgb:Boolean=true):File {
        cancel.check();check(original.isFile && original.length()>0){"原图元数据来源已失效，未发布不完整成片"}
        val bundle=PreservedMetadata.read(original,cancel)
        val target=File(context.cacheDir,"export-metadata-${UUID.randomUUID()}.tmp")
        var skippedInterop=false
        CaptureMetadataContainer.writePreserved(encoded,target,bundle,size.first,size.second,badge,cancel,outputSrgb,onInvalidInterop={skippedInterop=true})
        if(skippedInterop)android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context.applicationContext,"原图有一处异常元数据，已跳过写入成片；原图未改动",android.widget.Toast.LENGTH_LONG).show()
        }
        return target
    }
}
