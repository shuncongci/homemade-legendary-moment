package xyz.shuncongci.leica

/** Actual base-layer callback, not a spatial small-tile render or an estimated fraction. */
data class BokehLayerProgress(val part:Int,val parts:Int,val done:Int,val layers:Int) {
    init{require(part in 1..parts && parts in 1..4 && layers in 1..LayeredBokeh.LAYERS && done in 1..layers)}
    val cells get()=done*16/layers
}

/** Four-by-four display indicators inside each real base region. With ordinary 64 layers,
 * exactly four completed layers unlock one cell. Shortcut paths complete only on real output. */
class BokehLayerReveal(width:Int,height:Int,split:Boolean) {
    private val cores:List<BokehBox> = BokehRenderPolicy.parts(width,height,split)
    private val macroCols=cores.map{it.left}.distinct().size
    private val macroRows=cores.map{it.top}.distinct().size
    val cols:Int=macroCols*4;val rows:Int=macroRows*4
    val parts:Int=cores.size
    private val completed=BooleanArray(parts)
    private val doneLayers=IntArray(parts);private val totalLayers=IntArray(parts)
    private val counts=IntArray(parts)
    private val ages=FloatArray(parts*16){10000f}
    private var completedParts=0
    fun partForCell(i:Int):Int {require(i in 0 until cols*rows);return (i/cols/4)*macroCols+(i%cols/4)}
    fun localCell(i:Int)=(i/cols%4)*4+i%cols%4
    fun accept(p:BokehLayerProgress):Boolean {
        if(p.parts!=parts || p.part>completedParts+1)return false
        val i=p.part-1
        if(completed[i] || p.done<=doneLayers[i] || (totalLayers[i]!=0 && totalLayers[i]!=p.layers))return false
        totalLayers[i]=p.layers;doneLayers[i]=p.done;unlock(i,p.cells);return true
    }
    private fun unlock(part:Int,cells:Int){for(c in counts[part] until cells)ages[part*16+c]=0f;counts[part]=cells}
    fun partCompleted(part:Int):Boolean {
        if(part!=completedParts+1 || part !in 1..parts)return false
        completed[part-1]=true;completedParts++;unlock(part-1,16);return true
    }
    fun litCount(part:Int):Int {require(part in 1..parts);return counts[part-1]}
    fun isLit(i:Int)=localCell(i)<counts[partForCell(i)]
    fun hasBase(i:Int)=completed[partForCell(i)]
    fun dark(i:Int)=if(isLit(i))0f else .52f
    fun glow(i:Int):Float {val part=partForCell(i);val cell=localCell(i);return if(cell<counts[part]).018f+.045f*(1-ages[part*16+cell]/600f).coerceIn(0f,1f)else 0f}
    val needsFrames get()=ages.any{it<600f}
    fun advance(ms:Float){require(ms.isFinite() && ms>=0);for(i in ages.indices)ages[i]=(ages[i]+ms).coerceAtMost(10000f)}
    fun settle(){ages.fill(10000f)}
}
