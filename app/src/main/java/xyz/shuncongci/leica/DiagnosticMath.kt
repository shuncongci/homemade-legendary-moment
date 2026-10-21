package xyz.shuncongci.leica

object DiagnosticMath {
    data class Cpu(val oneCorePercent:Double,val capacityPercent:Double)
    fun cpu(previousCpu:Long,cpu:Long,previousWall:Long,wall:Long,cores:Int):Cpu? {
        if(cores<=0 || wall<=previousWall || cpu<previousCpu)return null
        val one=(cpu-previousCpu)*100.0/(wall-previousWall)
        return Cpu(one,one/cores)
    }
    fun percentile(values:List<Double>,q:Double):Double? {
        require(q in 0.0..1.0)
        if(values.isEmpty())return null
        val sorted=values.sorted();return sorted[kotlin.math.ceil((sorted.size-1)*q).toInt()]
    }
}
