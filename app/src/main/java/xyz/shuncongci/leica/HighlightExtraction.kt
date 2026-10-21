package xyz.shuncongci.leica

import kotlin.math.*

/** Conservative local light extraction, not semantic emitter recognition.
 * Broad bright surfaces must stay in the base: a fixed .25 cap alone made white
 * subjects/reflections into dense light sources and the later 4x gain exposed it. */
object HighlightExtraction {
    private fun linear(v:Int):Float {val x=v/255f;return if(x<=.04045f)x/12.92f else ((x+.055f)/1.055f).pow(2.4f)}
    private fun smooth(v:Float):Float {val t=v.coerceIn(0f,1f);return t*t*(3-2*t)}
    fun caps(p:Planes,cancel:Cancel):FloatArray {
        val w=p.width;val h=p.height
        val peak=FloatArray(p.size){i->if(i%w==0)cancel.check();linear(maxOf(p.p0[i].toInt() and 255,p.p1[i].toInt() and 255,p.p2[i].toInt() and 255))}
        val radius=max(4,(min(w,h)*.015f).roundToInt());val horizontal=FloatArray(p.size)
        for(y in 0 until h){cancel.check();val row=y*w;var sum=0.0;var left=0;var right=-1
            for(x in 0 until w){val lo=max(0,x-radius);val hi=min(w-1,x+radius)
                while(right<hi){right++;sum+=peak[row+right]};while(left<lo){sum-=peak[row+left];left++}
                horizontal[row+x]=(sum/(right-left+1)).toFloat()
            }
        }
        val out=FloatArray(p.size)
        val candidate=BooleanArray(p.size)
        for(x in 0 until w){cancel.check();var sum=0.0;var top=0;var bottom=-1
            for(y in 0 until h){val lo=max(0,y-radius);val hi=min(h-1,y+radius)
                while(bottom<hi){bottom++;sum+=horizontal[bottom*w+x]};while(top<lo){sum-=horizontal[top*w+x];top++}
                val i=y*w+x;val mean=(sum/(bottom-top+1)).toFloat()
                val strength=smooth((peak[i]-.08f)/.12f)*smooth((peak[i]-mean-.025f)/.10f)
                candidate[i]=strength>0f
                out[i]=if(candidate[i]) .25f-.19f*strength else max(.25f,peak[i])
            }
        }
        // No local peaks: diffuse pixels already kept intact, no component work.
        if(!candidate.any{it}){cancel.check();return out}
        // Include bright interiors in connectivity. Otherwise small texture peaks
        // on one white surface would be mistaken for independent tiny lamps.
        for(i in candidate.indices){if(i%w==0)cancel.check();if(peak[i]>.25f)candidate[i]=true}
        // Connected local-contrast bands also run around large bright objects.
        // Reject their entire component, not just its interior. 8-connectivity
        // keeps diagonals/curved reflection edges from breaking into tiny lamps.
        val maxSpan=max(8,(min(w,h)*.08f).roundToInt())
        val queue=IntArray(p.size)
        for(seed in candidate.indices){
            if(seed%w==0)cancel.check()
            if(!candidate[seed])continue
            var head=0;var tail=1;queue[0]=seed;candidate[seed]=false
            var x0=seed%w;var x1=x0;var y0=seed/w;var y1=y0
            while(head<tail){
                if(head and 4095==0)cancel.check()
                val i=queue[head++];val x=i%w;val y=i/w
                x0=min(x0,x);x1=max(x1,x);y0=min(y0,y);y1=max(y1,y)
                for(yy in max(0,y-1)..min(h-1,y+1))for(xx in max(0,x-1)..min(w-1,x+1)){
                    val j=yy*w+xx
                    if(candidate[j]){candidate[j]=false;queue[tail++]=j}
                }
            }
            val spanX=x1-x0+1;val spanY=y1-y0+1
            if(spanX>maxSpan || spanY>maxSpan){
                for(j in 0 until tail){val i=queue[j];out[i]=max(.25f,peak[i])}
                continue
            }
            // A compact local maximum alone is insufficient: ordinary neutral
            // midtones, glints on an already lit surface and short streaks should
            // remain in conventional blur, not turn into conspicuous pupil discs.
            var topPeak=0f;var chroma=0f
            for(j in 0 until tail){val i=queue[j];if(peak[i]>topPeak){
                topPeak=peak[i]
                val hi=maxOf(p.p0[i].toInt() and 255,p.p1[i].toInt() and 255,p.p2[i].toInt() and 255)
                val lo=minOf(p.p0[i].toInt() and 255,p.p1[i].toInt() and 255,p.p2[i].toInt() and 255)
                chroma=(hi-lo).toFloat()/max(1,hi)
            }}
            val pad=max(2,min(6,min(spanX,spanY)))
            var ring=0.0;var count=0
            for(y in max(0,y0-pad)..min(h-1,y1+pad)){cancel.check();for(x in max(0,x0-pad)..min(w-1,x1+pad)){
                if(x in x0..x1 && y in y0..y1)continue
                ring+=peak[y*w+x];count++
            }}
            val surround=if(count>0)(ring/count).toFloat() else topPeak
            val neutral=smooth((topPeak-.60f)/.25f)
            val colored=smooth((chroma-.35f)/.35f)*smooth((topPeak-.13f)/.10f)
            val isolation=smooth((1f-surround/max(topPeak,1e-8f)-.55f)/.30f)
            val shape=smooth((min(spanX,spanY).toFloat()/max(spanX,spanY)-.34f)/.20f)
            val confidence=max(neutral,colored)*isolation*shape
            for(j in 0 until tail){val i=queue[j]
                out[i]=if(confidence==0f)max(.25f,peak[i]) else max(out[i],peak[i]-(peak[i]-out[i])*confidence)
            }
        }
        cancel.check();return out
    }
}
