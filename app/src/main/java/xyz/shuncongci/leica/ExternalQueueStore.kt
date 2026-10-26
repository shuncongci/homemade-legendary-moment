package xyz.shuncongci.leica

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable publish journal, NOT a library backlog. No method re-enqueues jobs from an older run. */
class ExternalQueueStore(context:Context):SQLiteOpenHelper(context,"external-camera-journal.db",null,2) {
    data class Job(val id:String,val run:String,val photo:ExternalPhoto,val state:String,val outputName:String,val outputUri:String)
    override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, run TEXT NOT NULL, source_key TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, state TEXT NOT NULL, output_name TEXT NOT NULL DEFAULT '', output_uri TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, original_policy TEXT NOT NULL DEFAULT 'keep', original_sha TEXT NOT NULL DEFAULT '', output_sha TEXT NOT NULL DEFAULT '', original_action TEXT NOT NULL DEFAULT 'KEPT', original_note TEXT NOT NULL DEFAULT '', source_created INTEGER NOT NULL DEFAULT 0, source_file_key TEXT NOT NULL DEFAULT '')")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){
        require(oldVersion==1 && newVersion==2){"外接日志版本不兼容，未删除历史数据"}
        db.execSQL("ALTER TABLE jobs ADD COLUMN original_policy TEXT NOT NULL DEFAULT 'keep'")
        db.execSQL("ALTER TABLE jobs ADD COLUMN original_sha TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE jobs ADD COLUMN output_sha TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE jobs ADD COLUMN original_action TEXT NOT NULL DEFAULT 'KEPT'")
        db.execSQL("ALTER TABLE jobs ADD COLUMN original_note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE jobs ADD COLUMN source_created INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE jobs ADD COLUMN source_file_key TEXT NOT NULL DEFAULT ''")
    }
    override fun onConfigure(db:SQLiteDatabase){super.onConfigure(db);db.execSQL("PRAGMA synchronous=FULL")}
    private fun job(c:Cursor)=Job(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("run")),ExternalPhoto(c.getString(c.getColumnIndexOrThrow("source_key")),c.getString(c.getColumnIndexOrThrow("uri")),c.getString(c.getColumnIndexOrThrow("name")),c.getLong(c.getColumnIndexOrThrow("size")),c.getLong(c.getColumnIndexOrThrow("modified")),created=c.getLong(c.getColumnIndexOrThrow("source_created"))),c.getString(c.getColumnIndexOrThrow("state")),c.getString(c.getColumnIndexOrThrow("output_name")),c.getString(c.getColumnIndexOrThrow("output_uri")))
    @Synchronized fun enqueue(run:String,p:ExternalPhoto):Boolean {
        check(count(run,"QUEUED")<200){"待处理队列已达200张，已停止监听；不会稍后扫描旧照片补跑"}
        val values=ContentValues().apply{put("id",p.id);put("run",run);put("source_key",p.key);put("source_created",p.created);put("source_file_key",fileKey(p));put("uri",p.uri);put("name",p.name);put("size",p.size);put("modified",p.modified);put("state","QUEUED");put("created",System.currentTimeMillis())}
        return writableDatabase.insertWithOnConflict("jobs",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    private fun fileKey(p:ExternalPhoto):String=try{
        val uri=android.net.Uri.parse(p.uri)
        if(uri.scheme=="file")java.nio.file.Files.readAttributes(java.io.File(checkNotNull(uri.path)).toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()?.toString().orEmpty()else ""
    }catch(e:Exception){propagateFatalCause(e);""}
    @Synchronized fun next(run:String):Job?=readableDatabase.query("jobs",null,"run=? AND state='QUEUED'",arrayOf(run),null,null,"created ASC, id ASC","1").use{if(it.moveToFirst())job(it)else null}
    @Synchronized fun state(id:String,state:String,note:String="") { check(writableDatabase.update("jobs",ContentValues().apply{put("state",state);put("note",note.take(1500))},"id=?",arrayOf(id))==1){"无法保存外接任务状态"} }
    @Synchronized fun prepareExport(id:String,name:String) {check(name.startsWith(ExternalModePolicy.OUTPUT_PREFIX));check(writableDatabase.update("jobs",ContentValues().apply{put("state","EXPORTING");put("output_name",name);put("output_uri","")},"id=?",arrayOf(id))==1)}
    @Synchronized fun created(id:String,uri:String) {check(writableDatabase.update("jobs",ContentValues().apply{put("output_uri",uri)},"id=?",arrayOf(id))==1)}
    @Synchronized fun count(run:String,state:String):Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE run=? AND state=?",arrayOf(run,state)).use{it.moveToFirst();it.getInt(0)}
    @Synchronized fun unfinishedExports():List<Job> =readableDatabase.query("jobs",null,"state!='DONE' AND output_name!=''",null,null,null,null).use{c->buildList{while(c.moveToNext())add(job(c))}}
    /** Cold start explicitly ABANDONS old queued work. Reconciliation may recognize an already published file,
     * but is forbidden to re-render or publish an old input. */
    @Synchronized fun abandonOldJobs(){
        writableDatabase.execSQL("UPDATE jobs SET state='ABANDONED',note='监听已结束或应用已重启；旧队列不补跑' WHERE state IN ('QUEUED','PROCESSING','EXPORTING')")
        writableDatabase.execSQL("UPDATE jobs SET original_action='EXPIRED',original_note='上次监听已结束，原片删除请求作废；不补删' WHERE original_action='WAIT_PERMISSION'")
        writableDatabase.execSQL("UPDATE jobs SET original_action='UNKNOWN',original_note='删除时任务中断，状态待人工核对；绝不重试删除旧片' WHERE original_action='DELETING'")
    }
    @Synchronized fun cancelRun(run:String){
        writableDatabase.update("jobs",ContentValues().apply{put("state","CANCELLED");put("note","用户停止；不补跑")},"run=? AND state IN ('QUEUED','PROCESSING')",arrayOf(run))
        writableDatabase.update("jobs",ContentValues().apply{put("original_action","EXPIRED");put("original_note","已停止监听；原片保留，不补删")},"run=? AND original_action='WAIT_PERMISSION'",arrayOf(run))
    }
    data class OriginalReceipt(val job:Job,val policy:ExternalOriginalPolicy,val sourceHash:String,val outputHash:String,val action:OriginalAction,val message:String,val sourceFileKey:String)
    @Synchronized fun original(id:String):OriginalReceipt?=readableDatabase.query("jobs",null,"id=?",arrayOf(id),null,null,null).use{c->
        if(!c.moveToFirst())null else OriginalReceipt(job(c),ExternalOriginalPolicy.fromId(c.getString(c.getColumnIndexOrThrow("original_policy"))),c.getString(c.getColumnIndexOrThrow("original_sha")),c.getString(c.getColumnIndexOrThrow("output_sha")),OriginalAction.valueOf(c.getString(c.getColumnIndexOrThrow("original_action"))),c.getString(c.getColumnIndexOrThrow("original_note")),c.getString(c.getColumnIndexOrThrow("source_file_key")))
    }
    @Synchronized fun rememberOriginal(id:String,p:ExternalPhoto,hash:String){
        require(hash.matches(Regex("[a-f0-9]{64}")))
        check(writableDatabase.update("jobs",ContentValues().apply{put("size",p.size);put("modified",p.modified);put("original_policy",ExternalOriginalPolicy.DELETE_AFTER_SAVE.id);put("original_sha",hash)},"id=? AND state='PROCESSING' AND source_key=? AND uri=?",arrayOf(id,p.key,p.uri))==1){"原片安全快照登记失败；未删除"}
    }
    @Synchronized fun rememberOutput(id:String,hash:String){
        require(hash.matches(Regex("[a-f0-9]{64}")))
        check(writableDatabase.update("jobs",ContentValues().apply{put("output_sha",hash)},"id=? AND state='EXPORTING'",arrayOf(id))==1){"发布前成片指纹登记失败；未发布、未删除原片"}
    }
    @Synchronized fun originalOutcome(id:String,result:OriginalOutcome){
        check(writableDatabase.update("jobs",ContentValues().apply{put("original_action",result.action.name);put("original_note",result.message.take(1500))},"id=? AND state='DONE'",arrayOf(id))==1){"原片处置状态未保存，请人工核对；不会重试删除旧片"}
    }
    @Synchronized fun nextOriginalPermission(run:String):OriginalReceipt?=readableDatabase.rawQuery("SELECT id FROM jobs WHERE run=? AND state='DONE' AND original_action='WAIT_PERMISSION' ORDER BY created LIMIT 1",arrayOf(run)).use{if(it.moveToFirst())original(it.getString(0))else null}
    @Synchronized fun originalCount(run:String,vararg actions:OriginalAction):Int {
        if(actions.isEmpty())return 0
        val placeholders=actions.joinToString(","){"?"}
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs WHERE run=? AND state='DONE' AND original_action IN ($placeholders)",arrayOf(run,*actions.map{it.name}.toTypedArray())).use{it.moveToFirst();it.getInt(0)}
    }
}
