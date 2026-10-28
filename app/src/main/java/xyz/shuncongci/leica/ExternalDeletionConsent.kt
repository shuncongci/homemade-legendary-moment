package xyz.shuncongci.leica

/** A new consent version must not silently inherit an older, stricter deletion choice. */
object ExternalDeletionConsent {
    const val VERSION=1
    const val TITLE="开启保存后删除原片？"
    const val MESSAGE="我建议重要照片先备份。开启后，只要成片保存并校验成功，就会尝试删除原片。\n\n动态照片的视频和声音、原始 HDR、人像编辑数据及部分拍摄信息可能无法完整保留，也不会因此阻止删除。成片不是原片的完整备份，以后可能无法恢复原来的效果或重新编辑。\n\n支持时移到系统回收站；直接文件目录或部分授权目录可能永久删除。请不要把回收站当作备份。\n\n保存失败、任务取消或文件发生变化时不会删除。系统需要授权时仍要由你确认。只处理本次监听的新照片，停止或重启后不补删旧照片。\n\n如果你只需要最终成片，也接受这些损失，再开启这个选项。"
    const val ACCEPT="我已了解，开启删除"
    const val DECLINE="保留原片"
    fun restored(policy:ExternalOriginalPolicy,version:Int)=
        if(policy==ExternalOriginalPolicy.DELETE_AFTER_SAVE && version!=VERSION)ExternalOriginalPolicy.KEEP else policy
    fun canRemove(policy:ExternalOriginalPolicy,version:Int,verified:Boolean)=
        policy==ExternalOriginalPolicy.DELETE_AFTER_SAVE && version==VERSION && verified
}
