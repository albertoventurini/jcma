package jcma.mcp.json;

/**
 * Thrown by {@link JsonReader} when input is not well-formed JSON. Unchecked so the
 * recursive-descent code stays clean; the M2 JSON-RPC read loop (task 2) catches it at the top of
 * the loop to emit a {@code -32700} parse-error response. Carries the 0-based character
 * {@link #offset()} into the document where parsing failed, so callers (and tests) can point at the
 * bad byte rather than guess.
 */
public final class JsonParseException extends RuntimeException {

    private final int offset;

    public JsonParseException(String message, int offset) {
        super(message + " (at offset " + offset + ")");
        this.offset = offset;
    }

    /** 0-based character offset into the parsed document where the error was detected. */
    public int offset() {
        return offset;
    }
}
