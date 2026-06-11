package app;

/** Depth-2 subtype: {@code extends B} and overrides {@code m()} (superclass OVERRIDES → B#m). */
public class C extends B {

    @Override
    public void m() {
        // overrides B.m()
    }
}
