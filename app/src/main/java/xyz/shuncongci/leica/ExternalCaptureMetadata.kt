package xyz.shuncongci.leica

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.ExifInterface
import java.io.File

/** External mode only. Read metadata from the SAME private input used by the renderer, never a sample,
 * preview or earlier output. Source JPEG/PNG/WebP EXIF is preserved byte-exact except output geometry,
 * color interpretation and the explicitly controlled gallery badge. Platform fallback keeps originals.
 */
object ExternalCaptureMetadata {
    data class Prepared(val exif:ByteArray,val complete:Boolean)
    fun openOriginal(context:Context,uri:Uri):java.io.InputStream {
        val original=if(Build.VERSION.SDK_INT>=29 && uri.scheme=="content" && uri.authority=="media"){
            check(ExternalPermissions.originalMetadata(context)){"原片位置信息权限不足，未用被擦除GPS的副本代替原片"}
            MediaStore.setRequireOriginal(uri)
        }else uri
        return context.contentResolver.openInputStream(original)?:error("原片不可读取")
    }
    private val captureTags=listOf(
        "Make","Model","Software","Artist","Copyright","ImageDescription","UserComment","DateTime","DateTimeOriginal","DateTimeDigitized",
        "SubSecTime","SubSecTimeOriginal","SubSecTimeDigitized","OffsetTime","OffsetTimeOriginal","OffsetTimeDigitized",
        "ExposureTime","FNumber","ISOSpeedRatings","PhotographicSensitivity","SensitivityType","StandardOutputSensitivity","RecommendedExposureIndex","ISOSpeed",
        "ExposureProgram","ShutterSpeedValue","ApertureValue","BrightnessValue","ExposureBiasValue","MaxApertureValue","SubjectDistance",
        "MeteringMode","LightSource","Flash","FocalLength","FocalLengthIn35mmFilm","WhiteBalance","ExposureMode","SceneCaptureType","CustomRendered","DigitalZoomRatio",
        "Contrast","Saturation","Sharpness","GainControl","SubjectDistanceRange","SensingMethod","SceneType","FileSource","ImageUniqueID",
        "CameraOwnerName","BodySerialNumber","LensMake","LensModel","LensSpecification","LensSerialNumber",
        "GPSVersionID","GPSLatitudeRef","GPSLatitude","GPSLongitudeRef","GPSLongitude","GPSAltitudeRef","GPSAltitude","GPSTimeStamp","GPSDateStamp",
        "GPSSatellites","GPSStatus","GPSMeasureMode","GPSDOP","GPSSpeedRef","GPSSpeed","GPSTrackRef","GPSTrack","GPSImgDirectionRef","GPSImgDirection",
        "GPSMapDatum","GPSDestLatitudeRef","GPSDestLatitude","GPSDestLongitudeRef","GPSDestLongitude","GPSDestBearingRef","GPSDestBearing","GPSDestDistanceRef","GPSDestDistance",
        "GPSProcessingMethod","GPSAreaInformation","GPSDifferential","GPSHPositioningError")
    private fun platformExif(source:File,work:File,cancel:Cancel):ByteArray {
        cancel.check();val input=ExifInterface(source.absolutePath)
        check(work.isDirectory || work.mkdirs()){"无法建立元数据工作目录"}
        val carrier=File(work,"capture-exif-carrier.jpg");check(!carrier.exists())
        val bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try{carrier.outputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,out));out.fd.sync()}}finally{bitmap.recycle()}
        val output=ExifInterface(carrier.absolutePath)
        captureTags.forEach{tag->cancel.check();input.getAttribute(tag)?.let{output.setAttribute(tag,it)}}
        // Only this tiny EXIF carrier is touched by platform saveAttributes, NEVER a gainmap image.
        output.saveAttributes();cancel.check()
        return CaptureMetadataContainer.readExif(carrier).exif?:error("平台未能转存原片拍摄EXIF，原片保留")
    }
    fun prepare(source:File,work:File,width:Int,height:Int,watermark:Boolean,cancel:Cancel):Prepared {
        cancel.check();val captured=CaptureMetadataContainer.readExif(source)
        val packet=if(captured.supported)captured.exif else platformExif(source,work,cancel)
        val exif=packet?.let(::CaptureExif)?:CaptureExif.empty()
        val normalized=exif.normalized(width,height,XiaomiLeicaBadge.patches(exif,watermark))
        cancel.check()
        return Prepared(normalized,captured.supported && !captured.otherMetadata)
    }
}
