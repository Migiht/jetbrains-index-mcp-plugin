@file:JvmName("KUtil")

package idxtest

/** Top-level helper IDXMARK_COMMENT */
fun square(x: Double): Double = x * x

fun Int.doubled(): Int = this * 2

fun depth(node: KNode): Int = when (node) {
    is KNode.Leaf -> 1
    is KNode.Branch -> 1 + maxOf(depth(node.left), depth(node.right))
}

typealias Points = List<KPoint>
