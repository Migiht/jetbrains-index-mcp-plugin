package idxtest;

public abstract class JBase implements JShape {
    private String name;

    public JBase(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public abstract double area();

    public double scale(int factor) {
        return area() * factor;
    }

    public double scale(double factor) {
        return area() * factor;
    }

    public static JBase create(String kind) {
        if ("circle".equals(kind)) {
            return new JCircle(1.0);
        }
        return new KRect(2.0, 3.0);
    }
}
