package app;

/**
 * Holds the lone reference to the type {@code Holder}: the qualifier of the static call
 * {@code Holder.init()}. Syntactically {@code Holder} here is a {@code NameExpr} in value position;
 * semantically it is a reference to the type. Before the name-as-type fallback it surfaced as an
 * unconfirmed candidate (cause {@code MISSING_CLASSPATH}); it must be a confirmed reference to
 * {@code app/Holder#}, enclosed by {@code Caller.go()}.
 */
public class Caller {

    void go() {
        Holder.init();
    }
}
