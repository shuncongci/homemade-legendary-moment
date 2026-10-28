package xyz.shuncongci.leica

import java.io.*
import java.util.UUID
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** One global optical operator. Receiver strips are only a storage schedule, NOT four photo renders.
 * Each receiver sees the same global pupils, reflected source pixels and descending depth order.
 * Color/own/coverage stay in bounded RAM through ALL depths, then each output row is written once.
 */
internal object StreamingLightScatter {
    /** Compressed exact PSF rows, shared by SDR and signed HDR passes in this render only.
     * At most one radius lives in memory. Kernel generation is not repeated per strip/sign/pass. */
    class Pupils(val w:Int,val h:Int,val aperture:Float,private val directory:File):Closeable {
        val nodes=OpticalPupil.nodes(w,h)
        private val prefix="pupil-${UUID.randomUUID()}-"
        private val files=HashMap<Int,File>()
        private var lastRadius=-1
        private var last:List<Array<HighlightScatter.PsfRow>> = emptyList()
        var generatedRadii=0;private set
        fun rows(radius:Int,cancel:Cancel):List<Array<HighlightScatter.PsfRow>> {
            cancel.check();if(radius==lastRadius)return last
            val file=files[radius]
            val plans=if(file!=null)DataInputStream(BufferedInputStream(file.inputStream(),65536)).use{input->
                check(input.readInt()==0x50534631 && input.readInt()==radius && input.readInt()==nodes.size)
                List(nodes.size){Array(input.readInt().also{require(it in 1..2*(radius+1)+1)}){
                    val dy=input.readInt();val a=input.readInt();val b=input.readInt();val run=input.readFloat();val lo=input.readInt();val hi=input.readInt()
                    val n=input.readInt();require(n in 0..2*(radius+1)+1);val tx=IntArray(n);val tw=FloatArray(n)
                    for(i in 0 until n){tx[i]=input.readInt();tw[i]=input.readFloat()}
                    HighlightScatter.PsfRow(dy,a,b,run,tx,tw,lo,hi)
                }}.also{check(input.read()==-1)}
            }else{
                val made=nodes.map{node->cancel.check();HighlightScatter.psfRows(OpticalPupil.kernel(radius,aperture,OpticalPupil.shape(node.x,node.y,w,h),cancel))}
                val target=File(directory,"$prefix$radius.bin");check(target.createNewFile());files[radius]=target
                DataOutputStream(BufferedOutputStream(target.outputStream(),65536)).use{out->
                    out.writeInt(0x50534631);out.writeInt(radius);out.writeInt(nodes.size)
                    for(rows in made){out.writeInt(rows.size);for(row in rows){cancel.check();out.writeInt(row.dy);out.writeInt(row.a);out.writeInt(row.b);out.writeFloat(row.run);out.writeInt(row.lo);out.writeInt(row.hi);out.writeInt(row.tx.size);for(i in row.tx.indices){out.writeInt(row.tx[i]);out.writeFloat(row.tw[i])}}}
                };generatedRadii++;made
            }
            lastRadius=radius;last=plans;return plans
        }
        override fun close(){last=emptyList();lastRadius=-1;files.values.forEach{it.delete()};files.clear()}
    }
    data class Stats(var receiverWrites:Int=0,var depthStripVisits:Int=0,var emptyDepthStrips:Int=0,var prefixPixels:Long=0)
    fun run(high:Frame,coc:Frame,aperture:Float,cancel:Cancel,color:BokehFloatFile,foreground:BokehFloatFile?,pupils:Pupils,
            onProgress:(Int,Int)->Unit={_,_->},stats:Stats=Stats()) {
        cancel.check();FullFrameOptics.checkAperture(aperture)
        val w=high.w;val h=high.h;val n=high.size
        require(high.channels==3 && coc.channels==1 && coc.w==w && coc.h==h)
        require(pupils.w==w && pupils.h==h && pupils.aperture==aperture)
        require(color.w==w && color.h==h && color.channels==3)
        foreground?.let{require(it.w==w && it.h==h && it.channels==3)}
        val bound=max(54f,FullFrameOptics.maxRadius(w,h)+.01f)*HighlightScatter.RADIUS_SCALE
        require(high.data.all{it.isFinite() && it>=0f} && coc.data.all{it.isFinite() && abs(it)<=bound})
        if(high.data.all{it==0f}){onProgress(h,h);cancel.check();return}
        val minL=floor(coc.data.minOrNull()!!).toInt();val maxL=ceil(coc.data.maxOrNull()!!).toInt();val levels=maxL-minL+1
        val left=IntArray(levels*h){w};val right=IntArray(levels*h){-1}
        val litLo=IntArray(h){w};val litHi=IntArray(h){-1}
        for(y in 0 until h){cancel.check();for(x in 0 until w){val i=y*w+x;val v=coc.data[i]
            for(level in floor(v).toInt()..ceil(v).toInt())if(HighlightScatter.layerWeight(v,level)>0f){val j=(level-minL)*h+y;left[j]=min(left[j],x);right[j]=x}
            if(high.data[i]>0f || high.data[n+i]>0f || high.data[2*n+i]>0f){litLo[y]=min(litLo[y],x);litHi[y]=x}
        }}
        fun halfOf(r:Int)=if(r==0)0 else r+1
        val maxHalf=halfOf(max(abs(minL),abs(maxL)))
        val outLo=IntArray(h);val outHi=IntArray(h)
        for(y in 0 until h){cancel.check();var lo=w;var hi=-1
            for(sy in max(0,y-maxHalf)..min(h-1,y+maxHalf)){lo=min(lo,litLo[sy]);hi=max(hi,litHi[sy])}
            outLo[y]=if(hi<0)w else max(0,lo-maxHalf);outHi[y]=if(hi<0)-1 else min(w-1,hi+maxHalf)
        }
        val band=min(h,256);val workN=w*band;val capacity=min(h,band+2*maxHalf);val stride=w+2*maxHalf+1
        val rgb=FloatArray(workN*3);val own=FloatArray(workN*3);val cover=FloatArray(workN);val fg=foreground?.let{FloatArray(workN*3)}
        val maskP=DoubleArray(stride*capacity);val rP=DoubleArray(maskP.size);val gP=DoubleArray(maskP.size);val bP=DoubleArray(maskP.size)
        val pmin=IntArray(h);val pmax=IntArray(h);val acc=ThreadLocal.withInitial{DoubleArray(4*w)}
        val weights=FloatArray(stride*capacity);val active=BooleanArray(workN);val activeLo=IntArray(band);val activeHi=IntArray(band)
        for(y0 in 0 until h step band){
            cancel.check();val y1=min(h,y0+band);rgb.fill(0f);own.fill(0f);cover.fill(0f);fg?.fill(0f)
            if((y0 until y1).any{outLo[it]<=outHi[it]})for(level in maxL downTo minL){
                cancel.check();val half=halfOf(abs(level));val li=(level-minL)*h
                val source0=max(0,y0-half);val source1=min(h,y1+half)
                if(!(source0 until source1).any{right[li+it]>=0}){stats.emptyDepthStrips++;continue}
                var anyReceiver=false
                for(row in 0 until y1-y0){val y=y0+row;var lo=w;var hi=-1
                    for(x in outLo[y]..outHi[y]){val needed=cover[row*w+x]<1f || HighlightScatter.layerWeight(coc.data[y*w+x],level)>0f;active[row*w+x]=needed;if(needed){lo=min(lo,x);hi=x}}
                    activeLo[row]=lo;activeHi[row]=hi;if(lo<=hi)anyReceiver=true
                }
                if(!anyReceiver){stats.emptyDepthStrips++;continue}
                stats.depthStripVisits++
                val plans=pupils.rows(abs(level),cancel);val padded=w+2*half
                val colOf=IntArray(padded){HighlightScatter.reflect(it-half,w)}
                parallelFor(source1-source0){rowIndex->
                    val sy=source0+rowIndex;val slot=sy%capacity
                    if(sy and 15==0)cancel.check()
                    if(right[li+sy]<0){pmin[sy]=-1;return@parallelFor}
                    val base=slot*stride;val src=sy*w;var lo=left[li+sy]+half;var hi=right[li+sy]+half
                    for(p in 0 until half)if(HighlightScatter.layerWeight(coc.data[src+colOf[p]],level)>0f){lo=min(lo,p);hi=max(hi,p)}
                    for(p in w+half until padded)if(HighlightScatter.layerWeight(coc.data[src+colOf[p]],level)>0f){lo=min(lo,p);hi=max(hi,p)}
                    java.util.Arrays.fill(maskP,base,base+lo+1,0.0);java.util.Arrays.fill(rP,base,base+lo+1,0.0)
                    java.util.Arrays.fill(gP,base,base+lo+1,0.0);java.util.Arrays.fill(bP,base,base+lo+1,0.0)
                    java.util.Arrays.fill(weights,base,base+lo,0f);java.util.Arrays.fill(weights,base+hi+1,base+padded,0f)
                    var m=0.0;var r=0.0;var g=0.0;var b=0.0
                    for(p in lo..hi){val i=src+colOf[p];val fw=HighlightScatter.layerWeight(coc.data[i],level);weights[base+p]=fw;val weight=fw.toDouble()
                        if(weight>0){m+=weight;r+=high.data[i]*weight;g+=high.data[n+i]*weight;b+=high.data[2*n+i]*weight}
                        maskP[base+p+1]=m;rP[base+p+1]=r;gP[base+p+1]=g;bP[base+p+1]=b
                    }
                    java.util.Arrays.fill(maskP,base+hi+2,base+padded+1,m);java.util.Arrays.fill(rP,base+hi+2,base+padded+1,r)
                    java.util.Arrays.fill(gP,base+hi+2,base+padded+1,g);java.util.Arrays.fill(bP,base+hi+2,base+padded+1,b)
                    pmin[sy]=lo;pmax[sy]=hi
                }
                parallelFor(y1-y0){rowIndex->
                    val y=y0+rowIndex;if(y and 7==0)cancel.check();if(activeLo[rowIndex]>activeHi[rowIndex])return@parallelFor
                    val a=checkNotNull(acc.get());var touched=false
                    // Preserve reference node/PSF-row/run/tail order exactly, including level zero.
                    val count=if(level==0)1 else pupils.nodes.size
                    for(pi in 0 until count){val node=if(level==0)null else pupils.nodes[pi];val wy=node?.wy?.get(y)?:1f;if(wy<=0f)continue
                        for(row in plans[pi]){
                            val sy=HighlightScatter.reflect(y+row.dy,h);if(right[li+sy]<0 || pmin[sy]<0)continue
                            val x0=max(activeLo[rowIndex],max(node?.x0?:0,max(0,pmin[sy]-row.hi)));val x1=min(activeHi[rowIndex],min(node?.x1?:(w-1),min(w-1,pmax[sy]-row.lo)));if(x0>x1)continue
                            if(!touched){java.util.Arrays.fill(a,0.0);touched=true}
                            val base=(sy%capacity)*stride;val src=sy*w
                            if(row.a>=0)for(x in x0..x1){if(!active[rowIndex*w+x])continue;val p0=base+x+row.a;val p1=base+x+row.b+1;val mass=maskP[p1]-maskP[p0];if(mass<=0)continue
                                val kw=row.run*wy*(node?.wx?.get(x)?:1f)
                                a[x]+=kw*mass;a[w+x]+=kw*(rP[p1]-rP[p0]);a[2*w+x]+=kw*(gP[p1]-gP[p0]);a[3*w+x]+=kw*(bP[p1]-bP[p0])
                            }
                            for(t in row.tx.indices){val kx=row.tx[t];val kw=row.tw[t]
                                for(x in x0..x1){if(!active[rowIndex*w+x])continue;val i=src+colOf[x+kx];val weight=(kw*wy*(node?.wx?.get(x)?:1f)*weights[base+x+kx]).toDouble();if(weight<=0)continue
                                    a[x]+=weight;a[w+x]+=high.data[i]*weight;a[2*w+x]+=high.data[n+i]*weight;a[3*w+x]+=high.data[2*n+i]*weight
                                }
                            }
                        }
                    }
                    if(!touched)return@parallelFor
                    for(x in outLo[y]..outHi[y]){val alpha=a[x].toFloat().coerceIn(0f,1f);if(alpha<=1e-8f)continue
                        val i=y*w+x;val di=rowIndex*w+x;val local=HighlightScatter.layerWeight(coc.data[i],level)
                        val take=min(alpha*HighlightScatter.visibility(coc.data[i],level),max(0f,1f-cover[di]))
                        for(c in 0..2){val at=c*workN+di;val radiance=max(0.0,a[(c+1)*w+x]).toFloat()
                            own[at]+=local*(radiance/alpha);rgb[at]+=radiance*(take/alpha)
                            if(level>0 && fg!=null)fg[at]+=radiance*(take/alpha)
                        };cover[di]=(cover[di]+take).coerceIn(0f,1f)
                    }
                }
            }
            parallelFor(y1-y0){row->cancel.check();for(i in row*w until (row+1)*w)for(c in 0..2){val at=c*workN+i;rgb[at]+=(1f-cover[i])*own[at]}}
            for(c in 0..2){color.putRows(c,y0,y1-y0,rgb,cancel,c*workN);if(fg!=null)checkNotNull(foreground).putRows(c,y0,y1-y0,fg,cancel,c*workN)}
            stats.receiverWrites+=y1-y0;onProgress(y1,h);cancel.check()
        }
        color.sync();foreground?.sync()
    }
}
