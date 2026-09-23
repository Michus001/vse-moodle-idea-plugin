package cz.vse.moodle.vpl;

import cz.vse.moodle.vpl.api.VplFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Appends a comment identifying the submitter to the end of each submitted source file
 * (e.g. {@code // Odevzdal(a) přes IntelliJ (Moodle VŠE): jan.novak@vse.cz}).
 * <p>
 * Only the copy sent to Moodle is stamped; downloaded files are un-stamped, so the stamp never accumulates.
 * Files without a known comment syntax (data files read by tests, binaries) are left untouched.
 */
public final class VplSubmitterStamp {
    static final String MARKER = "Odevzdal(a) přes IntelliJ (Moodle VŠE):";

    private record Syntax(@NotNull String prefix, @NotNull String suffix) {
    }

    private static final Syntax SLASHES = new Syntax("// ", "");
    private static final Syntax HASH = new Syntax("# ", "");
    private static final Syntax DASHES = new Syntax("-- ", "");
    private static final Syntax PERCENT = new Syntax("% ", "");
    private static final Syntax BLOCK = new Syntax("/* ", " */");
    private static final Syntax XML = new Syntax("<!-- ", " -->");

    private static final Map<String, Syntax> BY_EXTENSION = Map.ofEntries(
        Map.entry("java", SLASHES), Map.entry("kt", SLASHES), Map.entry("kts", SLASHES), Map.entry("scala", SLASHES),
        Map.entry("groovy", SLASHES), Map.entry("c", SLASHES), Map.entry("h", SLASHES), Map.entry("cpp", SLASHES),
        Map.entry("cc", SLASHES), Map.entry("cxx", SLASHES), Map.entry("hpp", SLASHES), Map.entry("cs", SLASHES),
        Map.entry("js", SLASHES), Map.entry("mjs", SLASHES), Map.entry("ts", SLASHES), Map.entry("go", SLASHES),
        Map.entry("rs", SLASHES), Map.entry("swift", SLASHES), Map.entry("dart", SLASHES), Map.entry("php", SLASHES),
        Map.entry("py", HASH), Map.entry("rb", HASH), Map.entry("sh", HASH), Map.entry("r", HASH), Map.entry("pl", HASH),
        Map.entry("jl", HASH),
        Map.entry("sql", DASHES), Map.entry("hs", DASHES), Map.entry("lua", DASHES), Map.entry("ada", DASHES),
        Map.entry("m", PERCENT), Map.entry("pro", PERCENT),
        Map.entry("css", BLOCK),
        Map.entry("html", XML), Map.entry("htm", XML), Map.entry("xml", XML));

    private VplSubmitterStamp() {
    }

    /** Returns the files with the stamp appended where possible; {@code submitter} is the e-mail (or username). */
    public static @NotNull List<VplFile> apply(@NotNull List<VplFile> files, @NotNull String submitter) {
        return files.stream().map(file -> apply(file, submitter)).toList();
    }

    public static @NotNull List<VplFile> strip(@NotNull List<VplFile> files) {
        return files.stream().map(VplSubmitterStamp::strip).toList();
    }

    static @NotNull VplFile apply(@NotNull VplFile file, @NotNull String submitter) {
        Syntax syntax = syntaxOf(file.name());
        if (syntax == null || file.isBinary()) return file;
        String text = stripText(new String(file.data(), StandardCharsets.UTF_8));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder stamped = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) stamped.append(newline);
        // Keep the comment on one line and never let the value close the comment early.
        String safe = submitter.replaceAll("[\\r\\n]", " ").replace("*/", "* /").replace("-->", "- ->");
        stamped.append(syntax.prefix()).append(MARKER).append(' ').append(safe).append(syntax.suffix()).append(newline);
        return new VplFile(file.name(), stamped.toString().getBytes(StandardCharsets.UTF_8));
    }

    static @NotNull VplFile strip(@NotNull VplFile file) {
        if (syntaxOf(file.name()) == null || file.isBinary()) return file;
        String text = new String(file.data(), StandardCharsets.UTF_8);
        String stripped = stripText(text);
        return stripped.equals(text) ? file : new VplFile(file.name(), stripped.getBytes(StandardCharsets.UTF_8));
    }

    /** Removes trailing stamp lines (any comment syntax), keeping the rest byte for byte. */
    private static @NotNull String stripText(@NotNull String text) {
        Pattern trailingStamp = Pattern.compile("(?:^|(?<=\\n))[^\\n]*" + Pattern.quote(MARKER) + "[^\\n]*(?:\\r?\\n)?\\z");
        String result = text;
        while (true) {
            String next = trailingStamp.matcher(result).replaceFirst("");
            if (next.equals(result)) return result;
            result = next;
        }
    }

    private static @Nullable Syntax syntaxOf(@NotNull String name) {
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot <= slash + 1) return null;
        return BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
