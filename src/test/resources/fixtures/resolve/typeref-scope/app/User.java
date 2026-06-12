package app;

/**
 * References four distinct types as type-refs: {@code Alpha} twice (a field type + a parameter type),
 * and {@code Beta}/{@code Gamma}/{@code Delta} once each. A name-scoped {@code find_references(Alpha)}
 * must resolve only the two {@code Alpha} type-refs here, not all five — the B1 scoping property.
 */
public class User {

    Alpha a;
    Beta b;

    Gamma make(Delta d) {
        return null;
    }

    void use(Alpha other) {
    }
}
