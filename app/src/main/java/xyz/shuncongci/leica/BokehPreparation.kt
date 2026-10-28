package xyz.shuncongci.leica

import java.io.File
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Global, small-channel preparation. Never constructs an image-sized RGB FloatArray.
 * Caps/component classification and subject pull-push are global, not four independent photos.
 */
internal object BokehPreparation {
    data class Prepared(val coc:File,val caps:File,val alpha:File?,val plate:File?,val plateCoc:File,val maxRadius:Float,val field:LayeredBokeh.Field,val hasSdrLight:Boolean)
    private fun save(file:File,f:Frame,cancel:Cancel){BokehFloatFile.create(file,f.w,f.h,f.channels).use{it.put(f,it.whole,cancel);it.sync()}}
    fun prepare(colored:Planes,depth:Frame,focus:Float,aperture:Float,subject:Frame?,dir:File,cancel:Cancel,naturalLight:Boolean=false,checkpoint:RenderStepCache?=null,onRestored:()->Unit={}):Prepared {
        checkpoint?.load("prepare",cancel)?.let{saved->
            require(saved.meta["width"]==colored.width.toString() && saved.meta["height"]==colored.height.toString())
            val f=saved.files;require((subject!=null)==(f["alpha"]!=null))
            for((name,file) in f)BokehFloatFile.open(file).use{require(it.w==colored.width && it.h==colored.height && it.channels==if(name=="plate")3 else 1)}
            val coc=f.getValue("coc");val plate=f["plate"]
            val state=Prepared(coc,f.getValue("caps"),f["alpha"],plate,if(plate==null)coc else f.getValue("platecoc"),saved.meta.getValue("radius").toFloat(),LayeredBokeh.Field(colored.width,colored.height,saved.meta.getValue("low").toFloat(),saved.meta.getValue("high").toFloat()),saved.meta.getValue("lit").toBooleanStrict())
            cancel.check();onRestored();return state
        }
        val cocFile=File(dir,"coc.f32");val alphaFile=subject?.let{File(dir,"alpha.f32")}
        fun geometry(){
            val alpha=subject?.let{SharpSubjectEdges.renderAlpha(colored,it,cancel)}
            val coc=FullFrameOptics.coc(depth,focus,aperture,colored.width,colored.height,cancel)
            if(alpha!=null){
                save(checkNotNull(alphaFile),alpha,cancel)
                for(i in coc.data.indices){if(i%coc.w==0)cancel.check();coc.data[i]*=1-alpha.data[i].coerceIn(0f,1f)}
            }
            save(cocFile,coc,cancel)
        };geometry() // full scalar/alpha buffers are out of scope before caps/component scratch.
        val capsFile=File(dir,"caps.f32")
        fun extraction():Boolean {
            val scratch=File(dir,"component-queue.bin")
            val caps=if(naturalLight)NaturalBokehLights.caps(colored,cancel,scratch)else BoundedHighlightExtraction.caps(colored,cancel,scratch)
            save(capsFile,Frame(colored.width,colored.height,1,caps),cancel)
            val base=FloatArray(3)
            for(i in caps.indices){if(i%colored.width==0)cancel.check();LegendaryMath.basePixel(colored,i,caps[i],base)
                for(c in 0..2)if(max(0f,HdrMath.linearByte(colored.plane(c)[i].toInt()and 255)-LegendaryMath.linearQuantized(base[c]))>0f)return true
            };return false
        }
        val lit=extraction()
        val plateFile=File(dir,"plate.f32");val pcFile=File(dir,"plate-coc.f32")
        val filled=alphaFile?.let{globalPlate(colored,it,capsFile,cocFile,plateFile,pcFile,cancel)}?:false
        val selected=if(filled)pcFile else cocFile
        val range=BokehFloatFile.open(selected).use{it.range(cancel)}
        val highRange=BokehFloatFile.open(cocFile).use{it.range(cancel)}
        val state=Prepared(cocFile,capsFile,alphaFile,if(filled)plateFile else null,selected,max(abs(highRange.first),abs(highRange.second)),LayeredBokeh.Field(colored.width,colored.height,range.first,range.second),lit)
        val files=linkedMapOf("coc" to cocFile,"caps" to capsFile)
        alphaFile?.let{files["alpha"]=it};if(filled){files["plate"]=plateFile;files["platecoc"]=pcFile}
        checkpoint?.saveFiles("prepare",files,cancel,mapOf("width" to colored.width.toString(),"height" to colored.height.toString(),"radius" to state.maxRadius.toString(),"low" to range.first.toString(),"high" to range.second.toString(),"lit" to lit.toString()))
        return state
    }
    private fun up(f:FloatArray,pw:Int,ph:Int,x:Int,y:Int):Float {
        val sx=((x+.5f)*.5f-.5f).coerceIn(0f,(pw-1).toFloat());val sy=((y+.5f)*.5f-.5f).coerceIn(0f,(ph-1).toFloat())
        val x0=sx.toInt();val y0=sy.toInt();val x1=min(pw-1,x0+1);val y1=min(ph-1,y0+1);val fx=sx-x0;val fy=sy-y0
        return (f[y0*pw+x0]*(1-fx)+f[y0*pw+x1]*fx)*(1-fy)+(f[y1*pw+x0]*(1-fx)+f[y1*pw+x1]*fx)*fy
    }
    /** Exact BackgroundPlate pull/push equations, one channel pyramid at a time.
     * Level0 values are read by row; only scalar weights and coarser levels are retained.
     */
    private fun globalPlate(colored:Planes,alpha:File,caps:File,coc:File,plate:File,pc:File,cancel:Cancel):Boolean {
        val w=colored.width;val h=colored.height
        val wt=BokehFloatFile.open(alpha).use{it.read(it.whole,cancel).data}
        var hole=false;var known=false
        for(i in wt.indices){if(i%w==0)cancel.check();wt[i]=BackgroundPlate.evidence(wt[i].coerceIn(0f,1f));if(wt[i]<1f)hole=true;if(wt[i]>0f)known=true}
        if(!hole || !known || wt.size==1)return false
        val ws=arrayListOf(w);val hs=arrayListOf(h)
        while(ws.last()>1 || hs.last()>1){ws.add((ws.last()+1)/2);hs.add((hs.last()+1)/2)}
        val weights=arrayOfNulls<FloatArray>(ws.size);weights[0]=wt
        for(l in 1 until ws.size){val pw=ws[l-1];val ph=hs[l-1];val cw=ws[l];val prev=weights[l-1]!!;val cur=FloatArray(cw*hs[l])
            for(y in 0 until ph){cancel.check();for(x in 0 until pw)cur[(y/2)*cw+x/2]+=min(1f,prev[y*pw+x])};weights[l]=cur
        }
        BokehFloatFile.open(caps).use{cap->BokehFloatFile.open(coc).use{co->
        BokehFloatFile.create(plate,w,h,3).use{rgbOut->BokehFloatFile.create(pc,w,h,1).use{cocOut->
            val source=FloatArray(w);val capRow=FloatArray(w);val pixel=FloatArray(3)
            fun sourceRow(c:Int,y:Int){
                if(c==3){co.row(0,y,0,w,source,cancel);return}
                cap.row(0,y,0,w,capRow,cancel)
                for(x in 0 until w)source[x]=LegendaryMath.baseChannel(colored,y*w+x,capRow[x],c)
            }
            for(c in 0..3){
                val values=arrayOfNulls<FloatArray>(ws.size)
                for(l in 1 until ws.size){val pw=ws[l-1];val ph=hs[l-1];val cw=ws[l];val prevW=weights[l-1]!!;val cur=FloatArray(cw*hs[l]);val prevV=values[l-1]
                    for(y in 0 until ph){cancel.check();if(l==1)sourceRow(c,y)
                        for(x in 0 until pw){val j=y*pw+x;val k=min(1f,prevW[j]);if(k>0f)cur[(y/2)*cw+x/2]+=k*(prevV?.get(j)?:source[x])}
                    }
                    val sw=weights[l]!!;for(i in cur.indices)if(sw[i]>0f)cur[i]/=sw[i];values[l]=cur
                }
                for(l in ws.size-2 downTo 1){val cw=ws[l];val ch=hs[l];val pw=ws[l+1];val ph=hs[l+1];val v=values[l]!!;val parent=values[l+1]!!;val k=weights[l]!!
                    for(y in 0 until ch){cancel.check();for(x in 0 until cw){val j=y*cw+x;val t=min(1f,k[j]);if(t<1f)v[j]=t*v[j]+(1-t)*up(parent,pw,ph,x,y)}}
                }
                val first=values[1]!!;val pw=ws[1];val ph=hs[1]
                for(y in 0 until h){cancel.check();sourceRow(c,y)
                    for(x in 0 until w){val t=wt[y*w+x];if(t<1f)source[x]=t*source[x]+(1-t)*up(first,pw,ph,x,y)}
                    (if(c==3)cocOut else rgbOut).putRow(if(c==3)0 else c,y,0,w,source,cancel)
                }
            };rgbOut.sync();cocOut.sync()
        }}}}
        return true
    }
}
