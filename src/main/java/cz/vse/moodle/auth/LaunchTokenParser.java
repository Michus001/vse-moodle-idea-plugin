package cz.vse.moodle.auth;

import cz.vse.moodle.api.SiteUrls;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and verifies the {@code <scheme>://token=<base64>} redirect produced by
 * {@code admin/tool/mobile/launch.php}.
 * <p>
 * The decoded payload is {@code siteHash:::token[:::privateToken]}, where
 * {@code siteHash = md5($CFG->wwwroot . passport)}.
 */
public final class LaunchTokenParser {
    // Any custom scheme: the site may force its own scheme regardless of the one we asked for.
    private static final Pattern LAUNCH_REDIRECT = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://+token=(.*)$", Pattern.DOTALL);
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https", "about", "data", "blob", "file", "javascript");

    private LaunchTokenParser() {
    }

    /** True for URLs that look like the token redirect (they must not be loaded by the browser). */
    public static boolean isLaunchRedirect(@NotNull String url) {
        Matcher m = LAUNCH_REDIRECT.matcher(url.trim());
        return m.matches() && !WEB_SCHEMES.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * @param url      the intercepted redirect URL
     * @param passport the passport sent to launch.php
     * @param siteUrls site URLs to verify the hash against (each is tried with and without a trailing slash)
     */
    public static @NotNull MoodleToken parse(@NotNull String url, @NotNull String passport, @NotNull Collection<String> siteUrls)
        throws InvalidLaunchTokenException {
        Matcher m = LAUNCH_REDIRECT.matcher(url.trim());
        if (!m.matches() || !isLaunchRedirect(url)) {
            throw new InvalidLaunchTokenException("Adresa přesměrování neobsahuje token.");
        }
        String encoded = m.group(2);
        int end = indexOfAny(encoded, '?', '#', '&');
        if (end >= 0) {
            encoded = encoded.substring(0, end);
        }
        encoded = percentDecode(encoded).replaceAll("[\\s/]+$", "").strip();
        if (encoded.isEmpty()) {
            throw new InvalidLaunchTokenException("Token v přesměrování je prázdný.");
        }

        String payload = new String(decodeBase64(encoded), StandardCharsets.UTF_8);
        String[] parts = payload.split(":::", -1);
        if (parts.length < 2 || parts.length > 3 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new InvalidLaunchTokenException("Token má neočekávaný formát.");
        }
        String siteHash = parts[0].trim().toLowerCase(Locale.ROOT);
        if (!matchesAnySite(siteHash, passport, siteUrls)) {
            throw new InvalidLaunchTokenException(
                "Token nebyl vydán pro tento Moodle nebo tento pokus o přihlášení (nesouhlasí kontrolní součet).");
        }
        String privateToken = parts.length == 3 && !parts[2].isBlank() ? parts[2].trim() : null;
        return new MoodleToken(parts[1].trim(), privateToken);
    }

    static @NotNull String siteHash(@NotNull String siteUrl, @NotNull String passport) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest((siteUrl + passport).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static boolean matchesAnySite(@NotNull String siteHash, @NotNull String passport, @NotNull Collection<String> siteUrls) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String siteUrl : siteUrls) {
            if (siteUrl == null || siteUrl.isBlank()) continue;
            String normalized = SiteUrls.normalize(siteUrl);
            candidates.add(normalized);
            candidates.add(normalized + "/");
        }
        byte[] actual = siteHash.getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String candidate : candidates) {
            byte[] expected = siteHash(candidate, passport).getBytes(StandardCharsets.US_ASCII);
            match |= MessageDigest.isEqual(expected, actual);
        }
        return match;
    }

    private static byte[] decodeBase64(@NotNull String encoded) throws InvalidLaunchTokenException {
        String padded = encoded;
        if (padded.length() % 4 != 0) {
            padded = padded + "=".repeat(4 - padded.length() % 4);
        }
        try {
            return java.util.Base64.getDecoder().decode(padded);
        }
        catch (IllegalArgumentException e) {
            try {
                return java.util.Base64.getUrlDecoder().decode(padded);
            }
            catch (IllegalArgumentException e2) {
                throw new InvalidLaunchTokenException("Token v přesměrování není platný Base64.");
            }
        }
    }

    /** Decodes %XX escapes only; '+' is kept because it is a valid Base64 character. */
    private static @NotNull String percentDecode(@NotNull String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() &&isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            }
            else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static int indexOfAny(@NotNull String s, char... chars) {
        int result = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (result < 0 || i < result)) {
                result = i;
            }
        }
        return result;
    }
}
