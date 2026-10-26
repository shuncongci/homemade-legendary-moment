package xyz.shuncongci.leica

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject

enum class ExternalFolderKind { DIRECTORY, MEDIA, TREE }
data class ExternalFolder(val kind:ExternalFolderKind,val location:String,val label:String,val volume:String="external") {
    fun json()=JSONObject().put("kind",kind.name).put("location",location).put("label",label).put("volume",volume).toString()
    companion object {
        fun parse(value:String?):ExternalFolder?=try { value?.let { JSONObject(it).let{j->ExternalFolder(ExternalFolderKind.valueOf(j.getString("kind")),j.getString("location"),j.getString("label"),j.optString("volume","external"))} } }catch(_:Exception){null}
    }
}
data class ExternalSettings(val folder:ExternalFolder?=null,val style:LeicaStyle=LeicaStyle.DEFAULT,
                            val policy:ExternalExportPolicy=ExternalExportPolicy.SDR,val format:ExportFormat=ExportFormat.DEFAULT,val overlay:Boolean=true,
                            val original:ExternalOriginalPolicy=ExternalOriginalPolicy.KEEP,val metadataWatermark:Boolean=false,
                            val monitor:Boolean=false)
object ExternalPreferences {
    private fun prefs(c:Context)=c.getSharedPreferences("external-camera-mode",Context.MODE_PRIVATE)
    fun load(c:Context):ExternalSettings=prefs(c).let { p->ExternalSettings(ExternalFolder.parse(p.getString("folder",null)),LeicaStyle.fromId(p.getString("style",null)),ExternalExportPolicy.fromId(p.getString("policy",null)),ExportFormat.fromId(p.getString("format",null),Build.VERSION.SDK_INT),p.getBoolean("overlay",true),ExternalOriginalPolicy.fromId(p.getString("original-policy",null)),p.getBoolean("metadata-watermark",false),p.getBoolean("monitor-notification",false)) }
    fun save(c:Context,s:ExternalSettings){check(prefs(c).edit().putString("folder",s.folder?.json()).putString("style",s.style.id).putString("policy",s.policy.id).putString("format",s.format.id).putBoolean("overlay",s.overlay).putString("original-policy",s.original.id).putBoolean("metadata-watermark",s.metadataWatermark).putBoolean("monitor-notification",s.monitor).commit()){"无法保存外接预设"}}
}
object ExternalPermissions {
    fun granted(c:Context,p:String)=ContextCompat.checkSelfPermission(c,p)==PackageManager.PERMISSION_GRANTED
    fun allFiles(c:Context)=if(Build.VERSION.SDK_INT>=30)Environment.isExternalStorageManager() else Build.VERSION.SDK_INT<=28 && granted(c,Manifest.permission.READ_EXTERNAL_STORAGE)
    fun allPhotos(c:Context)=granted(c,if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    fun notifications(c:Context)=(Build.VERSION.SDK_INT<33 || granted(c,Manifest.permission.POST_NOTIFICATIONS)) && androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()
    fun overlay(c:Context)=Settings.canDrawOverlays(c)
    fun battery(c:Context)=(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    fun canRead(c:Context,f:ExternalFolder):Boolean=when(f.kind){
        ExternalFolderKind.DIRECTORY->allFiles(c)
        ExternalFolderKind.MEDIA->allPhotos(c)
        ExternalFolderKind.TREE->c.contentResolver.persistedUriPermissions.any{it.isReadPermission && it.uri==Uri.parse(f.location)}
    }
    fun originalMetadata(c:Context)=Build.VERSION.SDK_INT<29 || granted(c,Manifest.permission.ACCESS_MEDIA_LOCATION)
    fun treeWrite(c:Context,f:ExternalFolder)=f.kind==ExternalFolderKind.TREE && c.contentResolver.persistedUriPermissions.any{it.isWritePermission && it.uri==Uri.parse(f.location)}
    fun startProblem(c:Context,s:ExternalSettings):String? {
        val f=s.folder?:return "请先自动寻找或手动选择相机目录"
        if(!canRead(c,f))return "相机目录读取权限不足或已撤销，请重新授权/选择目录"
        if(Build.VERSION.SDK_INT<=28 && !granted(c,Manifest.permission.WRITE_EXTERNAL_STORAGE))return "Android 8–9 保存到相册需要存储写入权限"
        if(f.kind==ExternalFolderKind.MEDIA && !originalMetadata(c))return "保留媒体库原片的GPS元数据需要“原片位置信息”权限，请在顶部授权。也可改用本机文件目录或系统目录授权；不会静默保存被系统擦除的GPS后删除原片"
        if(s.original==ExternalOriginalPolicy.DELETE_AFTER_SAVE && f.kind==ExternalFolderKind.TREE && !treeWrite(c,f))return "删除原片需要所选目录的读写授权，请在顶部重新授权目录，或选择同时保留"
        return null
    }
}
