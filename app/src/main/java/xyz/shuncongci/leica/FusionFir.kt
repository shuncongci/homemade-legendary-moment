package xyz.shuncongci.leica

import kotlin.math.min

/** Exact separable FIR, not an IIR/box/downsample approximation.
 * Keeps each float multiply/add in the original tap order. Only independent pixels are interleaved.
 * Input LAB planes stay read-only until every stripe joins: halo reads must never race output writes.
 */
internal object FusionFir {
    const val STRIPE=128
    const val MAX_WORKERS=4
    fun channel(original:ByteArray,styled:ByteArray,w:Int,h:Int,sigma:Double,cancel:Cancel?) {
        require(original.size==w*h && styled.size==original.size && w>0 && h>0)
        cancel?.check()
        val k=Gaussian.kernel(sigma);val n=k.size;val r=n/2
        val output=ByteArray(original.size)
        val stripes=(w+STRIPE-1)/STRIPE
        val workers=min(stripes,min(MAX_WORKERS,Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
        parallelFor(workers){worker->
            // Bounded scratch per worker, reused for all its stripes.
            val ring=FloatArray(n*STRIPE)
            val tags=IntArray(n){-1}
            val row=FloatArray(STRIPE+2*r)
            val rows=IntArray(n)
            val xs=IntArray(STRIPE+2*r)
            for(stripe in worker until stripes step workers){
                cancel?.check();tags.fill(-1)
                val x0=stripe*STRIPE;val cw=min(STRIPE,w-x0)
                for(x in 0 until cw+2*r)xs[x]=Gaussian.reflect101(x0+x-r,w)
                for(y in 0 until h){
                    cancel?.check()
                    for(j in 0 until n){
                        val sy=Gaussian.reflect101(y+j-r,h);val slot=sy%n;val offset=slot*STRIPE
                        rows[j]=offset
                        if(tags[slot]==sy)continue
                        val base=sy*w
                        for(x in 0 until cw+2*r){val at=base+xs[x];row[x]=((styled[at].toInt()and 255)-(original[at].toInt()and 255)).toFloat()}
                        convolveRow(row,ring,offset,cw,k)
                        tags[slot]=sy
                    }
                    var x=0
                    while(x+7<cw){
                        var a0=0f;var a1=0f;var a2=0f;var a3=0f;var a4=0f;var a5=0f;var a6=0f;var a7=0f
                        for(j in 0 until n){val v=k[j];val at=rows[j]+x
                            a0+=v*ring[at];a1+=v*ring[at+1];a2+=v*ring[at+2];a3+=v*ring[at+3]
                            a4+=v*ring[at+4];a5+=v*ring[at+5];a6+=v*ring[at+6];a7+=v*ring[at+7]
                        }
                        val at=y*w+x0+x
                        output[at]=merge(original[at],a0);output[at+1]=merge(original[at+1],a1)
                        output[at+2]=merge(original[at+2],a2);output[at+3]=merge(original[at+3],a3)
                        output[at+4]=merge(original[at+4],a4);output[at+5]=merge(original[at+5],a5)
                        output[at+6]=merge(original[at+6],a6);output[at+7]=merge(original[at+7],a7)
                        x+=8
                    }
                    while(x<cw){var a=0f;for(j in 0 until n)a+=k[j]*ring[rows[j]+x]
                        val at=y*w+x0+x;output[at]=merge(original[at],a);x++}
                }
            }
        }
        cancel?.check();System.arraycopy(output,0,original,0,output.size)
    }
    private fun merge(original:Byte,diff:Float):Byte {
        val v=(original.toInt()and 255)+diff
        return (if(v<=0f)0 else if(v>=255f)255 else v.toInt()).toByte()
    }
    private fun convolveRow(row:FloatArray,out:FloatArray,offset:Int,width:Int,k:FloatArray){
        var x=0
        while(x+7<width){
            var a0=0f;var a1=0f;var a2=0f;var a3=0f;var a4=0f;var a5=0f;var a6=0f;var a7=0f
            for(j in k.indices){val v=k[j];val at=x+j
                a0+=v*row[at];a1+=v*row[at+1];a2+=v*row[at+2];a3+=v*row[at+3]
                a4+=v*row[at+4];a5+=v*row[at+5];a6+=v*row[at+6];a7+=v*row[at+7]
            }
            out[offset+x]=a0;out[offset+x+1]=a1;out[offset+x+2]=a2;out[offset+x+3]=a3
            out[offset+x+4]=a4;out[offset+x+5]=a5;out[offset+x+6]=a6;out[offset+x+7]=a7;x+=8
        }
        while(x<width){var a=0f;for(j in k.indices)a+=k[j]*row[x+j];out[offset+x]=a;x++}
    }
}
