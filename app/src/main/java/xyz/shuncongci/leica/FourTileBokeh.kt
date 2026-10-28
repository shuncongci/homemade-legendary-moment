package xyz.shuncongci.leica

import java.io.File
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Four spatial regions ONLY for the 64-layer base. SDR/HDR highlights are global,
 * each full-photo color channel processed sequentially; RGB handoff stays in lossless row files. */
object FourTileBokeh {
    const val ID="layered64-four-quadrants-base-global-light-v3-streamed"
    internal fun scatterHalo(r:Float)=ceil(r*HighlightScatter.RADIUS_SCALE).toInt()+1
    internal fun softenHalo(r:Float)=2*ceil(r*HighlightScatter.RADIUS_SCALE*SoftBokehEdges.SOFTNESS+.5f).toInt()
    const val NATURAL_ID="layered64-four-quadrants-base-natural-physical-signed-v1"
    /** Retained 66 numerical reference for historical tests; the App uses renderNatural. */
    fun render(colored:Planes,depth:Frame,focus:Float,aperture:Float,cancel:Cancel,cacheDir:File,
        subjectMask:Frame?=null,sharp:HdrPixels?=null,hdrTarget:File?=null,
        progress:(String)->Unit={},onPart:(Int,Int)->Unit={_,_->},split:Boolean=true,
        onPreview:((BokehPreview.Patch)->Unit)?=null,onReport:(BokehRunReport)->Unit={},
        clock:()->Long={System.nanoTime()/1_000_000L},onLayer:(BokehLayerProgress)->Unit={}):Planes =
        renderCore(colored,depth,focus,aperture,cancel,cacheDir,subjectMask,sharp,hdrTarget,progress,onPart,split,onPreview,onReport,clock,onLayer,false)
    /** Production entry: physical-radius light, no dark chroma qualification or invented gain,
     * and a single shared SDR/signed-HDR operator after all base regions complete. */
    fun renderNatural(colored:Planes,depth:Frame,focus:Float,aperture:Float,cancel:Cancel,cacheDir:File,
        subjectMask:Frame?=null,sharp:HdrPixels?=null,hdrTarget:File?=null,
        progress:(String)->Unit={},onPart:(Int,Int)->Unit={_,_->},split:Boolean=true,
        onPreview:((BokehPreview.Patch)->Unit)?=null,onReport:(BokehRunReport)->Unit={},
        clock:()->Long={System.nanoTime()/1_000_000L},onLayer:(BokehLayerProgress)->Unit={},checkpoint:RenderStepCache?=null):Planes =
        renderCore(colored,depth,focus,aperture,cancel,cacheDir,subjectMask,sharp,hdrTarget,progress,onPart,split,onPreview,onReport,clock,onLayer,true,checkpoint)
    private fun renderCore(colored:Planes,depth:Frame,focus:Float,aperture:Float,cancel:Cancel,cacheDir:File,
        subjectMask:Frame?,sharp:HdrPixels?,hdrTarget:File?,
        progress:(String)->Unit,onPart:(Int,Int)->Unit,split:Boolean,
        onPreview:((BokehPreview.Patch)->Unit)?,onReport:(BokehRunReport)->Unit,
        clock:()->Long,onLayer:(BokehLayerProgress)->Unit,naturalLight:Boolean,checkpoint:RenderStepCache?=null):Planes {
        cancel.check();FullFrameOptics.checkAperture(aperture)
        require((sharp==null)==(hdrTarget==null));sharp?.let{require(it.w==colored.width && it.h==colored.height)}
        cacheDir.mkdirs();val work=File(cacheDir,"bokeh4-${java.util.UUID.randomUUID()}");check(work.mkdir())
        var ownedTarget=false;val partTimes=arrayListOf<BokehPartTime>();var partTiming:StageTiming?=null
        fun stage(text:String){cancel.check();partTiming?.start(BokehProgress.key(text).substringAfter(" · "));progress(text);cancel.check()}
        fun ready(p:Planes,core:BokehBox,index:Int,total:Int,start:Long){
            cancel.check();onPreview?.let{send->partTiming?.start("过程预览");send(BokehPreview.patch(p,core,index,total,cancel))}
            partTiming?.finish();partTimes.add(BokehPartTime(index,total,(clock()-start).coerceAtLeast(0),partTiming?.summary().orEmpty()));partTiming=null
            onPart(index,total);cancel.check()
        }
        fun finished(p:Planes):Planes{cancel.check();onReport(BokehRunReport(partTimes.toList()));cancel.check();return p}
        try{
            HdrFloatFile.requireSpace(File(work,"space"),((if(sharp!=null)192L else 112L)+(if(checkpoint!=null)160L else 0L))*colored.size+64*HdrMemory.MIB)
            stage("准备 · 光学参数")
            val prep=BokehPreparation.prepare(colored,depth,focus,aperture,subjectMask,work,cancel,naturalLight,checkpoint){stage("准备 · 复用已保存光学参数")}
            val w=colored.width;val h=colored.height;val parts=BokehRenderPolicy.parts(w,h,split);val whole=BokehBox(0,0,w,h)
            if(prep.maxRadius<1e-6f){
                val kept=if(sharp!=null){HdrFloatFile.write(checkNotNull(hdrTarget),sharp,cancel);ownedTarget=true;colored}
                    else prep.alpha?.let{f->BokehFloatFile.open(f).use{SubjectProtectionMath.composite(colored,colored,it.read(it.whole,cancel),cancel)}}?:colored
                for((i,core) in parts.withIndex()){val start=clock();partTiming=StageTiming(clock);stage(BokehProgress.label(i+1,parts.size,"保持焦内"));ready(kept,core,i+1,parts.size,start)}
                return finished(kept)
            }
            val result=Planes(w,h);val base=File(work,"global-base.f32")
            BokehFloatFile.create(base,w,h,3).use{out->
                for((part,core) in parts.withIndex()){
                    val start=clock();partTiming=StageTiming(clock);stage(BokehProgress.label(part+1,parts.size,"虚化"))
                    val restored=checkpoint?.restoreRegion("base${part+1}",out,core,cancel)==true
                    if(restored)stage(BokehProgress.label(part+1,parts.size,"复用基础区域（本轮读回）"))else{
                        renderBase(colored,prep,core,out,cancel){done,total->
                            if(done%4==0 || done==total){
                                cancel.check();onLayer(BokehLayerProgress(part+1,parts.size,done,total));cancel.check()
                                stage(BokehProgress.label(part+1,parts.size,"虚化 · $done/${total}层"))
                            }
                        }
                        checkpoint?.saveRegion("base${part+1}",out,core,cancel)
                    }
                    basePreview(out,core,result,cancel);ready(result,core,part+1,parts.size,start)
                };out.sync()
            }
            // No spatial four-part loop around any highlight operation.
            StreamingLightScatter.Pupils(w,h,aperture,work).use{pupils->
            val together=if(naturalLight)NaturalBokehLights.render(colored,prep,sharp,work,aperture,cancel,pupils,::stage,checkpoint)else null
            val sdr=if(naturalLight)together?.sdr else if(prep.hasSdrLight)GlobalBokehLights.render(colored,prep,null,false,"sdr",work,aperture,cancel,pupils,::stage)else null
            stage("合成 · SDR画面")
            val savedSdr=checkpoint?.planes("sdr-composite",cancel)
            if(savedSdr!=null){require(savedSdr.width==w && savedSdr.height==h);for(c in 0..2)savedSdr.plane(c).copyInto(result.plane(c));stage("合成 · 恢复已完成SDR画面")}
            else{combineSdr(base,sdr?.color,whole,result,cancel);checkpoint?.savePlanes("sdr-composite",result,cancel)}
            if(sharp!=null){
                val positive=if(naturalLight)together?.hdrSigned else GlobalBokehLights.render(colored,prep,sharp,false,"hdr-positive",work,aperture,cancel,pupils,::stage)
                val negative=if(naturalLight)null else GlobalBokehLights.render(colored,prep,sharp,true,"hdr-negative",work,aperture,cancel,pupils,::stage)
                stage("合成 · HDR画面")
                val savedHdr=checkpoint?.load("hdr-composite",cancel)?.files?.getValue("pixels")
                if(savedHdr!=null){
                    BokehFloatFile.open(savedHdr).use{require(it.w==w && it.h==h && it.channels==3)}
                    RenderStepCache.copyFile(savedHdr,checkNotNull(hdrTarget),cancel);ownedTarget=true;stage("合成 · 恢复已完成HDR画面")
                }else{
                    BokehFloatFile.create(checkNotNull(hdrTarget),w,h,3).use{out->
                        ownedTarget=true
                        combineHdr(colored,prep,whole,base,positive?.let{it.color to it.foreground},negative?.let{it.color to it.foreground},sharp,out,cancel);out.sync()
                    }
                    checkpoint?.saveFiles("hdr-composite",mapOf("pixels" to checkNotNull(hdrTarget)),cancel)
                }
            }
            stage("合成 · 主体边缘")
            if(prep.alpha==null)return finished(result)
            val protected=BokehFloatFile.open(prep.alpha).use{SubjectProtectionMath.composite(colored,result,it.read(it.whole,cancel),cancel)}
            if(sdr?.foreground!=null)addIncomingRows(protected,prep,sdr,cancel)
            return finished(protected)
            }
        }catch(t:Throwable){if(ownedTarget)hdrTarget?.delete();throw t}
        finally{work.listFiles()?.forEach{check(it.isFile);it.delete()};work.delete()}
    }
    private fun baseImage(colored:Planes,caps:File,box:BokehBox,cancel:Cancel):Frame {
        val out=Frame(box.w,box.h,3,FloatArray(box.w*box.h*3));val capRow=FloatArray(box.w);val pixel=FloatArray(3)
        BokehFloatFile.open(caps).use{cap->for(y in 0 until box.h){cancel.check();cap.row(0,box.top+y,box.left,box.w,capRow,cancel)
            for(x in 0 until box.w){val i=(box.top+y)*colored.width+box.left+x;LegendaryMath.basePixel(colored,i,capRow[x],pixel)
                for(c in 0..2)out.data[c*out.size+y*box.w+x]=pixel[c]
            }
        }};return out
    }
    private fun renderBase(colored:Planes,p:BokehPreparation.Prepared,core:BokehBox,target:BokehFloatFile,cancel:Cancel,onLayer:(Int,Int)->Unit){
        val r=max(abs(p.field.low),abs(p.field.high));val box=core.expand(ceil(r+.5f).toInt(),colored.width,colored.height)
        val rgb=p.plate?.let{BokehFloatFile.open(it).use{f->f.read(box,cancel)}}?:baseImage(colored,p.caps,box,cancel)
        val coc=BokehFloatFile.open(p.plateCoc).use{it.read(box,cancel)}
        val rendered=LayeredBokeh.renderRegion(rgb,coc,cancel,p.field,onLayer)
        target.putCore(rendered,box,core,core,cancel)
    }
    private fun basePreview(base:BokehFloatFile,core:BokehBox,out:Planes,cancel:Cancel){
        val row=FloatArray(core.w)
        for(c in 0..2)for(y in 0 until core.h){base.row(c,core.top+y,core.left,core.w,row,cancel)
            for(x in 0 until core.w)out.plane(c)[(core.top+y)*out.width+core.left+x]=Math.rint((row[x].coerceIn(0f,1f)*255).toDouble()).toInt().toByte()
        }
    }
    private fun addIncomingRows(out:Planes,p:BokehPreparation.Prepared,light:GlobalBokehLights.Light,cancel:Cancel){
        val w=out.width;val row=Planes(w,1);val a=Frame(w,1,1,FloatArray(w));val co=Frame(w,1,1,FloatArray(w))
        val fg=Frame(w,1,3,FloatArray(3*w));val src=Frame(w,1,3,FloatArray(3*w))
        BokehFloatFile.open(checkNotNull(p.alpha)).use{ar->BokehFloatFile.open(p.coc).use{cr->
        BokehFloatFile.open(checkNotNull(light.foreground)).use{fr->BokehFloatFile.open(light.source).use{sr->
            for(y in 0 until out.height){cancel.check();ar.row(0,y,0,w,a.data,cancel);cr.row(0,y,0,w,co.data,cancel)
                for(c in 0..2){out.plane(c).copyInto(row.plane(c),0,y*w,(y+1)*w);fr.row(c,y,0,w,fg.data,cancel,c*w);sr.row(c,y,0,w,src.data,cancel,c*w)}
                BokehRadiance.addIncoming(row,a,fg,cancel,src,co)
                for(c in 0..2)row.plane(c).copyInto(out.plane(c),y*w)
            }
        }}}}
    }
    private fun combineSdr(base:File,light:File?,core:BokehBox,out:Planes,cancel:Cancel){
        BokehFloatFile.open(base).use{b->val l=light?.let{BokehFloatFile.open(it)}
            try{val br=FloatArray(core.w);val lr=FloatArray(core.w)
                for(c in 0..2)for(y in 0 until core.h){b.row(c,y,0,core.w,br,cancel);if(l!=null)l.row(c,y,0,core.w,lr,cancel)
                    for(x in 0 until core.w){val v=if(l==null)br[x]else LegendaryMath.srgb(SoftBokehEdges.shoulder(LegendaryMath.linearQuantized(br[x]),lr[x]))
                        out.plane(c)[(core.top+y)*out.width+core.left+x]=Math.rint((v.coerceIn(0f,1f)*255).toDouble()).toInt().toByte()
                    }
                }
            }finally{l?.close()}
        }
    }
    private fun combineHdr(colored:Planes,p:BokehPreparation.Prepared,core:BokehBox,base:File,positive:Pair<File,File?>?,negative:Pair<File,File?>?,sharp:HdrPixels,out:BokehFloatFile,cancel:Cancel){
        val readers=arrayListOf<BokehFloatFile>();fun open(file:File?):BokehFloatFile?=file?.let{BokehFloatFile.open(it).also{f->readers.add(f)}}
        try{
            val b=checkNotNull(open(base));val pos=open(positive?.first);val neg=open(negative?.first);val pf=open(positive?.second);val nf=open(negative?.second)
            val alpha=open(p.alpha);val co=checkNotNull(open(p.coc));val cap=checkNotNull(open(p.caps))
            val baseRow=FloatArray(core.w);val posRow=FloatArray(core.w);val negRow=FloatArray(core.w);val pfRow=FloatArray(core.w);val nfRow=FloatArray(core.w)
            val alphaRow=FloatArray(core.w);val cocRow=FloatArray(core.w);val capRow=FloatArray(core.w);val hdr=FloatArray(sharp.w);val result=FloatArray(core.w);val originalBase=FloatArray(3)
            val originalRows=Array(3){FloatArray(core.w)}
            for(y in 0 until core.h){cancel.check();alpha?.row(0,core.top+y,core.left,core.w,alphaRow,cancel);co.row(0,core.top+y,core.left,core.w,cocRow,cancel);cap.row(0,core.top+y,core.left,core.w,capRow,cancel)
                for(x in 0 until core.w){LegendaryMath.basePixel(colored,(core.top+y)*colored.width+core.left+x,capRow[x],originalBase);for(c in 0..2)originalRows[c][x]=LegendaryMath.linearQuantized(originalBase[c])}
                for(c in 0..2){b.row(c,y,0,core.w,baseRow,cancel);pos?.row(c,y,0,core.w,posRow,cancel);neg?.row(c,y,0,core.w,negRow,cancel);pf?.row(c,y,0,core.w,pfRow,cancel);nf?.row(c,y,0,core.w,nfRow,cancel);sharp.read(c,core.top+y,1,hdr,cancel)
                    for(x in 0 until core.w){val a=alphaRow[x].coerceIn(0f,1f);val sv=hdr[core.left+x];HdrPixels.valid(sv)
                        val blurred=max(0f,LegendaryMath.linearQuantized(baseRow[x])+posRow[x]-negRow[x])
                        val delta=sv-originalRows[c][x];val local=if(cocRow[x]>0f)max(0f,delta)-max(0f,-delta)else 0f
                        val incoming=max(0f,pfRow[x]-nfRow[x]-local)
                        result[x]=sv*a+blurred*(1-a)+incoming*a;HdrPixels.valid(result[x])
                    };out.putRow(c,core.top+y,core.left,core.w,result,cancel)
                }
            }
        }finally{readers.forEach{it.close()}}
    }
}
