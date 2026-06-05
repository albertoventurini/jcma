// Fixture (test data, default package): the call site of the unit resolve test.
// The call `c.compute(21)` is on line 7; the `compute` name token begins at column 19. The test
// resolves at Position(7, 19), expecting ResolvedRef signature `Callee.compute(int)`, decl Callee.java:2.
public class Caller {
    void run() {
        Callee c = new Callee();
        int r = c.compute(21);
    }
}
