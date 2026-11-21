package idxtest;

public enum JColor {
    RED,
    GREEN;

    public JColor next() {
        return this == RED ? GREEN : RED;
    }
}
