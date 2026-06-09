package jcma.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Global, subcommand-independent argument parsing — currently just the git-style {@code -C <dir>}
 * working-directory override. Stripped before dispatch so it may appear anywhere on the line
 * ({@code jcma -C ~/proj refs Foo} or {@code jcma refs Foo -C ~/proj}); the remaining tokens carry
 * the subcommand and its own args. When {@code -C} is absent the working directory defaults to the
 * process CWD, so the common case is "act on the project I'm sitting in".
 */
final class GlobalArgs {

    private GlobalArgs() {}

    /** The working directory, the args with {@code -C <dir>} removed (subcommand at index 0), or an error. */
    record Parsed(Path workingDir, String[] rest, String error) {}

    static Parsed parse(String[] args) {
        Path workingDir = Path.of("").toAbsolutePath(); // process CWD
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-C")) {
                if (i + 1 >= args.length) {
                    return new Parsed(null, null, "-C requires a directory (e.g. -C ~/project)");
                }
                workingDir = Path.of(args[++i]).toAbsolutePath();
            } else {
                rest.add(args[i]);
            }
        }
        return new Parsed(workingDir, rest.toArray(new String[0]), null);
    }
}
