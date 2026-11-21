package idxtest

data class KPoint(val x: Int, val y: Int)

sealed class KNode {
    data class Leaf(val value: Int) : KNode()
    data class Branch(val left: KNode, val right: KNode) : KNode()
}

object KConfig {
    const val MAX = 5

    @JvmStatic
    fun limit(): Int = MAX * 2
}

class KCompanion(val id: Int) {
    companion object {
        @JvmField
        val TAG = "kc"

        @JvmStatic
        fun make(): KCompanion = KCompanion(7)
    }
}

interface KVisitor {
    fun visit(shape: JShape): String
}
