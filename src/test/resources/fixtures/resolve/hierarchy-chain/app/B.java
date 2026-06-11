package app;

/** Depth-1 subtype: {@code implements A} and overrides {@code m()} (interface-impl OVERRIDES → A#m). */
public class B implements A {

    @Override
    public void m() {
        // implements A.m()
    }
}
