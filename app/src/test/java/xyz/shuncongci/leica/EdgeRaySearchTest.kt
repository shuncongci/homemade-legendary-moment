package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.*

@RunWith(Parameterized::class)
class EdgeRaySearchTest(private val scenario:Int) {
    companion object { @JvmStatic @Parameterized.Parameters(name="rays-{0}") fun cases()=(0..11).map{arrayOf(it)} }
    @Test fun matchesOriginalDistanceDirectionOrder(){
        val w=if(scenario==0)1 else if(scenario==1)53 else 137;val h=if(scenario==1)1 else 79
        val limit=if(scenario%3==0)128 else if(scenario%3==1)5 else 37
        val a=FloatArray(w*h){i->when(scenario){2->.3f;3->if(i%w<w/4)0f else if(i%w>3*w/4)1f else .5f;4->if(i==0)1f else if(i==w*h-1)0f else .25f;5->if(i/w<h/4)0f else if(i/w>3*h/4)1f else .2f;else->when((i*37+i/w*11)%31){0,3->0f;7,19->1f;else->.7f}}}
        val dirs=arrayOf(1 to 0,-1 to 0,0 to 1,0 to -1,1 to 1,-1 to 1,1 to -1,-1 to -1)
        for(top in 0 until h step 32){val end=min(h,top+32);val left=if(scenario<6)0 else 13;val right=if(scenario<6)w-1 else 111
            val got=EdgeRaySearch.find(a,w,h,top,end,left,right,limit,Cancel())
            for(y in top until end)for(x in left..right){if(a[y*w+x]<=0 || a[y*w+x]>=1)continue
                var fi=-1;var bi=-1;var fd=0;var bd=0
                search@ for(d in 1..limit){for((dx,dy) in dirs){val xx=x+dx*d;val yy=y+dy*d;if(xx !in 0 until w || yy !in 0 until h)continue;val j=yy*w+xx
                    if(fi<0 && a[j]>=1){fi=j;fd=d};if(bi<0 && a[j]<=0){bi=j;bd=d}
                };if(fi>=0 && bi>=0)break@search}
                val k=(y-top)*got.span+x-left
                assertEquals("fg $x,$y",fi,got.fg[k]);assertEquals("bg $x,$y",bi,got.bg[k])
                if(fi>=0)assertEquals(fd,got.fd[k]);if(bi>=0)assertEquals(bd,got.bd[k])
            }
        }
    }
}
