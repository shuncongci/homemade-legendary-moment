package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** One global physical-CoC transport for SDR light and signed HDR residuals.
 * Geometry/coverage is shared, radiance channels are independent. No radius enlargement,
 * clipping gain, absolute-value HDR conversion or low-precision/low-resolution fallback.
 * Only CoC is full-frame. Source RGB, Double prefixes and receiver accumulators are row-bounded.
 * This implements the existing pupil/visibility equations, not a learned light detector.
 */
internal object RadianceTransport {
    data class Stats(var receiverRows:Int=0,var depthBands:Int=0,var sourceRows:Long=0,var maxSourceRows:Int=0)
    fun run(inputs:List<BokehFloatFile>,coc:Frame,aperture:Float,cancel:Cancel,
        outputs:List<BokehFloatFile>,foreground:List<BokehFloatFile>?,signed:BooleanArray,
        pupils:StreamingLightScatter.Pupils,bandSize:Int=128,onProgress:(Int,Int)->Unit={_,_->},stats:Stats=Stats()) {
        cancel.check();FullFrameOptics.checkAperture(aperture)
        val w=coc.w;val h=coc.h;val groups=inputs.size;val channels=groups*3
        require(groups in 1..2 && outputs.size==groups && signed.size==groups)
        require(foreground==null || foreground.size==groups)
        require(coc.channels==1 && bandSize in 1..256)
        require(pupils.w==w && pupils.h==h && pupils.aperture==aperture)
        for(fs in listOfNotNull(inputs,outputs,foreground))for(f in fs)require(f.w==w && f.h==h && f.channels==3)
        require(outputs.map{it.file.canonicalFile}.distinct().size==groups)
        require(inputs.none{a->outputs.any{b->a.file.canonicalFile==b.file.canonicalFile}})
        val bound=max(54f,FullFrameOptics.maxRadius(w,h)+.01f)
        var low=Float.POSITIVE_INFINITY;var high=Float.NEGATIVE_INFINITY
        for(i in coc.data.indices){if(i and 65535==0)cancel.check();val v=coc.data[i]
            require(v.isFinite() && abs(v)<=bound);low=min(low,v);high=max(high,v)
        }
        val minL=floor(low).toInt();val maxL=ceil(high).toInt();val levels=maxL-minL+1
        val maxR=max(abs(minL),abs(maxL));val maxHalf=if(maxR==0)0 else maxR+1
        val band=min(h,bandSize);val workN=w*band;val capacity=min(h,band+2*maxHalf);val stride=w+2*maxHalf+1
        val left=IntArray(levels*h){w};val right=IntArray(levels*h){-1}
        for(y in 0 until h){cancel.check();for(x in 0 until w){val v=coc.data[y*w+x]
            for(level in floor(v).toInt()..ceil(v).toInt())if(HighlightScatter.layerWeight(v,level)>0f){val i=(level-minL)*h+y;left[i]=min(left[i],x);right[i]=x}
        }}
        val source=Array(channels){FloatArray(capacity*w)}
        val rgb=Array(channels){FloatArray(workN)};val own=Array(channels){FloatArray(workN)}
        val fg=foreground?.let{Array(channels){FloatArray(workN)}}
        val cover=FloatArray(workN);val weights=FloatArray(stride*capacity)
        val prefix=Array(channels+1){DoubleArray(stride*capacity)}
        // Exact nonzero mask runs. Interleaved inclusive endpoints need at most
        // padded+1 ints per resident source row, even for alternating one-pixel runs.
        val spans=Array(capacity){IntArray(stride)};val spanCounts=IntArray(capacity);val nonzeroGroups=IntArray(capacity)
        val pmin=IntArray(h);val pmax=IntArray(h);val activeLo=IntArray(band);val activeHi=IntArray(band);val active=BooleanArray(workN)
        val accum=ThreadLocal.withInitial{Array(channels+1){DoubleArray(w)}}
        val nodes=pupils.nodes;stats.maxSourceRows=capacity
        val reflected=Array(maxR+1){r->val half=if(r==0)0 else r+1;IntArray(w+2*half){HighlightScatter.reflect(it-half,w)}}
        val litLo=IntArray(h){w};val litHi=IntArray(h){-1};val outLo=IntArray(band);val outHi=IntArray(band)
        for(y0 in 0 until h step band){
            cancel.check();val y1=min(h,y0+band);val source0=max(0,y0-maxHalf);val source1=min(h,y1+maxHalf)
            for(c in 0 until channels)for(sy in source0 until source1){
                val at=sy%capacity*w;inputs[c/3].row(c%3,sy,0,w,source[c],cancel,at);stats.sourceRows++
                for(x in 0 until w){val v=source[c][at+x];require(v.isFinite() && (signed[c/3] || v>=0f)){"光能数值无效"};if(v!=0f){litLo[sy]=min(litLo[sy],x);litHi[sy]=max(litHi[sy],x)}}
            }
            // A receiver outside every source's maximum physical support is exactly zero.
            // This only bounds work; all coverage/depth operations INSIDE support remain unchanged.
            for(y in y0 until y1){var lo=w;var hi=-1
                for(sy in max(0,y-maxHalf)..min(h-1,y+maxHalf)){lo=min(lo,litLo[sy]);hi=max(hi,litHi[sy])}
                outLo[y-y0]=if(hi<0)w else max(0,lo-maxHalf);outHi[y-y0]=if(hi<0)-1 else min(w-1,hi+maxHalf)
            }
            rgb.forEach{it.fill(0f)};own.forEach{it.fill(0f)};fg?.forEach{it.fill(0f)};cover.fill(0f)
            for(level in maxL downTo minL){
                cancel.check();val half=if(level==0)0 else abs(level)+1;val li=(level-minL)*h
                val from=max(0,y0-half);val to=min(h,y1+half);val padded=w+2*half
                if(!(from until to).any{right[li+it]>=0})continue
                var any=false
                for(row in 0 until y1-y0){var a=w;var b=-1;val y=y0+row
                    // A level's mask (including dark occluders) has no support outside this
                    // conservative reflected rectangle. Only exact-zero alpha work is skipped;
                    // never prune by signed HDR energy, visibility or an approximate threshold.
                    var lo=w;var hi=-1
                    for(sy in max(0,y-half)..min(h-1,y+half)){lo=min(lo,left[li+sy]);hi=max(hi,right[li+sy])}
                    val first=if(hi<0)w else max(outLo[row],max(0,lo-half))
                    val last=if(hi<0)-1 else min(outHi[row],min(w-1,hi+half))
                    for(x in first..last){val use=cover[row*w+x]<1f || HighlightScatter.layerWeight(coc.data[y*w+x],level)>0f
                        active[row*w+x]=use;if(use){a=min(a,x);b=x}
                    };activeLo[row]=a;activeHi[row]=b;if(a<=b)any=true
                }
                if(!any)continue;stats.depthBands++
                val plans=pupils.rows(abs(level),cancel);val colOf=reflected[abs(level)]
                parallelFor(to-from){rowIndex->
                    val sy=from+rowIndex;cancel.check()
                    if(right[li+sy]<0){pmin[sy]=-1;return@parallelFor}
                    val slot=sy%capacity;val base=slot*stride;val row=sy*w;val src=slot*w
                    var a=left[li+sy]+half;var b=right[li+sy]+half
                    for(x in 0 until half)if(HighlightScatter.layerWeight(coc.data[row+colOf[x]],level)>0f){a=min(a,x);b=max(b,x)}
                    for(x in w+half until padded)if(HighlightScatter.layerWeight(coc.data[row+colOf[x]],level)>0f){a=min(a,x);b=max(b,x)}
                    // Prefix endpoints outside [a,b+1] are constant. Clamp queries below
                    // instead of filling channels+1 whole row tails for every depth/band.
                    val p0=prefix[0];val p1=prefix[1];val p2=prefix[2];val p3=prefix[3]
                    val dual=channels==6
                    val p4=if(dual)prefix[4]else p1;val p5=if(dual)prefix[5]else p2;val p6=if(dual)prefix[6]else p3
                    val v0=source[0];val v1=source[1];val v2=source[2]
                    val v3=if(dual)source[3]else v0;val v4=if(dual)source[4]else v1;val v5=if(dual)source[5]else v2
                    prefix.forEach{it[base+a]=0.0}
                    var s0=0.0;var s1=0.0;var s2=0.0;var s3=0.0;var s4=0.0;var s5=0.0;var s6=0.0
                    val runs=spans[slot];var runStart=-1;var runCount=0;var present=0
                    for(x in a..b){val fw=HighlightScatter.layerWeight(coc.data[row+colOf[x]],level);weights[base+x]=fw;val f=fw.toDouble()
                        if(f>0){if(runStart<0)runStart=x
                            val i=src+colOf[x];s0+=f;s1+=v0[i]*f;s2+=v1[i]*f;s3+=v2[i]*f
                            if(v0[i]!=0f || v1[i]!=0f || v2[i]!=0f)present=present or 1
                            if(dual){s4+=v3[i]*f;s5+=v4[i]*f;s6+=v5[i]*f;if(v3[i]!=0f || v4[i]!=0f || v5[i]!=0f)present=present or 2}
                        }else if(runStart>=0){runs[runCount++]=runStart;runs[runCount++]=x-1;runStart=-1}
                        val i=base+x+1;p0[i]=s0;p1[i]=s1;p2[i]=s2;p3[i]=s3
                        if(dual){p4[i]=s4;p5[i]=s5;p6[i]=s6}
                    }
                    if(runStart>=0){runs[runCount++]=runStart;runs[runCount++]=b};spanCounts[slot]=runCount;nonzeroGroups[slot]=present
                    pmin[sy]=a;pmax[sy]=b
                }
                parallelFor(y1-y0){rowIndex->
                    val y=y0+rowIndex;if(y and 7==0)cancel.check();if(activeLo[rowIndex]>activeHi[rowIndex])return@parallelFor
                    val sums=checkNotNull(accum.get());sums.forEach{it.fill(0.0)}
                    val s0=sums[0];val s1=sums[1];val s2=sums[2];val s3=sums[3]
                    val p0=prefix[0];val p1=prefix[1];val p2=prefix[2];val p3=prefix[3]
                    val s4=if(channels==6)sums[4]else s1;val s5=if(channels==6)sums[5]else s2;val s6=if(channels==6)sums[6]else s3
                    val p4=if(channels==6)prefix[4]else p1;val p5=if(channels==6)prefix[5]else p2;val p6=if(channels==6)prefix[6]else p3
                    val v0=source[0];val v1=source[1];val v2=source[2]
                    val v3=if(channels==6)source[3]else v0;val v4=if(channels==6)source[4]else v1;val v5=if(channels==6)source[5]else v2
                    val count=if(level==0)1 else nodes.size
                    for(pi in 0 until count){val node=if(level==0)null else nodes[pi];val wy=node?.wy?.get(y)?:1f;if(wy<=0f)continue
                        for(row in plans[pi]){
                            val sy=HighlightScatter.reflect(y+row.dy,h);if(right[li+sy]<0 || pmin[sy]<0)continue
                            val x0=max(activeLo[rowIndex],max(node?.x0?:0,max(0,pmin[sy]-row.hi)))
                            val x1=min(activeHi[rowIndex],min(node?.x1?:(w-1),min(w-1,pmax[sy]-row.lo)));if(x0>x1)continue
                            val slot=sy%capacity;val base=slot*stride;val src=slot*w
                            // Exact source presence, not a signed sum: cancellation must never
                            // make a nonzero HDR group disappear. Occlusion mass is always kept.
                            val has0=nonzeroGroups[slot] and 1!=0;val has1=nonzeroGroups[slot] and 2!=0
                            if(row.a>=0)for(x in max(x0,pmin[sy]-row.b)..min(x1,pmax[sy]-row.a)){
                                if(!active[rowIndex*w+x])continue;val a=base+max(pmin[sy],x+row.a);val b=base+min(pmax[sy]+1,x+row.b+1);val mass=p0[b]-p0[a];if(mass<=0)continue
                                val kw=row.run*wy*(node?.wx?.get(x)?:1f);s0[x]+=kw*mass
                                if(has0){s1[x]+=kw*(p1[b]-p1[a]);s2[x]+=kw*(p2[b]-p2[a]);s3[x]+=kw*(p3[b]-p3[a])}
                                if(has1){s4[x]+=kw*(p4[b]-p4[a]);s5[x]+=kw*(p5[b]-p5[a]);s6[x]+=kw*(p6[b]-p6[a])}
                            }
                            val runs=spans[sy%capacity];val runCount=spanCounts[sy%capacity]
                            for(t in row.tx.indices){val kx=row.tx[t];val kw=row.tw[t]*wy
                                // A zero depth-mask weight contributes exactly zero, including
                                // for dense signed HDR. Visit only nonzero source runs, in the
                                // same tail and receiver order; dark occluders are retained.
                                for(r in 0 until runCount step 2){
                                    val first=max(x0,runs[r]-kx);val last=min(x1,runs[r+1]-kx)
                                    if(first>x1)break
                                    for(x in first..last){if(!active[rowIndex*w+x])continue
                                        val mass=(kw*(node?.wx?.get(x)?:1f)*weights[base+x+kx]).toDouble();if(mass<=0)continue
                                        s0[x]+=mass;val i=src+colOf[x+kx]
                                        if(has0){s1[x]+=v0[i]*mass;s2[x]+=v1[i]*mass;s3[x]+=v2[i]*mass}
                                        if(has1){s4[x]+=v3[i]*mass;s5[x]+=v4[i]*mass;s6[x]+=v5[i]*mass}
                                    }
                                }
                            }
                        }
                    }
                    for(x in activeLo[rowIndex]..activeHi[rowIndex]){
                        val alpha=sums[0][x].toFloat().coerceIn(0f,1f);if(alpha<=1e-8f)continue
                        val di=rowIndex*w+x;val local=HighlightScatter.layerWeight(coc.data[y*w+x],level)
                        val take=min(alpha*HighlightScatter.visibility(coc.data[y*w+x],level),max(0f,1f-cover[di]))
                        for(c in 0 until channels){val value=(if(signed[c/3])sums[c+1][x] else max(0.0,sums[c+1][x])).toFloat()
                            own[c][di]+=local*(value/alpha);rgb[c][di]+=value*(take/alpha);if(level>0 && fg!=null)fg[c][di]+=value*(take/alpha)
                        };cover[di]=(cover[di]+take).coerceIn(0f,1f)
                    }
                }
            }
            for(c in 0 until channels){
                for(i in 0 until (y1-y0)*w)rgb[c][i]+=(1f-cover[i])*own[c][i]
                outputs[c/3].putRows(c%3,y0,y1-y0,rgb[c],cancel)
                if(fg!=null)checkNotNull(foreground)[c/3].putRows(c%3,y0,y1-y0,fg[c],cancel)
            }
            stats.receiverRows+=y1-y0;onProgress(y1,h);cancel.check()
        }
        outputs.forEach{it.sync()};foreground?.forEach{it.sync()}
    }
}
