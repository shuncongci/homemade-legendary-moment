package xyz.shuncongci.leica

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

/** Selected URI is read once; preview, tiles and inference share an unchanged private byte copy.
 * Separate from leica result cache, which is cleared after every preview/generation.
 */
class PhotoImports(private val context:Context):AutoCloseable {
    private val base=File(context.noBackupFilesDir,"legendary-photo-imports")
    private val directory=File(base,UUID.randomUUID().toString())
    init {
        // Only stale private copies left by a terminated process; never gallery originals.
        base.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>24L*60*60*1000}?.forEach{it.deleteRecursively()}
    }
    private fun open(uri:Uri):InputStream {
        val resolver=context.contentResolver
        // MediaStore must provide original capture GPS, never silently import its redacted rendition.
        if(PhotoImportPolicy.original(android.os.Build.VERSION.SDK_INT,uri.scheme,uri.authority,uri.pathSegments.firstOrNull()))return ExternalCaptureMetadata.openOriginal(context,uri)
        try { return resolver.openInputStream(uri) ?: throw FileNotFoundException("照片来源没有返回数据") }
        catch(first:FileNotFoundException) {
            // Some document providers offer a typed file instead of a regular descriptor.
            if(uri.scheme!="content")throw first
            try {
                val descriptor=resolver.openTypedAssetFileDescriptor(uri,"image/*",null)
                    ?: throw first
                return try{descriptor.createInputStream()}catch(e:Exception){descriptor.close();throw e}
            } catch(second:FileNotFoundException){second.addSuppressed(first);throw second}
        }
    }
    fun import(uri:Uri,check:()->Unit = {}):Uri {
        try { return Uri.fromFile(PhotoCopy.stage(directory,{open(uri)},check)) }
        catch(e:FileNotFoundException) {
            throw IllegalArgumentException(PhotoImportPolicy.MISSING,e)
        } catch(e:SecurityException) {
            throw IllegalArgumentException(PhotoImportPolicy.PERMISSION,e)
        }
    }
    override fun close(){directory.deleteRecursively()}
}
