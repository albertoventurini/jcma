// Fixture (test data, default package): the declaration target of the unit resolve test.
// `compute(int)` is declared on line 5 — JavaParserEngineTest asserts the resolved declaration
// points here at Callee.java:5.
public class Callee {
    public int compute(int x) {
        return x * 2;
    }
}
