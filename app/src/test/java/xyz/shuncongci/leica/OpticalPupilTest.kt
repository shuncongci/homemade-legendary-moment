package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*
import java.util.concurrent.CancellationException

class OpticalPupilTest {
    private fun kernel(x:Double,y:Double,r:Int=12,ap:Float=.95f)=OpticalPupil.kernel(r,ap,OpticalPupil.shape(x,y,401,401),Cancel())
    private fun moments(k:LegendaryMath.Kernel):Triple<Double,Double,Double>{var xx=0.0;var yy=0.0;var xy=0.0;for(y in 0 until k.w)for(x in 0 until k.w){val a=k.data[y*k.w+x].toDouble();val dx=(x-k.half).toDouble();val dy=(y-k.half).toDouble();xx+=a*dx*dx;yy+=a*dy*dy;xy+=a*dx*dy};return Triple(xx,yy,xy)}
    @Test fun centerIsUnmodifiedAperture(){for(ap in LegendaryMath.apertures)assertArrayEquals(LegendaryMath.kernel(7,ap).data,kernel(.5,.5,7,ap).data,0f)}
    @Test fun centralWideOpenPsfIsCircular(){val (x,y,xy)=moments(kernel(.5,.5));assertEquals(x,y,1e-7);assertEquals(0.0,xy,1e-7)}
    @Test fun radiusCompressionIsContinuousAndMonotone(){var prev=1.0;for(i in 0..100){val q=OpticalPupil.shape(.5+i/200.0,.5+i/200.0,401,401).radialRatio;assertTrue(q<=prev+1e-12);assertTrue(q>=OpticalPupil.MIN_RADIAL_RATIO);prev=q};assertEquals(.82,prev,1e-6)}
    @Test fun rightEdgeLongAxisIsVertical(){val (x,y,_)=moments(kernel(1.0,.5));assertTrue(y>x*1.2)}
    @Test fun topEdgeLongAxisIsHorizontal(){val (x,y,_)=moments(kernel(.5,0.0));assertTrue(x>y*1.2)}
    @Test fun cornerLongAxisIsTangentialNotRadial(){val (x,y,xy)=moments(kernel(1.0,1.0));assertTrue(x+y-2*xy>(x+y+2*xy)*1.3)}
    @Test fun oppositeEdgesAreMirrorConsistent(){val a=moments(kernel(1.0,.5));val b=moments(kernel(0.0,.5));assertEquals(a.first,b.first,1e-7);assertEquals(a.second,b.second,1e-7)}
    @Test fun orientationUsesPixelAspectNotSquareCoordinates(){val s=OpticalPupil.shape(1.0,1.0,801,401);assertEquals(2.0,s.ux/s.uy,1e-8)}
    @Test fun everyPsfIsFiniteNonnegativeAndNormalized(){for(ap in LegendaryMath.apertures)for(r in intArrayOf(1,3,12))for((x,y)in listOf(.5 to .5,1.0 to .5,1.0 to 1.0)){val k=kernel(x,y,r,ap);assertTrue(k.data.all{it.isFinite() && it>=0});assertEquals(1.0,k.data.sumOf{it.toDouble()},2e-6)}}
    @Test fun fieldDoesNotEnlargeLongAxisOrKernelBounds(){for((x,y)in listOf(.5 to .5,.5 to 1.0,1.0 to 1.0)){val k=kernel(x,y);assertEquals(13,k.half);for(yy in 0 until k.w)for(xx in 0 until k.w)if(hypot((xx-k.half).toDouble(),(yy-k.half).toDouble())>12+sqrt(.5))assertEquals(0f,k.data[yy*k.w+xx],0f)}}
    @Test fun gridWeightsAlwaysPartitionUnity(){for((w,h)in listOf(1 to 1,1 to 19,35 to 1,31 to 23)){val nodes=OpticalPupil.nodes(w,h);for(y in 0 until h)for(x in 0 until w)assertEquals(1f,nodes.sumOf{(it.wx[x]*it.wy[y]).toDouble()}.toFloat(),1e-6f)}}
    @Test fun zeroRadiusIsIdentityAtEveryPosition(){assertArrayEquals(floatArrayOf(1f),kernel(1.0,1.0,0).data,0f)}
    @Test fun cancelledKernelStops(){try{OpticalPupil.kernel(50,.95f,OpticalPupil.shape(1.0,1.0,401,401),Cancel().apply{cancelled=true});fail()}catch(_:CancellationException){}}
    @Test fun zeroDefocusPreservesEveryPixel(){val random=java.util.Random(44);val high=Frame(33,25,3,FloatArray(2475){random.nextFloat()});val copy=high.data.copyOf();val out=HighlightScatter.optical(high,Frame(33,25,1,FloatArray(825)),.95f,Cancel());assertArrayEquals(copy,out.data,0f);assertArrayEquals(copy,high.data,0f)}
    @Test fun constantRadianceWithChangingDepthDoesNotDimOrBoost(){val r=java.util.Random(71);val high=Frame(31,23,3,FloatArray(2139){.37f});val coc=Frame(31,23,1,FloatArray(713){r.nextFloat()*8-4});val out=HighlightScatter.optical(high,coc,2.8f,Cancel());assertTrue(out.data.all{abs(it-.37f)<3e-6f})}
    @Test fun zeroCocForegroundStillRejectsBrightFarBackground(){val w=49;val n=w*w;val high=Frame(w,w,3,FloatArray(n*3){.5f});val coc=Frame(w,w,1,FloatArray(n){-8f});for(y in 0 until w){val i=y*w+38;coc.data[i]=0f;for(c in 0..2)high.data[c*n+i]=0f};val out=HighlightScatter.optical(high,coc,.95f,Cancel());assertEquals(0f,out.data[24*w+38],1e-6f)}
    @Test fun opticalRendererActuallyDiffersFromUniformPupil(){val w=101;val n=w*w;val high=Frame(w,w,3,FloatArray(n*3));high.data[50*w+80]=1f;val coc=Frame(w,w,1,FloatArray(n){8f});val a=HighlightScatter.optical(high,coc,.95f,Cancel());val b=HighlightScatter.scatter(high,coc,.95f,Cancel());assertTrue(a.data.indices.any{abs(a.data[it]-b.data[it])>1e-5f})}
    @Test fun completeRenderStillProtectsOpaqueSubjectAgainstBackground(){val w=31;val h=25;val p=Planes(w,h,ByteArray(w*h){245.toByte()},ByteArray(w*h){200.toByte()},ByteArray(w*h){120});val a=Frame(w,h,1,FloatArray(w*h){if(it%w>15)1f else 0f});val d=Frame(w,h,1,FloatArray(w*h){.1f});val result=LegendaryMath.render(p,d,.9f,.95f,Cancel(),subjectMask=a){rgb,_->rgb};for(c in 0..2)for(i in 0 until w*h)if(a.data[i]==1f)assertEquals(p.plane(c)[i],result.plane(c)[i])}
    @Test fun completeRenderRoutesForegroundAcrossProtectedSubject(){
        val w=151;val h=101;val n=w*h;val p=Planes(w,h);val a=Frame(w,h,1,FloatArray(n){if(it%w>=75)1f else 0f});val d=Frame(w,h,1,FloatArray(n){.9f});val c=Cancel()
        for(i in 0 until n)for(ch in 0..2)p.plane(ch)[i]=(if(a.data[i]==1f)80+(i%2)*20 else 25).toByte()
        for(y in 47..53)for(x in 66..70)for(ch in 0..2)p.plane(ch)[y*w+x]=245.toByte()
        val coc=SubjectProtectionMath.protectedCoc(FullFrameOptics.coc(d,.1f,.95f,w,h,c),a,c);var near:Frame?=null
        val rendered=LegendaryMath.renderCoc(p,coc,.95f,c,onForeground={near=it},subjectAlpha=a){rgb,_->rgb}
        val expected=BokehRadiance.protect(p,rendered,a,checkNotNull(near),c)
        val actual=LegendaryMath.render(p,d,.1f,.95f,c,subjectMask=a){rgb,_->rgb}
        for(ch in 0..2)assertArrayEquals(expected.plane(ch),actual.plane(ch))
        assertTrue((0 until n).any{a.data[it]==1f && (actual.p0[it].toInt() and 255)>(p.p0[it].toInt() and 255)})
        for(i in 0 until n)if(a.data[i]==1f && (0..2).all{near!!.data[it*n+i]==0f})for(ch in 0..2)assertEquals(p.plane(ch)[i],actual.plane(ch)[i])
    }
    private fun reflect(v:Int,n:Int):Int{if(n==1)return 0;var x=v;while(x<0 || x>=n)x=if(x<0)-x else 2*n-2-x;return x}
    /** Independent direct convolution (no row-prefix plans), including signed-depth composition. */
    private fun reference(high:Frame,coc:Frame,ap:Float):FloatArray {
        val w=high.w;val h=high.h;val n=w*h;val out=DoubleArray(3*n);val own=DoubleArray(3*n);val coverage=DoubleArray(n)
        val nodes=OpticalPupil.nodes(w,h)
        for(level in ceil(coc.data.maxOrNull()!!.toDouble()).toInt() downTo floor(coc.data.minOrNull()!!.toDouble()).toInt()){
            val kernels=nodes.map{OpticalPupil.kernel(abs(level),ap,OpticalPupil.shape(it.x,it.y,w,h),Cancel())}
            for(y in 0 until h)for(x in 0 until w){val i=y*w+x;var mass=0.0;val radiance=DoubleArray(3)
                for(j in nodes.indices){val node=nodes[j];val nw=(node.wx[x]*node.wy[y]).toDouble();if(nw<=0)continue;val k=kernels[j]
                    for(ky in 0 until k.w)for(kx in 0 until k.w){val si=reflect(y+ky-k.half,h)*w+reflect(x+kx-k.half,w);val value=nw*k.data[ky*k.w+kx]*max(0.0,1-abs(coc.data[si]-level).toDouble());mass+=value;for(c in 0..2)radiance[c]+=high.data[c*n+si]*value}
                }
                val alpha=mass.coerceIn(0.0,1.0);if(alpha<=1e-8)continue
                val receiver=coc.data[i].toDouble();val behind=(receiver-level-1).coerceIn(0.0,1.0);val visibility=1-behind*(1-min(1.0,abs(receiver)/max(abs(level),1)))
                val take=min(alpha*visibility,max(0.0,1-coverage[i]));val local=max(0.0,1-abs(receiver-level))
                for(c in 0..2){own[c*n+i]+=local*radiance[c]/alpha;out[c*n+i]+=radiance[c]*take/alpha};coverage[i]+=take
            }
        }
        return FloatArray(3*n){i->(out[i]+(1-coverage[i%n])*own[i]).toFloat()}
    }
    @Test fun optimizedFieldScatterMatchesIndependentBruteForce(){val r=java.util.Random(114);val high=Frame(11,9,3,FloatArray(297){r.nextFloat()});val coc=Frame(11,9,1,FloatArray(99){r.nextFloat()*5-2.5f});for(ap in floatArrayOf(.95f,2.8f,5.6f))for(scale in floatArrayOf(1f,1.5f,3f)){
        // Retained 1x/1.5x and production 3x all match independent direct convolution.
        val scaled=Frame(coc.w,coc.h,1,FloatArray(coc.size){coc.data[it]*scale})
        val expected=reference(high,scaled,ap);val actual=HighlightScatter.opticalAtScale(high,coc,ap,Cancel(),scale).data
        val error=actual.indices.maxOf{abs(actual[it]-expected[it])};println("field PSF independent reference f/$ap radius=$scale max=$error");assertTrue(error<5e-6f)
    }}
}
