package idxtest

class KUser {
    fun useJava(): Double {
        val circle = JCircle(2.0)
        val n: String = circle.name
        val base = JBase.create("circle")
        val color = JColor.RED.next()
        val total = JUtil.twice(JUtil.LIMIT) + JShape.KIND.length
        val nested = JUtil.Nested().hello()
        val cb = JCallback { e -> println(e + n + color + nested) }
        cb.onEvent("go")
        val rect = KRect(1.0, 2.0)
        rect.scale(4)
        return base.area() + total + square(3.0) + 5.doubled()
    }

    fun anonymousShape(): JShape = object : JShape {
        override fun area(): Double = 0.0
    }
}

object KDefaultVisitor : KVisitor {
    override fun visit(shape: JShape): String = shape.describe()
}
