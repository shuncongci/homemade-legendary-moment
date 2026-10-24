package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/** Dense spatial previews: geometry/real depth means/subject layers, not model resolution. */
class SpaceGridTest {
    private fun frame(w:Int,h:Int,f:(Int)->Float)=LegendaryMath.Frame(w,h,1,FloatArray(w*h,f))
    private fun eachCell(w:Int,h:Int,cols:Int,rows:Int,block:(Int,Int,Int,Int,Int)->Unit){
        for(y in 0 until rows)for(x in 0 until cols)block(y*cols+x,x*w/cols,y*h/rows,(x+1)*w/cols,(y+1)*h/rows)
    }
    @Test fun fourByThreeHasFourTimesAsManyPieces(){
        assertEquals(48,DepthVisualMath.SPACE_GRID_EDGE)
        for((w,h) in listOf(4000 to 3000,3000 to 4000)){
            val old=DepthVisualMath.grid(w,h,24);val grid=DepthVisualMath.spatialGrid(w,h)
            assertEquals(1728,grid.first*grid.second);assertEquals(4*old.first*old.second,grid.first*grid.second)
            assertEquals(old.first*2,grid.first);assertEquals(old.second*2,grid.second)
        }
    }
    @Test fun commonAspectRatiosHaveBoundedDenseGrids(){
        assertEquals(48 to 48,DepthVisualMath.spatialGrid(2048,2048))
        assertEquals(48 to 32,DepthVisualMath.spatialGrid(6000,4000))
        assertEquals(48 to 27,DepthVisualMath.spatialGrid(3840,2160))
        assertEquals(27 to 48,DepthVisualMath.spatialGrid(2160,3840))
    }
    @Test fun tinyImagesNeverCreateDuplicatePixelCells(){
        for((w,h) in listOf(1 to 1,1 to 23,23 to 1,5 to 4,31 to 47)){
            val (cols,rows)=DepthVisualMath.spatialGrid(w,h)
            assertTrue(cols in 1..min(48,w));assertTrue(rows in 1..min(48,h))
            eachCell(w,h,cols,rows){_,l,t,r,b->assertTrue(r>l && b>t)}
            val v=DepthVisualMath.create(frame(w,h){.4f},Cancel())
            assertEquals(cols to rows,v.tiles.cols to v.tiles.rows);assertTrue(v.tiles.depth.all{abs(it-.4f)<1e-6f})
        }
    }
    @Test fun randomSizesStayWithinDrawBudgetAndPreserveOrientation(){
        val rng=Random(227)
        repeat(2000){
            val w=1+rng.nextInt(30000);val h=1+rng.nextInt(30000);val (cols,rows)=DepthVisualMath.spatialGrid(w,h)
            assertTrue(cols*rows<=2304);assertTrue(cols<=w && rows<=h)
            assertEquals(rows to cols,DepthVisualMath.spatialGrid(h,w))
            if(w>=48 && h>=48){assertEquals(48,max(cols,rows));assertTrue(abs(cols/rows.toDouble()-w/h.toDouble())<=w/h.toDouble()/rows+1.0/rows)}
        }
    }
    @Test fun cellPartitionCoversEveryPixelExactlyOnce(){
        for((w,h) in listOf(101 to 67,67 to 101,1 to 211,211 to 1,49 to 49,7 to 5)){
            val (cols,rows)=DepthVisualMath.spatialGrid(w,h);val counts=IntArray(w*h)
            eachCell(w,h,cols,rows){_,l,t,r,b->for(y in t until b)for(x in l until r)counts[y*w+x]++}
            assertTrue(counts.all{it==1})
        }
    }
    @Test fun singlePixelCellsRetainRealDepthExactly(){
        val d=frame(5,4){it/19f};val v=DepthVisualMath.create(d,Cancel())
        assertArrayEquals(d.data,v.tiles.depth,0f)
    }
    @Test fun nonDivisibleTilesMatchIndependentMeans(){
        val w=101;val h=67;val d=frame(w,h){((it*17)%1009)/1008f};val v=DepthVisualMath.create(d,Cancel())
        eachCell(w,h,v.tiles.cols,v.tiles.rows){i,l,t,r,b->
            var sum=0.0;for(y in t until b)for(x in l until r)sum+=d.data[y*w+x]
            assertEquals((sum/((r-l)*(b-t))).toFloat(),v.tiles.depth[i],1e-7f)
        }
    }
    @Test fun smallerTilesResolveDetailsPreviouslyAveragedTogether(){
        val v=DepthVisualMath.create(frame(96,96){if((it%96)/2%2==0).2f else .8f},Cancel())
        assertEquals(48,v.tiles.cols)
        for(y in 0 until 48)for(x in 0 until 48)assertEquals(if(x%2==0).2f else .8f,v.tiles.depth[y*48+x],1e-6f)
    }
    @Test fun areaWeightedMeanIsPreservedAcrossUnevenCells(){
        val w=103;val h=71;val d=frame(w,h){(it%83)/82f};val v=DepthVisualMath.create(d,Cancel());var sum=0.0
        eachCell(w,h,v.tiles.cols,v.tiles.rows){i,l,t,r,b->sum+=v.tiles.depth[i].toDouble()*(r-l)*(b-t)}
        assertEquals(d.data.sumOf{it.toDouble()}/d.size,sum/d.size,1e-7)
    }
    @Test fun subjectAndReliefUseTheSameGrid(){
        for((w,h) in listOf(96 to 64,67 to 101,1 to 32,31 to 1)){
            val d=frame(w,h){.4f};val v=DepthVisualMath.create(d,Cancel());val s=SubjectDepthMotion.create(d,frame(w,h){.5f},.7f,Cancel())
            assertEquals(v.tiles.cols to v.tiles.rows,s.foreground.cols to s.foreground.rows)
            assertEquals(s.foreground.cols to s.foreground.rows,s.background.cols to s.background.rows)
            assertEquals(v.tiles.depth.size,s.coverage.size);assertTrue(s.coverage.all{it==.5f})
        }
    }
    @Test fun denseSubjectWeightedMeansMatchIndependentReference(){
        val w=101;val h=67;val d=frame(w,h){(it%29)/28f};val a=frame(w,h){(it%7)/6f}
        val s=SubjectDepthMotion.create(d,a,.6f,Cancel())
        eachCell(w,h,s.foreground.cols,s.foreground.rows){i,l,t,r,b->
            var mass=0.0;var fs=0.0;var bs=0.0;var total=0.0;val n=(r-l)*(b-t)
            for(y in t until b)for(x in l until r){val at=y*w+x;val alpha=a.data[at].toDouble();val depth=d.data[at].toDouble();mass+=alpha;fs+=alpha*depth;bs+=(1-alpha)*depth;total+=depth}
            assertEquals((mass/n).toFloat(),s.coverage[i],1e-7f)
            assertEquals((if(mass>1e-8)fs/mass else total/n).toFloat(),s.foreground.depth[i],1e-7f)
            assertEquals((if(n-mass>1e-8)bs/(n-mass) else total/n).toFloat(),s.background.depth[i],1e-7f)
        }
    }
    @Test fun denseSubjectStillEndsExactlyOnFocusPlane(){
        val d=frame(96,64){(it%53)/52f};val s=SubjectDepthMotion.create(d,frame(96,64){if(it%3==0)1f else .1f},.67f,Cancel())
        for(depth in s.foreground.depth)assertEquals(SubjectDepthMotion.z(.67f),SubjectDepthMotion.movedZ(depth,s.focus,1f),0f)
    }
    @Test fun coarseFullAndPhotoGridsRemapWithoutOutOfBounds(){
        val coarse=DepthVisualMath.create(frame(113,75){(it%113)/112f},Cancel()).tiles
        val full=DepthVisualMath.create(frame(137,91){(it%137)/136f},Cancel()).tiles
        val (cols,rows)=DepthVisualMath.spatialGrid(960,638)
        for(map in listOf(coarse,full))for(i in 0 until cols*rows){
            val x=floor((i%cols+.5)*map.cols/cols).toInt();val y=floor((i/cols+.5)*map.rows/rows).toInt()
            assertEquals(map.depth[y*map.cols+x],ProgressiveDepth.tileDepth(map,i,cols,rows),0f)
        }
    }
    @Test fun denseCoarseToFullTransitionsRemainBounded(){
        val (cols,rows)=DepthVisualMath.spatialGrid(4031,3023)
        for(i in 0 until cols*rows){val a=ProgressiveDepth.floatOffset(i,.2f);val b=((i%97)/96f-.5f)*.95f
            for(t in listOf(0f,.1f,.5f,.9f,1f)){val z=ProgressiveDepth.blend(a,b,t);assertTrue(z>=min(a,b)-1e-6f && z<=max(a,b)+1e-6f)}
        }
    }
    @Test fun reusedProjectorMatchesIndependentCameraReference(){
        val camera=DepthVisualMath.Projector();val out=FloatArray(3)
        for(yaw in listOf(-.85f,-.38f,0f,.38f,.85f))for(pitch in listOf(-.55f,-.14f,0f,.55f)){
            camera.setAngles(yaw,pitch)
            for(x in listOf(-.5f,0f,.5f))for(y in listOf(-.5f,0f,.5f))for(z in listOf(-.475f,0f,.475f)){
                camera.project(x,y,z,out)
                val xx=x*cos(yaw.toDouble())+z*sin(yaw.toDouble());val zz=-x*sin(yaw.toDouble())+z*cos(yaw.toDouble())
                val yy=y*cos(pitch.toDouble())-zz*sin(pitch.toDouble());val depth=y*sin(pitch.toDouble())+zz*cos(pitch.toDouble());val scale=3.8/(3.8-depth)
                assertEquals((xx*scale).toFloat(),out[0],2e-7f);assertEquals((yy*scale).toFloat(),out[1],2e-7f);assertEquals(depth.toFloat(),out[2],2e-7f)
            }
        }
    }
    @Test fun reusedProjectorOnlyWritesRequestedRange(){
        val camera=DepthVisualMath.Projector();camera.setAngles(.38f,-.14f);val out=FloatArray(7){-99f};val ref=FloatArray(3)
        camera.project(.2f,-.1f,.4f,out,2);DepthVisualMath.project(.2f,-.1f,.4f,.38f,-.14f,ref)
        assertArrayEquals(ref,out.copyOfRange(2,5),0f);for(i in listOf(0,1,5,6))assertEquals(-99f,out[i],0f)
    }
    @Test fun cameraRetargetDoesNotUseStaleAngles(){
        val camera=DepthVisualMath.Projector();val out=FloatArray(3);val ref=FloatArray(3)
        for(i in 0..100){val yaw=(i-50)/60f;val pitch=(50-i)/100f;camera.setAngles(yaw,pitch);camera.project(.23f,-.17f,.42f,out);DepthVisualMath.project(.23f,-.17f,.42f,yaw,pitch,ref);assertArrayEquals(ref,out,0f)}
    }
    @Test fun allDenseCornersRemainFiniteAcrossDragRange(){
        val (cols,rows)=DepthVisualMath.spatialGrid(4000,3000);val p=DepthVisualMath.Projector();val out=FloatArray(3)
        for(yaw in listOf(-.85f,.38f,.85f))for(pitch in listOf(-.55f,-.14f,.55f)){
            p.setAngles(yaw,pitch)
            for(y in 0..rows)for(x in 0..cols)for(z in listOf(-.475f,.475f)){
                p.project(x.toFloat()/cols-.5f,(y.toFloat()/rows-.5f)*.75f,z,out);assertTrue(out.all{it.isFinite() && abs(it)<2})
            }
        }
    }
    @Test fun generatingDenseDataDoesNotMutateInputs(){
        val d=frame(97,73){(it%11)/10f};val a=frame(97,73){(it%3)/2f};val before=d.data.copyOf();val mask=a.data.copyOf()
        DepthVisualMath.create(d,Cancel());SubjectDepthMotion.create(d,a,.6f,Cancel());assertArrayEquals(before,d.data,0f);assertArrayEquals(mask,a.data,0f)
    }
    @Test fun nonPositiveSpatialSizesAreRejected(){
        for((w,h) in listOf(0 to 1,1 to 0,-1 to 9,9 to -1))try{DepthVisualMath.spatialGrid(w,h);fail("invalid size accepted")}catch(_:IllegalArgumentException){}
    }
}
