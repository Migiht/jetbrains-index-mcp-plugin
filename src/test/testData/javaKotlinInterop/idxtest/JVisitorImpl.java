package idxtest;

public class JVisitorImpl implements KVisitor {
    @Override
    public String visit(JShape shape) {
        return shape.describe();
    }
}
