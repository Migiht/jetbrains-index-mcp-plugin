package idxtest;

/** Shape contract shared by Java and Kotlin. IDXMARK_COMMENT */
public interface JShape {
    String KIND = "shape";

    double area();

    default String describe() {
        return KIND + ":" + area();
    }
}
