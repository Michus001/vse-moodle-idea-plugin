package cz.vse.moodle.vpl;

import cz.vse.moodle.vpl.api.VplFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Appends comments identifying the submitter and the other team members to the end of each submitted source file:
 * <pre>
 * // Odevzdano pres IntelliJ (Moodle VSE): jan.novak@vse.cz
 * // Clen tymu (Moodle VSE): xdvop02@vse.cz
 * </pre>
 * <p>
 * The stamp is pure ASCII: VPL jail servers may compile with {@code US-ASCII}, and javac then rejects any other
 * character, even inside a comment.
 * <p>
 * Only the copy sent to Moodle is stamped; downloaded files are un-stamped, so the stamp never accumulates.
 * Files without a known comment syntax (data files read by tests, binaries) are left untouched.
 */
public final class VplSubmitterStamp {
    static final String MARKER = "Odevzdano pres IntelliJ (Moodle VSE):";
    /** One line per other team member, below the submitter. */
    static final String TEAM_MARKER = "Clen tymu (Moodle VSE):";
    /** Non-ASCII marker of plugin 0.1.0; still stripped so old stamps don't stay in re-submitted files. */
    static final String LEGACY_MARKER = "Odevzdal(a) přes IntelliJ (Moodle VŠE):";

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

    /**
     * Returns the files with the stamp appended where possible.
     *
     * @param submitter   e-mail (or username) of the logged-in student
     * @param teamMembers other team members; each gets its own line below the submitter
     */
    public static @NotNull List<VplFile> apply(@NotNull List<VplFile> files, @NotNull String submitter,
                                               @NotNull List<String> teamMembers) {
        return files.stream().map(file -> apply(file, submitter, teamMembers)).toList();
    }

    public static @NotNull List<VplFile> strip(@NotNull List<VplFile> files) {
        return files.stream().map(VplSubmitterStamp::strip).toList();
    }

    static @NotNull VplFile apply(@NotNull VplFile file, @NotNull String submitter) {
        return apply(file, submitter, List.of());
    }

    static @NotNull VplFile apply(@NotNull VplFile file, @NotNull String submitter, @NotNull List<String> teamMembers) {
        Syntax syntax = syntaxOf(file.name());
        if (syntax == null || file.isBinary()) return file;
        String text = stripText(new String(file.data(), StandardCharsets.UTF_8));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder stamped = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) stamped.append(newline);
        String submitterValue = safe(submitter);
        appendLine(stamped, syntax, MARKER, submitterValue, newline);
        for (String member : teamMembers) {
            String value = safe(member);
            if (!value.isBlank() && !value.equalsIgnoreCase(submitterValue)) {
                appendLine(stamped, syntax, TEAM_MARKER, value, newline);
            }
        }
        return new VplFile(file.name(), stamped.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendLine(@NotNull StringBuilder out, @NotNull Syntax syntax, @NotNull String marker,
                                   @NotNull String value, @NotNull String newline) {
        out.append(syntax.prefix()).append(marker).append(' ').append(value).append(syntax.suffix()).append(newline);
    }

    /** ASCII, one line, and never able to close the comment early. */
    private static @NotNull String safe(@NotNull String value) {
        return toAscii(value).replaceAll("[\\r\\n]", " ").replace("*/", "* /").replace("-->", "- ->").trim();
    }

    static @NotNull VplFile strip(@NotNull VplFile file) {
        if (syntaxOf(file.name()) == null || file.isBinary()) return file;
        String text = new String(file.data(), StandardCharsets.UTF_8);
        String stripped = stripText(text);
        return stripped.equals(text) ? file : new VplFile(file.name(), stripped.getBytes(StandardCharsets.UTF_8));
    }

    /** "Nováková" -> "Novakova"; any other non-ASCII character becomes '?'. */
    static @NotNull String toAscii(@NotNull String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** Removes trailing stamp lines (any comment syntax, current or legacy marker), keeping the rest byte for byte. */
    private static @NotNull String stripText(@NotNull String text) {
        Pattern trailingStamp = Pattern.compile("(?:^|(?<=\\n))[^\\n]*(?:" + Pattern.quote(MARKER) + "|"
            + Pattern.quote(TEAM_MARKER) + "|" + Pattern.quote(LEGACY_MARKER) + ")[^\\n]*(?:\\r?\\n)?\\z");
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
