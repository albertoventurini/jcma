package jcma.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared {@code --deadline <value>} parsing for the query subcommands and the REPL (M1 task-12). The
 * value is a time-box: a bare number is milliseconds; an {@code ms} or {@code s} suffix is honoured
 * ({@code 200}, {@code 200ms}, {@code 2s}). When the flag is absent a {@linkplain #DEFAULT generous
 * default} applies, so the common interactive case is unbounded-enough while a tight {@code --deadline}
 * can force a {@link jcma.query.QueryTimeoutException}.
 */
final class Deadline {

    private Deadline() {}

    /** Generous default time-box when {@code --deadline} is omitted. */
    static final Duration DEFAULT = Duration.ofSeconds(30);

    /** The positional args with {@code --deadline <value>} stripped, the parsed deadline, or an error. */
    record Parsed(String[] positional, Duration deadline, String error) {}

    /** Strip a {@code --deadline <value>} pair from {@code args}; everything else is positional. */
    static Parsed parse(String[] args) {
        List<String> positional = new ArrayList<>();
        Duration deadline = DEFAULT;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--deadline")) {
                if (i + 1 >= args.length) {
                    return new Parsed(null, null, "--deadline requires a value (e.g. --deadline 200ms)");
                }
                String value = args[++i];
                Duration parsed = parseDuration(value);
                if (parsed == null) {
                    return new Parsed(null, null,
                            "bad --deadline '" + value + "' — expected ms, e.g. 200 or 200ms or 2s");
                }
                deadline = parsed;
            } else {
                positional.add(args[i]);
            }
        }
        return new Parsed(positional.toArray(new String[0]), deadline, null);
    }

    /** Parse {@code "200"}, {@code "200ms"}, or {@code "2s"} into a positive {@link Duration}; null if malformed. */
    static Duration parseDuration(String token) {
        String t = token.trim();
        long millis;
        try {
            if (t.endsWith("ms")) {
                millis = Long.parseLong(t.substring(0, t.length() - 2).trim());
            } else if (t.endsWith("s")) {
                millis = Long.parseLong(t.substring(0, t.length() - 1).trim()) * 1_000L;
            } else {
                millis = Long.parseLong(t);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return millis > 0 ? Duration.ofMillis(millis) : null;
    }
}
