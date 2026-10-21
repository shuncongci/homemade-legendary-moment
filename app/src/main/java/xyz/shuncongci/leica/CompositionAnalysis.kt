package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop
import xyz.shuncongci.leica.CompositionGeometry.Point

/** Deterministic image statistics + intention-specific scores. No learned aesthetic/subject model. */
object CompositionAnalysis {
    enum class Intent(val label:String,val reason:String) {
        CENTER("集中视觉重点","让视觉重量更靠近画面中心，减少外围干扰"),
        THIRDS("三分布局","让视觉重点靠近三分交点，保留环境关系"),
        GOLDEN("黄金分割","用较舒展的偏心布局组织视觉重量"),
        SYMMETRY("强调对称","保留左右结构的呼应，让对称轴更居中"),
        SPACE("保留留白","在视觉重点的一侧保留较低纹理的空间"),
        DIAGONAL("对角线布局","利用已有斜线组织画面，不生成新的线条"),
        LEADING("线条汇聚","围绕已有线条交汇关系保留纵深线索"),
        BALANCE("视觉均衡","兼顾两侧视觉重量和边缘整洁")
    }
    data class Suggestion(val crop:Crop,val title:String,val reason:String,val intent:Intent?,val improvement:Double=0.0)
    data class Blob(val box:Crop,val weight:Double)
    data class Line(val angle:Double,val rho:Double,val weight:Double)
    class Features(val width:Int,val height:Int,val gw:Int,val gh:Int,val mass:DoubleArray,val tone:DoubleArray,val texture:DoubleArray,
                   val blobs:List<Blob>,val lines:List<Line>,val reliable:Boolean) {
        val total=mass.sum()
        val sal=Integral(gw,gh,mass)
        val tex=Integral(gw,gh,texture)
        val mx=Integral(gw,gh,DoubleArray(mass.size){mass[it]*(it%gw+.5)/gw})
        val my=Integral(gw,gh,DoubleArray(mass.size){mass[it]*(it/gw+.5)/gh})
        fun centre(c:Crop):Point {val m=sal.crop(c);return if(m<1e-12)Point((c.l+c.r)/2,(c.t+c.b)/2)else Point((mx.crop(c)/m).coerceIn(c.l,c.r),(my.crop(c)/m).coerceIn(c.t,c.b))}
    }
    class Integral(private val w:Int,private val h:Int,a:DoubleArray) {
        private val sums=DoubleArray((w+1)*(h+1))
        init {for(y in 0 until h){var s=0.0;for(x in 0 until w){s+=a[y*w+x];sums[(y+1)*(w+1)+x+1]=sums[y*(w+1)+x+1]+s}}}
        fun sum(l:Int,t:Int,r:Int,b:Int):Double {val x=l.coerceIn(0,w);val y=t.coerceIn(0,h);val xx=r.coerceIn(x,w);val yy=b.coerceIn(y,h);return sums[yy*(w+1)+xx]-sums[y*(w+1)+xx]-sums[yy*(w+1)+x]+sums[y*(w+1)+x]}
        fun crop(c:Crop)=sum((c.l*w).roundToInt(),(c.t*h).roundToInt(),(c.r*w).roundToInt(),(c.b*h).roundToInt())
    }
    fun extract(image:Planes,cancel:Cancel=Cancel()):Features {
        cancel.check();val w=image.width;val h=image.height
        require(max(w,h)<=CompositionGeometry.ANALYSIS_EDGE){"构图输入超过1220分析预算"}
        // All source-analysis pixels contribute. The small grid only pools extracted statistics for candidate search.
        val sums=(0..2).map{c->Integral(w,h,DoubleArray(image.size){(image.plane(c)[it].toInt() and 255)/255.0})}
        val mean=DoubleArray(3){sums[it].sum(0,0,w,h)/image.size}
        val gw=max(1,(96.0*w/max(w,h)).roundToInt());val gh=max(1,(96.0*h/max(w,h)).roundToInt())
        val sal=DoubleArray(gw*gh);val tone=DoubleArray(sal.size);val tex=DoubleArray(sal.size);val count=IntArray(sal.size)
        val lum=DoubleArray(image.size){.2126*(image.p0[it].toInt() and 255)/255+.7152*(image.p1[it].toInt() and 255)/255+.0722*(image.p2[it].toInt() and 255)/255}
        fun light(x:Int,y:Int)=lum[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
        val radii=intArrayOf(max(2,(min(w,h)*.018).roundToInt()),max(3,(min(w,h)*.075).roundToInt()))
        val votes=DoubleArray(36*181);val cs=DoubleArray(36){cos(it*PI/36)};val sn=DoubleArray(36){sin(it*PI/36)}
        var voteTotal=0.0
        for(y in 0 until h){if(y%8==0)cancel.check();for(x in 0 until w){
            val i=y*w+x;val g=(y*gh/h)*gw+x*gw/w
            val gx=(light(x+1,y-1)+2*light(x+1,y)+light(x+1,y+1)-light(x-1,y-1)-2*light(x-1,y)-light(x-1,y+1))/8
            val gy=(light(x-1,y+1)+2*light(x,y+1)+light(x+1,y+1)-light(x-1,y-1)-2*light(x,y-1)-light(x+1,y-1))/8
            val grad=hypot(gx,gy).coerceAtMost(.5)*2
            var contrast=0.0;var global=0.0
            for(c in 0..2){val v=(image.plane(c)[i].toInt() and 255)/255.0;global+=abs(v-mean[c]);for(radius in radii){
                val l=max(0,x-radius);val t=max(0,y-radius);val r=min(w,x+radius+1);val b=min(h,y+radius+1)
                contrast+=abs(v-sums[c].sum(l,t,r,b)/((r-l)*(b-t)))
            }}
            sal[g]+=.62*contrast/6+.28*global/3+.10*grad;tone[g]+=lum[i];tex[g]+=grad;count[g]++
            if(x%3==0 && y%3==0 && grad>.12){
                val angle=((atan2(gy,gx)+PI)%PI*36/PI).roundToInt()%36
                val px=(x+.5-w/2.0)/max(w,h);val py=(y+.5-h/2.0)/max(w,h)
                for(d in -1..1){val a=(angle+d+36)%36;val rho=px*cs[a]+py*sn[a];val rr=((rho+1)*90).roundToInt().coerceIn(0,180);val v=grad*(if(d==0)1.0 else .5);votes[a*181+rr]+=v;voteTotal+=v}
            }
        }}
        val average=sal.sum()/image.size
        for(i in sal.indices){val n=max(1,count[i]);sal[i]=max(0.0,sal[i]/n-average*.65).pow(2);tone[i]/=n;tex[i]/=n}
        // Ignore weak/noisy maps: a textured wall should not force a crop.
        val total=sal.sum();val peak=sal.maxOrNull()?:0.0
        val reliable=average>.008 && peak>total/max(1,sal.size)*1.8 && total>1e-7
        val visited=BooleanArray(sal.size);val blobs=mutableListOf<Blob>();val queue=IntArray(sal.size)
        for(i in sal.indices){if(visited[i] || sal[i]<peak*.22 || peak==0.0)continue;cancel.check();var start=0;var end=1;queue[0]=i;visited[i]=true;var l=gw;var t=gh;var r=0;var b=0;var weight=0.0
            while(start<end){val k=queue[start++];val x=k%gw;val y=k/gw;l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1);weight+=sal[k]
                for(n in intArrayOf(if(x>0)k-1 else -1,if(x+1<gw)k+1 else -1,if(y>0)k-gw else -1,if(y+1<gh)k+gw else -1)){if(n>=0 && !visited[n] && sal[n]>=peak*.22){visited[n]=true;queue[end++]=n}}
            }
            if(weight>total*.07)blobs.add(Blob(Crop(l.toDouble()/gw,t.toDouble()/gh,r.toDouble()/gw,b.toDouble()/gh),weight/total))
        }
        val lines=mutableListOf<Line>()
        for(k in votes.indices.sortedByDescending{votes[it]}){
            if(lines.size>=6 || votes[k]<max(2.5,voteTotal*.018))break
            val a=k/181*PI/36;val rho=(k%181)/90.0-1
            if(lines.none{min(abs(it.angle-a),PI-abs(it.angle-a))<PI/12 && min(abs(it.rho-rho),if(abs(it.angle-a)>PI/2)abs(it.rho+rho)else 9.0)<.055})lines.add(Line(a,rho,votes[k]/max(1.0,voteTotal)))
        }
        cancel.check();return Features(w,h,gw,gh,sal,tone,tex,blobs,lines,reliable)
    }
    private fun overlap(a:Crop,b:Crop)=max(0.0,min(a.r,b.r)-max(a.l,b.l))*max(0.0,min(a.b,b.b)-max(a.t,b.t))
    fun safe(f:Features,c:Crop,sourceW:Int,sourceH:Int,point:Point?):Boolean {
        if(c.area<.48 || (point!=null && !c.contains(point,.06)))return false
        val box=CompositionGeometry.pixels(c,sourceW,sourceH)
        if(box.w.toLong()*box.h<min(250000L,(sourceW.toLong()*sourceH*.48).toLong()))return false
        // Small originals remain usable; proposals never pretend that upscaling creates detail.
        if(min(box.w,box.h)<min(96,min(sourceW,sourceH)))return false
        if(f.total>1e-9 && f.sal.crop(c)/f.total<.80)return false
        for(blob in f.blobs){val fraction=overlap(c,blob.box)/blob.box.area;if(blob.weight>.10 && fraction<.92)return false}
        return true
    }
    private fun symmetry(f:Features,c:Crop):Double {
        var diff=0.0;var scale=0.0
        for(y in 0..15)for(x in 0..7){val yy=((c.t+(y+.5)/16*c.h)*f.gh).toInt().coerceIn(0,f.gh-1)
            val l=((c.l+(x+.5)/16*c.w)*f.gw).toInt().coerceIn(0,f.gw-1);val r=((c.r-(x+.5)/16*c.w)*f.gw).toInt().coerceIn(0,f.gw-1)
            val a=yy*f.gw+l;val b=yy*f.gw+r;diff+=abs(f.tone[a]-f.tone[b])+.6*abs(f.texture[a]-f.texture[b]);scale+=1
        };return (1-diff/max(1.0,scale)/.22).coerceIn(0.0,1.0)
    }
    private fun convergence(f:Features):Point? {
        val points=mutableListOf<Point>();val long=max(f.width,f.height).toDouble()
        for(i in f.lines.indices)for(j in 0 until i){val a=f.lines[i];val b=f.lines[j];val det=sin(b.angle-a.angle);if(abs(det)<.4)continue
            val x=(a.rho*sin(b.angle)-b.rho*sin(a.angle))/det*long/f.width+.5
            val y=(b.rho*cos(a.angle)-a.rho*cos(b.angle))/det*long/f.height+.5
            if(x in .05.. .95 && y in .05.. .95)points.add(Point(x,y))
        }
        // Two arbitrary edges do not establish a vanishing point: need a cluster of intersections.
        return points.maxByOrNull{p->points.count{hypot(p.x-it.x,p.y-it.y)<.08}}?.takeIf{p->points.count{hypot(p.x-it.x,p.y-it.y)<.08}>=3}
    }
    private fun score(f:Features,c:Crop,intent:Intent,point:Point?,vanish:Point?):Double {
        val centre=point?:f.centre(c);val x=(centre.x-c.l)/c.w;val y=(centre.y-c.t)/c.h
        fun target(a:Double,b:Double)=exp(-9*((x-a).pow(2)+(y-b).pow(2)))
        val mass=f.sal.crop(c);val retained=if(f.total>1e-9)(mass/f.total).coerceIn(0.0,1.0)else 1.0
        val inner=Crop(c.l+c.w*.04,c.t+c.h*.04,c.r-c.w*.04,c.b-c.h*.04)
        val rim=if(mass>1e-9)((mass-f.sal.crop(inner))/mass).coerceIn(0.0,1.0)else 0.0
        val left=f.sal.crop(Crop(c.l,c.t,(c.l+c.r)/2,c.b));val balance=if(mass>1e-9)1-abs(left/mass-.5)*2 else 1.0
        val layout=when(intent){
            Intent.CENTER->target(.5,.5)
            Intent.THIRDS->listOf(1.0/3,2.0/3).maxOf{a->listOf(1.0/3,2.0/3).maxOf{b->target(a,b)}}
            Intent.GOLDEN->listOf(.382,.618).maxOf{a->listOf(.382,.618).maxOf{b->target(a,b)}}
            Intent.SYMMETRY->symmetry(f,c)
            Intent.SPACE->{val empty=if(x<.5)Crop(c.l+c.w*.55,c.t,c.r,c.b)else Crop(c.l,c.t,c.l+c.w*.45,c.b)
                val quiet=(1-f.tex.crop(empty)/(empty.area*f.gw*f.gh)/.16).coerceIn(0.0,1.0)
                max(target(.28,.5),target(.72,.5))*quiet}
            Intent.BALANCE->balance
            Intent.DIAGONAL->f.lines.filter{abs(cos(it.angle))>.3 && abs(sin(it.angle))>.3}.maxOfOrNull{line->
                val long=max(f.width,f.height).toDouble()
                fun distance(px:Double,py:Double)=abs((px-.5)*f.width/long*cos(line.angle)+(py-.5)*f.height/long*sin(line.angle)-line.rho)
                exp(-12*min(distance(c.l,c.t)+distance(c.r,c.b),distance(c.l,c.b)+distance(c.r,c.t)))}?:0.0
            Intent.LEADING->if(vanish!=null && c.contains(vanish,.1))exp(-6*(((vanish.x-c.l)/c.w-.5).pow(2)+((vanish.y-c.t)/c.h-.4).pow(2)))else 0.0
        }
        return .20*retained+.16*sqrt(c.area)+.18*(1-rim)+.08*balance+.38*layout-.12*(1-retained)
    }
    /** Offer only exportable alternatives across useful ratios; no manual ratio/point setup required. */
    fun automatic(f:Features,w:Int,h:Int,output:BokehOutput,cancel:Cancel=Cancel()):List<Suggestion> {
        val native=w.toDouble()/h
        val ratios=if(w>=h)listOf(native,1.0,4.0/3,1.5,16.0/9)else listOf(native,1.0,3.0/4,2.0/3,9.0/16)
        val pool=ratios.distinct().flatMap{suggest(f,w,h,it,cancel=cancel)}
        val selected=mutableListOf(pool.first())
        for(item in pool.filter{it.intent!=null}.sortedByDescending{it.improvement}){
            cancel.check();if(selected.size==4)break
            if(CompositionOutput.eligible(w,h,item.crop,output) && selected.none{CompositionGeometry.iou(it.crop,item.crop)>.88})selected.add(item)
        }
        return selected
    }
    fun suggest(f:Features,sourceW:Int,sourceH:Int,ratio:Double,point:Point?=null,cancel:Cancel=Cancel()):List<Suggestion> {
        require(sourceW>0 && sourceH>0 && ratio.isFinite() && ratio>0);cancel.check()
        val full=CompositionGeometry.FULL
        val keep=Suggestion(full,"保留原构图","不裁剪；没有充分改善依据时，保留完整画面",null)
        if(!f.reliable && point==null)return listOf(keep)
        val centre=point?:f.centre(full);val vanish=convergence(f)
        val candidates=linkedSetOf<Crop>()
        for(scale in doubleArrayOf(1.0,.96,.89,.81,.73)){
            val base=CompositionGeometry.fit(sourceW,sourceH,ratio,scale)
            for(x in 0..4)for(y in 0..4)candidates.add(CompositionGeometry.placed(base.w,base.h,(1-base.w)*x/4,(1-base.h)*y/4))
            for(x in doubleArrayOf(.28,1.0/3,.382,.5,.618,2.0/3,.72))for(y in doubleArrayOf(1.0/3,.382,.5,.618,2.0/3))candidates.add(CompositionGeometry.placed(base.w,base.h,centre.x-x*base.w,centre.y-y*base.h))
        }
        val applicable=Intent.entries.filter{when(it){Intent.SYMMETRY->symmetry(f,full)>.6 && f.tex.crop(full)/(f.gw*f.gh)>.01;Intent.LEADING->vanish!=null;Intent.DIAGONAL->f.lines.any{l->abs(cos(l.angle))>.3 && abs(sin(l.angle))>.3};else->true}}
        val baseScores=applicable.associateWith{score(f,full,it,point,vanish)}
        val ranked=mutableListOf<Suggestion>()
        for(c in candidates){cancel.check();if(CompositionGeometry.iou(c,full)>.985 || !safe(f,c,sourceW,sourceH,point))continue
            for(intent in applicable){val improvement=score(f,c,intent,point,vanish)-baseScores.getValue(intent)
                if(improvement>.028)ranked.add(Suggestion(c,intent.label,intent.reason,intent,improvement))
            }
        }
        val selected=mutableListOf<Suggestion>()
        for(candidate in ranked.sortedByDescending{it.improvement}){
            if(selected.size==3)break
            if(selected.none{it.intent==candidate.intent || CompositionGeometry.iou(it.crop,candidate.crop)>.88})selected.add(candidate)
        }
        return listOf(keep)+selected
    }
}
