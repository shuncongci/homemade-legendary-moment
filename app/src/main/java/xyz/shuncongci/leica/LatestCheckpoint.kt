package xyz.shuncongci.leica

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One latest manifest, immutable blobs, no history. Commit data before atomically replacing
 * the manifest. Process death cannot publish half-written pixels. A damaged manifest is kept
 * for diagnosis, not silently discarded; importing a new photo explicitly replaces it.
 * The per-directory lock also serializes overlapping Activity lifetimes in this process. */
class LatestCheckpoint(val directory:File, private val fault:(String)->Unit = {}) {
    private val manifest=File(directory,"latest.properties")
    private val lock=locks.getOrPut(directory.canonicalPath){Any()}
    private var values=Properties()
    private var loadError:Exception?=null
    private val verified=HashMap<String,Pair<Long,Long>>()
    init { synchronized(lock){directory.mkdirs();reload();if(loadError==null)prune()} }
    private fun reload(){
        try {
            val next=Properties()
            if(manifest.exists()){
                manifest.inputStream().buffered().use{next.load(it)}
                require(next.getProperty("schema")=="2" && next.getProperty("manifest.sha256")==digest(next)){"恢复记录损坏或版本不兼容"}
            }
            values=next;loadError=null
        }catch(e:Exception){values=Properties();loadError=e}
    }
    private fun healthy(){reload();loadError?.let{throw IOException("恢复记录不可读取；原文件保留，可重新导入照片",it)}}
    fun properties():Map<String,String> = synchronized(lock){healthy();values.stringPropertyNames().filterNot(::reserved).associateWith{values.getProperty(it)}}
    fun get(key:String):String?=synchronized(lock){healthy();values.getProperty(key)}
    fun blobName(key:String):String?=synchronized(lock){healthy();values.getProperty("blob.$key")}
    fun file(key:String):File?=synchronized(lock){
        healthy()
        values.getProperty("blob.$key")?.let{name->
            require(ownFile(name) && !name.endsWith(".part")){"恢复记录路径无效"}
            val f=File(directory,name)
            require(f.isFile && f.length()==values.getProperty("bytes.$key")?.toLongOrNull()){"恢复文件缺失或长度不正确：$key"}
            val stamp=f.length() to f.lastModified()
            if(verified[name]!=stamp){require(sha(f)==values.getProperty("hash.$key")){"恢复文件校验失败：$key"};verified[name]=stamp}
            f
        }
    }
    fun keys():Set<String> = synchronized(lock){healthy();blobKeys(values)}
    fun update(meta:Map<String,String> = emptyMap(),remove:Set<String> = emptySet(),blobs:Map<String,(File)->Unit> = emptyMap()) = synchronized(lock){
        healthy();commit(Properties().also{it.putAll(values)},meta,remove,blobs)
    }
    /** A new import is the only path that may replace an unreadable old manifest. */
    fun reset(meta:Map<String,String>,blobs:Map<String,(File)->Unit>)=synchronized(lock){
        reload();commit(Properties(),meta,emptySet(),blobs)
    }
    private fun commit(next:Properties,meta:Map<String,String>,remove:Set<String>,blobs:Map<String,(File)->Unit>){
        next.setProperty("schema","2")
        remove.forEach{next.remove(it);next.remove("blob.$it");next.remove("hash.$it");next.remove("bytes.$it")}
        meta.forEach{(k,v)->require(!reserved(k) && k!="schema");next.setProperty(k,v)}
        val id=UUID.randomUUID().toString();val created=ArrayList<File>();var committed=false
        try {
            blobs.forEach{(key,write)->
                require(key.matches(Regex("[a-zA-Z0-9._-]+")))
                val suffix=when(key){"result"->"result.png";"result-pair"->"result.png.hdr-pair";"result-jpeg"->"result.png.hdr.jpg";else->key}
                val target=File(directory,"$id-$suffix");val temp=File(directory,target.name+".part")
                created+=temp;write(temp);RandomAccessFile(temp,"rw").use{it.fd.sync()}
                val hash=sha(temp);fault("blob-written:$key")
                Files.move(temp.toPath(),target.toPath(),ATOMIC_MOVE);created+=target
                next.setProperty("blob.$key",target.name);next.setProperty("hash.$key",hash);next.setProperty("bytes.$key",target.length().toString())
            }
            next.setProperty("manifest.sha256",digest(next))
            val temp=File(directory,"manifest.part")
            FileOutputStream(temp).use{out->next.store(out,"Latest editor checkpoint");out.fd.sync()}
            fault("before-manifest");Files.move(temp.toPath(),manifest.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
            committed=true;values=next;loadError=null
            fault("after-manifest")
        }finally{
            if(!committed)created.forEach{it.delete()}
            File(directory,"manifest.part").delete()
            if(loadError==null)prune()
        }
    }
    private fun prune(){
        val retained=blobKeys(values).map{values.getProperty("blob.$it")}.toSet()+manifest.name
        directory.listFiles()?.filter{it.isFile && it.name !in retained && (it.name=="manifest.part" || ownFile(it.name))}?.forEach{it.delete();verified.remove(it.name)}
    }
    companion object {
        private val locks=ConcurrentHashMap<String,Any>()
        private fun ownFile(name:String)=name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[a-zA-Z0-9._-]+"))
        private fun reserved(key:String)=listOf("blob.","hash.","bytes.","manifest.").any{key.startsWith(it)}
        private fun blobKeys(p:Properties)=p.stringPropertyNames().filter{it.startsWith("blob.")}.map{it.removePrefix("blob.")}.toSet()
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
        private fun sha(file:File):String {val d=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return hex(d.digest())}
        private fun digest(p:Properties):String {val d=MessageDigest.getInstance("SHA-256");p.stringPropertyNames().filter{it!="manifest.sha256"}.sorted().forEach{k->val v=p.getProperty(k);d.update("${k.length}:$k${v.length}:$v".toByteArray(Charsets.UTF_8))};return hex(d.digest())}
    }
}

/** Lossless byte planes / float bits. No image quantisation in a checkpoint. */
object CheckpointPixels {
    private const val MAGIC=0x4c435031
    fun planes(file:File,p:Planes,cancel:Cancel){
        DataOutputStream(file.outputStream().buffered()).use{out->
            out.writeInt(MAGIC);out.writeInt(p.width);out.writeInt(p.height);out.writeInt(0)
            for(c in 0..2){cancel.check();out.write(p.plane(c))}
        }
    }
    fun planes(file:File,cancel:Cancel):Planes=DataInputStream(file.inputStream().buffered()).use{input->
        require(input.readInt()==MAGIC);val w=input.readInt();val h=input.readInt();require(input.readInt()==0)
        require(w>0 && h>0 && w.toLong()*h<=Int.MAX_VALUE && w.toLong()*h*3+16==file.length()){"恢复图像长度不正确"}
        val p=Planes(w,h);for(c in 0..2){cancel.check();input.readFully(p.plane(c))};p
    }
    fun frame(file:File,p:LegendaryMath.Frame,cancel:Cancel){
        DataOutputStream(file.outputStream().buffered()).use{out->
            out.writeInt(MAGIC);out.writeInt(p.w);out.writeInt(p.h);out.writeInt(p.channels)
            for(i in p.data.indices){if(i and 16383==0)cancel.check();require(p.data[i].isFinite());out.writeInt(p.data[i].toRawBits())}
        }
    }
    fun frame(file:File,cancel:Cancel):LegendaryMath.Frame=DataInputStream(file.inputStream().buffered()).use{input->
        require(input.readInt()==MAGIC);val w=input.readInt();val h=input.readInt();val c=input.readInt()
        val n=w.toLong()*h*c;require(w>0 && h>0 && c in 1..4 && n in 1..Int.MAX_VALUE.toLong() && 16+n*4==file.length()){"恢复浮点图像长度不正确"}
        LegendaryMath.Frame(w,h,c,FloatArray(n.toInt()){i->if(i and 16383==0)cancel.check();Float.fromBits(input.readInt()).also{require(it.isFinite())}})
    }
}

enum class SubjectResolution(val longEdge:Int) {
    HIGH(2048), MEDIUM(1220), LOW(720);
    val label get()="长边 $longEdge"+if(this==HIGH)"（默认）" else ""
    companion object { fun from(value:Int)=entries.firstOrNull{it.longEdge==value}?:HIGH }
}
