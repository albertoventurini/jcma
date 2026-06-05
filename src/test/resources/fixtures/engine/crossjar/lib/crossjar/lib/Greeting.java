package crossjar.lib;

// Cross-jar fixture — the JAR side. build.gradle.kts compiles this into a fixture jar at a known
// build-output path; nothing binary is committed. The native cross-jar smoke points a temp
// project's cp.txt at that jar and asserts `hello(String)` resolves through JarTypeSolver to
// crossjar.lib.Greeting.hello(java.lang.String) with no project source for the callee.
public class Greeting {
    public String hello(String name) {
        return "hi " + name;
    }
}
