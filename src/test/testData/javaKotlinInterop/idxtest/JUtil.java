package idxtest;

public final class JUtil {
    public static final int LIMIT = 42;

    private JUtil() {
    }

    public static int twice(int x) {
        int local = x * 2;
        return local;
    }

    public static double callScales(JBase base) {
        return base.scale(2) + base.scale(2.5) + base.scale(3);
    }

    public static class Nested {
        public String hello() {
            return "IDXMARK_STRING";
        }
    }
}
