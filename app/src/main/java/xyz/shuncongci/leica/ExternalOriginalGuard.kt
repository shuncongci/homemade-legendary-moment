package xyz.shuncongci.leica

import java.io.InputStream
import java.security.MessageDigest

enum class ExternalOriginalPolicy(val id:String,val label:String) {
    KEEP("keep","同时保留原片和成片"), DELETE_AFTER_SAVE("delete-after-save","成片保存成功后删除原片");
    companion object { fun fromId(id:String?)=entries.firstOrNull{it.id==id}?:KEEP }
}
enum class OriginalAction { KEPT, WAIT_PERMISSION, DELETING, DELETED, TRASHED, CHANGED, FAILED, DECLINED, EXPIRED, UNKNOWN }
data class OriginalOutcome(val action:OriginalAction,val message:String)

/** The only gate through which external-mode source removal may run. No library scans or cold-start retries. */
object ExternalOriginalGuard {
    fun sameIdentity(expected:ExternalPhoto,observed:ExternalPhoto?,fileIdentityRequired:Boolean=false,expectedFileKey:String="",observedFileKey:String=""):Boolean =
        observed!=null && !observed.pending && expected.key==observed.key && expected.uri==observed.uri && expected.name==observed.name &&
            expected.created>0 && observed.created==expected.created && expected.sameBytes(observed) &&
            (!fileIdentityRequired || expectedFileKey.isNotEmpty() && observedFileKey==expectedFileKey)
    fun digest(input:InputStream,check:()->Unit={}):String {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        input.use{stream->while(true){check();val n=stream.read(buffer);if(n<0)break;if(n>0)hash.update(buffer,0,n)}}
        check();return hash.digest().joinToString(""){"%02x".format(it)}
    }
    /** Verify against the PRE-publication private file, never trust a digest first learned from storage. */
    fun verifyCopy(expected:String,input:InputStream,check:()->Unit={}) {
        val observed=digest(input,check)
        require(expected.matches(Regex("[a-f0-9]{64}")) && observed==expected){"成片存储回读与发布前指纹不一致；未发布、原片保留"}
    }
    fun afterPublished(policy:ExternalOriginalPolicy,published:Boolean,cancel:Cancel,active:()->Boolean,
                       sourceMatches:()->Boolean,outputMatches:()->Boolean,remove:()->OriginalOutcome):OriginalOutcome {
        if(policy!=ExternalOriginalPolicy.DELETE_AFTER_SAVE)return OriginalOutcome(OriginalAction.KEPT,"原片与成片同时保留")
        if(!published)return OriginalOutcome(OriginalAction.KEPT,"成片未成功发布，原片保留")
        cancel.check()
        if(!active())return OriginalOutcome(OriginalAction.EXPIRED,"监听已停止或不是本次任务，原片不补删")
        if(!sourceMatches())return OriginalOutcome(OriginalAction.CHANGED,"原片身份/字节已变化或无法完整核对，未删除")
        cancel.check()
        if(!outputMatches())return OriginalOutcome(OriginalAction.FAILED,"成片校验未通过，原片保留")
        cancel.check()
        if(!active())return OriginalOutcome(OriginalAction.EXPIRED,"监听已停止，原片不补删")
        return remove() // No cancellation reclassification AFTER a successful irreversible operation.
    }
}
