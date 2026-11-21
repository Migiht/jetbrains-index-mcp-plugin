package idxtest;

import java.util.List;

public class JCircle extends JBase implements JCallback {
    private final double radius;

    public JCircle(double radius) {
        super("circle");
        this.radius = radius;
    }

    @Override
    public double area() {
        return Math.PI * KUtil.square(radius);
    }

    @Override
    public void onEvent(String event) {
        System.out.println(event + KConfig.MAX + KConfig.limit());
    }

    public int useKotlin(List<KPoint> points) {
        KPoint p = new KPoint(1, 2);
        int sum = p.getX() + p.component2();
        KRect rect = new KRect(4.0, 5.0);
        rect.setWidth(rect.getWidth() + 1);
        KCompanion c = KCompanion.make();
        String tag = KCompanion.TAG + c.getId();
        int d = KUtil.doubled(sum);
        KNode node = new KNode.Leaf(d);
        return points.size() + tag.length() + KUtil.depth(node);
    }
}
