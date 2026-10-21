package xyz.shuncongci.leica

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Test APK only. Invokes image processing directly; does not inject UI input or grant permissions. */
class HairDiagnosticInstrumentation:Instrumentation(){
    private var args=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);args=arguments?:Bundle();start()}
    override fun onStart(){
        val info=Bundle()
        try {
            val ctx=targetContext
            val source=File(ctx.filesDir,"hair-diagnostic-source.jpg")
            require(source.isFile){"Missing explicit test source"}
            val name=args.getString("case")?:"hair-diagnostic-2048"
            require(name.matches(Regex("[a-zA-Z0-9_-]+")))
            val dir=File(ctx.filesDir,name);require(!dir.exists()){ "Test output already exists; do not overwrite" };check(dir.mkdirs())
            fun capture(name:String,f:LegendaryMath.Frame){
                val meta=JSONObject().put("w",f.w).put("h",f.h).put("channels",f.channels).put("dtype","float32-le-CHW")
                File(dir,"$name.json").writeText(meta.toString())
                File(dir,"$name.f32").outputStream().buffered().use { out ->
                    val b=ByteBuffer.allocate(262144).order(ByteOrder.LITTLE_ENDIAN)
                    for(v in f.data){if(b.remaining()<4){out.write(b.array(),0,b.position());b.clear()};b.putFloat(v)}
                    if(b.position()>0)out.write(b.array(),0,b.position())
                }
            }
            val x=(args.getString("x")?:"0.52").toFloat();val y=(args.getString("y")?:"0.27").toFloat()
            val aperture=(args.getString("aperture")?:"2.8").toFloat()
            val meta=JSONObject().put("sourceSha256",java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)})
                .put("x",x).put("y",y).put("aperture",aperture).put("note","Controlled test coordinates; prior UI only reported rounded percentages")
            File(dir,"request.json").writeText(meta.toString(2))
            LeicaEngine(ctx,::capture).use { engine ->
                val result=engine.process(Uri.fromFile(source),LeicaStyle.LEGENDARY,Cancel(),true,LegendaryMath.Point(x,y),aperture,BokehOutput.SIZE_2048,true){message ->
                    File(dir,"progress.txt").writeText(message)
                    sendStatus(0,Bundle().apply{putString("stream",message+"\n")})
                }
                result.file.copyTo(File(dir,"result.png"),overwrite=false)
                meta.put("totalMsIncludingDiagnosticIO",result.totalMs).put("stages",result.stageSummary).put("hardware",result.hardwareSummary).put("rendered",result.rendered).put("width",result.width).put("height",result.height)
                File(dir,"result.json").writeText(meta.toString(2))
                result.original.recycle();result.result.recycle()
            }
            info.putString("stream","Diagnostic complete: ${dir.absolutePath}\n")
            finish(Activity.RESULT_OK,info)
        } catch(t:Throwable){info.putString("stream",android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,info)}
    }
}
