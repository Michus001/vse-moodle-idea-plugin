package cz.vse.moodle.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns names from Moodle (course, activity…) into ASCII-only directory and file names.
 * <p>
 * IntelliJ passes the project path to the JPS build process on the command line in the system ANSI code page on Windows,
 * so any character outside that code page (e.g. Czech letters on a cp1251 system) becomes {@code ?} and the build fails
 * with {@code InvalidPathException}. Local paths created by the plugin therefore use only {@code [A-Za-z0-9._-]}.
 * Display names in the UI keep their diacritics; only paths are sanitized.
 */
public final class PathNames {
    public static final int MAX_LENGTH = 60;
    private static final String DEFAULT_FALLBACK = "slozka";

    /** Letters that NFD does not decompose into a base letter and a combining mark. */
    private static final Map<Character, String> SPECIAL_LETTERS = Map.ofEntries(
        Map.entry('ł', "l"), Map.entry('Ł', "L"), Map.entry('ß', "ss"), Map.entry('ø', "o"), Map.entry('Ø', "O"),
        Map.entry('đ', "d"), Map.entry('Đ', "D"), Map.entry('æ', "ae"), Map.entry('Æ', "AE"), Map.entry('œ', "oe"),
        Map.entry('Œ', "OE"), Map.entry('þ', "th"), Map.entry('Þ', "Th"), Map.entry('ð', "d"), Map.entry('Ð', "D"),
        Map.entry('ı', "i"), Map.entry('ħ', "h"), Map.entry('Ħ', "H"));
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern UNDERSCORES = Pattern.compile("_{2,}");
    private static final Pattern EDGES = Pattern.compile("^[_. ]+|[_. ]+$");
    private static final Pattern RESERVED = Pattern.compile("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]");

    private PathNames() {
    }

    /** Same as {@link #safeDirName(String, String)} with a generic fallback. */
    public static @NotNull String safeDirName(@Nullable String name) {
        return safeDirName(name, DEFAULT_FALLBACK);
    }

    /**
     * An ASCII-only name safe on all platforms, e.g. {@code "Žluťoučký kůň (1)"} → {@code "Zlutoucky_kun_1"}.
     * At most {@value #MAX_LENGTH} characters, never a reserved Windows device name, idempotent.
     *
     * @param fallback used when nothing is left of {@code name}, e.g. {@code "uloha-601"}; sanitized as well
     */
    public static @NotNull String safeDirName(@Nullable String name, @NotNull String fallback) {
        String safe = sanitize(name);
        if (safe.isEmpty()) safe = sanitize(fallback);
        return safe.isEmpty() ? DEFAULT_FALLBACK : safe;
    }

    /**
     * {@link #safeDirName(String, String)} followed by {@code suffix} (e.g. {@code "_601"}), shortening the name,
     * not the suffix, to stay within {@value #MAX_LENGTH} characters.
     */
    public static @NotNull String safeDirName(@Nullable String name, @NotNull String fallback, @NotNull String suffix) {
        String safeSuffix = UNSAFE.matcher(suffix).replaceAll("_");
        String base = safeDirName(name, fallback);
        int room = Math.max(1, MAX_LENGTH - safeSuffix.length());
        if (base.length() > room) {
            base = safeDirName(base.substring(0, room), fallback);
        }
        return safeDirName(base + safeSuffix, fallback);
    }

    /** True if {@code name} contains only printable ASCII, i.e. survives any Windows ANSI code page. */
    public static boolean isAscii(@NotNull String name) {
        return name.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    private static @NotNull String sanitize(@Nullable String name) {
        if (name == null) return "";
        StringBuilder replaced = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            String special = SPECIAL_LETTERS.get(c);
            replaced.append(special != null ? special : String.valueOf(c));
        }
        String safe = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        safe = MARKS.matcher(safe).replaceAll("");
        safe = UNSAFE.matcher(safe).replaceAll("_");
        safe = UNDERSCORES.matcher(safe).replaceAll("_");
        safe = trim(safe);
        if (safe.length() > MAX_LENGTH) {
            safe = trim(safe.substring(0, MAX_LENGTH));
        }
        return avoidReserved(safe);
    }

    /** "CON" → "CON_", "com1.txt" → "com1_.txt": Windows treats these as devices regardless of the extension. */
    private static @NotNull String avoidReserved(@NotNull String name) {
        int dot = name.indexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        if (!RESERVED.matcher(stem.toUpperCase(Locale.ROOT)).matches()) {
            return name;
        }
        String result = stem + "_" + name.substring(stem.length());
        // Only reachable for "CON.<56+ chars>"; cutting the tail keeps the "_" right after the stem.
        return result.length() > MAX_LENGTH ? trim(result.substring(0, MAX_LENGTH)) : result;
    }

    private static @NotNull String trim(@NotNull String name) {
        return EDGES.matcher(name).replaceAll("");
    }
}
