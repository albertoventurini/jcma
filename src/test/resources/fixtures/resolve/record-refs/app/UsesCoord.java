package app;

/** References the single-line record {@code Coord} as a type from two enclosing methods. */
public class UsesCoord {

    Coord first(Coord c) {
        return c;
    }

    void second() {
        Coord local = first(null);
    }
}
