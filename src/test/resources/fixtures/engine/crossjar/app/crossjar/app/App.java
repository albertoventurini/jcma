package crossjar.app;

import crossjar.lib.Greeting;

// Cross-jar fixture — the SOURCE side (the caller). The native cross-jar smoke copies this into a
// temp project's src/main/java and runs `jcma resolve` at the `hello` call on line 12 (the `hello`
// name token begins at column 22). `Greeting` itself exists only inside the fixture jar, so a
// successful resolve proves agent-traced native-image metadata covers the JarTypeSolver surface.
public class App {
    void run() {
        Greeting g = new Greeting();
        String s = g.hello("world");
    }
}
