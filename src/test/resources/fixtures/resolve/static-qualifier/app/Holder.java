package app;

/**
 * The static-qualifier target: a type whose <em>only</em> use elsewhere is as the qualifier of a
 * static-member access ({@code Holder.init()}). No {@code new Holder()} and no {@code Holder}-typed
 * declaration exists, so the sole occurrence of the name {@code Holder} is a {@code NameExpr} in
 * value position — the case JavaSymbolSolver's value resolution rejects with UnsolvedSymbolException.
 */
public class Holder {

    static void init() {
        // body irrelevant
    }
}
