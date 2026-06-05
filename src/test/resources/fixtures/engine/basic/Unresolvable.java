// Fixture (test data, default package): the negative case. `Mystery` is declared nowhere on the
// workspace source root or classpath, so resolving the call `m.doStuff()` on line 7 (the `doStuff`
// token begins at column 11) must safe-degrade to Optional.empty() — never a silent-wrong answer.
public class Unresolvable {
    void run() {
        Mystery m = null;
        m.doStuff();
    }
}
