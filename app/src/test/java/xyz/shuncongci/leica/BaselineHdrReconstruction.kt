package xyz.shuncongci.leica
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame
/** Test-only scalar extraction of the original 2.5.1 loop, retained as an independent reference. */
internal object BaselineHdrReconstruction {
    fun render(input:HdrReconstruction.Input,exif:Int,w:Int,h:Int,cancel:Cancel):Pair<Frame,Frame> {
        fun gainValue(x:Int,y:Int,c:Int)=input.gain.get(x,y,c)/255f
        val low=input.minimum;val high=input.maximum;val gamma=input.gamma;val es=input.epsilonSdr;val eh=input.epsilonHdr

        val ow=if(exif in 5..8)input.height else input.width;val oh=if(exif in 5..8)input.width else input.height

        require(w in 1..ow && h in 1..oh){"HDR工作尺寸不可放大"}

        val s=Frame(w,h,3,FloatArray(w*h*3));val hdr=Frame(w,h,3,FloatArray(w*h*3))

        val sx=ow.toDouble()/w;val sy=oh.toDouble()/h

        fun encodedPosition(x:Int,y:Int):Pair<Int,Int> = when(exif){

            2->input.width-1-x to y;3->input.width-1-x to input.height-1-y;4->x to input.height-1-y

            5->y to x;6->y to input.height-1-x;7->input.width-1-y to input.height-1-x;8->input.width-1-y to x;else->x to y

        }

        fun mapValue(x:Int,y:Int,c:Int):Float {

            val gx=(x+.5)*input.gainWidth/input.width-.5;val gy=(y+.5)*input.gainHeight/input.height-.5

            val ix=floor(gx).toInt();val iy=floor(gy).toInt();val fx=(gx-ix).toFloat();val fy=(gy-iy).toFloat();var value=0f

            for(dy in 0..1)for(dx in 0..1)value+=gainValue((ix+dx).coerceIn(0,input.gainWidth-1),(iy+dy).coerceIn(0,input.gainHeight-1),c)*(if(dx==0)1-fx else fx)*(if(dy==0)1-fy else fy)

            return value.coerceIn(0f,1f)

        }

        fun convert(v:FloatArray):FloatArray {

            if(input.p3){

                val r=1.2249402f*v[0]-.2249402f*v[1];val g=-.04205696f*v[0]+1.042057f*v[1];val b=-.01963755f*v[0]-.07863605f*v[1]+1.0982736f*v[2]

                val y=max(0f,.2126f*r+.7152f*g+.0722f*b);val low=min(r,min(g,b))

                val t=if(low<0f)y/(y-low).coerceAtLeast(1e-12f)else 1f

                v[0]=max(0f,y+(r-y)*t);v[1]=max(0f,y+(g-y)*t);v[2]=max(0f,y+(b-y)*t)

            };return v

        }

        val a=FloatArray(3);val z=FloatArray(3);val sums=DoubleArray(6)

        // Reconstruct each native pixel first, THEN area-reduce HDR radiance; do not multiply separately resized layers.

        for(y in 0 until h){cancel.check();for(x in 0 until w){val l=x*sx;val r=(x+1)*sx;val t=y*sy;val b=(y+1)*sy

            sums.fill(0.0)

            for(yy in floor(t).toInt() until min(oh,ceil(b).toInt()))for(xx in floor(l).toInt() until min(ow,ceil(r).toInt())){

                val (u,v)=encodedPosition(xx,yy)

                for(c in 0..2){a[c]=input.eotf[input.raw.get(u,v,c)]

                    z[c]=BaselineHdrMath.recover(a[c],mapValue(u,v,c),low[c],high[c],gamma[c],es[c],eh[c])}

                val aa=convert(a);val zz=convert(z)

                val weight=(min(r,xx+1.0)-max(l,xx.toDouble()))*(min(b,yy+1.0)-max(t,yy.toDouble()))/(sx*sy)

                for(c in 0..2){sums[c]+=aa[c]*weight;sums[c+3]+=zz[c]*weight}

            }

            for(c in 0..2){val at=c*s.size+y*w+x;s.data[at]=sums[c].toFloat();hdr.data[at]=sums[c+3].toFloat()}

        }};return s to hdr
    }
}
