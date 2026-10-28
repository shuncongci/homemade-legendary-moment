package xyz.shuncongci.leica

import java.io.File
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** ONE full-photo RGB highlight scatter. Float32 receiver-band accumulators and bounded
 * Double row prefixes spill losslessly to owned files; no three channel replays or four spatial scatters. */
internal object GlobalBokehLights {
    data class Light(val source:File,val color:File,val foreground:File?)
    private fun plane(file:File,c:Int,cancel:Cancel):Frame=BokehFloatFile.open(file).use{f->
        val data=FloatArray(f.w*f.h)
        for(y in 0 until f.h)f.row(c,y,0,f.w,data,cancel,y*f.w)
        Frame(f.w,f.h,1,data)
    }
    private fun put(f:BokehFloatFile,c:Int,p:Frame,cancel:Cancel){
        require(p.w==f.w && p.h==f.h && p.channels==1)
        for(y in 0 until f.h)f.putRow(c,y,0,f.w,p.data,cancel,y*f.w);f.sync()
    }
    private fun makeSource(colored:Planes,p:BokehPreparation.Prepared,sharp:HdrPixels?,negative:Boolean,file:File,cancel:Cancel):Boolean {
        val w=colored.width;val h=colored.height;val rows=Array(3){FloatArray(w)};val capRow=FloatArray(w);val pixel=FloatArray(3)
        val peak=if(sharp==null)FloatArray(w*h)else null;var any=false
        BokehFloatFile.create(file,w,h,3).use{out->BokehFloatFile.open(p.caps).use{cap->
            for(y in 0 until h){cancel.check();cap.row(0,y,0,w,capRow,cancel)
                if(sharp!=null)for(c in 0..2)sharp.read(c,y,1,rows[c],cancel)
                for(x in 0 until w){val i=y*w+x;LegendaryMath.basePixel(colored,i,capRow[x],pixel)
                    for(c in 0..2){val value=if(sharp==null)HdrMath.linearByte(colored.plane(c)[i].toInt()and 255)else rows[c][x].also{HdrPixels.valid(it)}
                        val delta=value-LegendaryMath.linearQuantized(pixel[c]);rows[c][x]=if(negative)max(0f,-delta)else max(0f,delta)
                        if(rows[c][x]>0f)any=true
                    }
                    peak?.set(i,max(rows[0][x],max(rows[1][x],rows[2][x])))
                }
                for(c in 0..2)out.putRow(c,y,0,w,rows[c],cancel)
            };out.sync()
        }
        if(peak!=null && any){
            check(BokehRadiance.DEFAULT_GAIN==1f)
            val coc=plane(p.coc,0,cancel)
            val gains=ClippedHighlights.gainsFromPeak(colored,Frame(w,h,1,peak),coc,cancel)
            if(gains!=null){
                val row=FloatArray(w)
                for(c in 0..2)for(y in 0 until h){out.row(c,y,0,w,row,cancel)
                    for(x in 0 until w){val k=gains[y*w+x];if(k!=1f)row[x]*=k}
                    out.putRow(c,y,0,w,row,cancel)
                };out.sync()
            }
        }
        }
        return any
    }
    private fun scatterRgb(source:File,cocFile:File,color:BokehFloatFile,foreground:BokehFloatFile?,aperture:Float,cancel:Cancel,pupils:StreamingLightScatter.Pupils,label:String,progress:(String)->Unit) {
        val high=BokehFloatFile.open(source).use{it.read(it.whole,cancel)}
        val coc=plane(cocFile,0,cancel)
        var low=Float.POSITIVE_INFINITY;var highRadius=Float.NEGATIVE_INFINITY
        for(i in coc.data.indices){if(i and 65535==0)cancel.check();coc.data[i]*=HighlightScatter.RADIUS_SCALE;low=min(low,coc.data[i]);highRadius=max(highRadius,coc.data[i])}
        progress("$label · 光学半径档位 ${ceil(highRadius).toInt()-floor(low).toInt()+1}级")
        StreamingLightScatter.run(high,coc,aperture,cancel,color,foreground,pupils,{n,total->progress("$label · 全局散射 $n/${total}行")})
    }
    private fun softenRgb(file:BokehFloatFile,cocFile:File,cancel:Cancel){
        val light=BokehFloatFile.open(file.file).use{it.read(it.whole,cancel)};val coc=plane(cocFile,0,cancel)
        SoftBokehEdges.softenInPlace(light,coc,cancel)
        for(c in 0..2)file.putRows(c,0,file.h,light.data,cancel,c*light.size)
        file.sync()
    }
    fun render(colored:Planes,p:BokehPreparation.Prepared,sharp:HdrPixels?,negative:Boolean,tag:String,work:File,aperture:Float,cancel:Cancel,pupils:StreamingLightScatter.Pupils,progress:(String)->Unit):Light? {
        val label=if(sharp==null)"SDR高光"else if(negative)"HDR减光"else "HDR增光"
        progress("$label · 提取光能");cancel.check()
        val source=File(work,"$tag-source.f32")
        if(!makeSource(colored,p,sharp,negative,source,cancel)){check(source.delete());return null}
        val color=File(work,"$tag-color.f32");val foreground=if(p.alpha!=null)File(work,"$tag-foreground.f32")else null
        BokehFloatFile.create(color,colored.width,colored.height,3).use{out->
            val fg=foreground?.let{BokehFloatFile.create(it,colored.width,colored.height,3)}
            try{
                progress("$label · 光能散射")
                scatterRgb(source,p.coc,out,fg,aperture,cancel,pupils,label,progress)
                val soft=if(sharp==null)"SDR柔边"else "HDR柔边"
                progress("$soft · 色彩");softenRgb(out,p.coc,cancel)
                fg?.let{progress("$soft · 前景");softenRgb(it,p.coc,cancel)}
            }finally{fg?.close()}
        }
        cancel.check();if(sharp!=null)check(source.delete())
        return Light(source,color,foreground)
    }
}
