package jcma.query;

import java.time.Duration;

/**
 * Thrown by {@link QueryService} when a query does not complete within its {@code deadline} (M1
 * task-12). A clean, hard-to-ignore timeout signal — M1 deliberately returns <em>no</em> partial
 * answer (best-effort partial assembly is a deferred optimization; see the task's scope override).
 * The caller (CLI / REPL today; the MCP server in M2) reports it and moves on; the session is left
 * quiescent for the next query (the cancelled worker stops cooperatively at a candidate-file
 * boundary, never mid-edit).
 */
public final class QueryTimeoutException extends Exception {

    private final Duration deadline;

    public QueryTimeoutException(Duration deadline) {
        super("query exceeded its deadline of " + deadline.toMillis() + " ms");
        this.deadline = deadline;
    }

    /** The deadline that was exceeded. */
    public Duration deadline() {
        return deadline;
    }
}
