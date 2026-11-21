package idxtest

@JMarker("rect")
open class KRect(var width: Double, val height: Double) : JBase("rect") {
    override fun area(): Double = width * height

    override fun describe(): String = "rect " + super.describe()
}

class KSquare(side: Double) : KRect(side, side) {
    override fun area(): Double = width * width
}
