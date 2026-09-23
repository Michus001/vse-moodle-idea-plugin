package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

public final class SiteUrls {
    private SiteUrls() {
    }

    /** Trims whitespace and trailing slashes: {@code "https://moodle.vse.cz/ "} -> {@code "https://moodle.vse.cz"}. */
    public static @NotNull String normalize(@NotNull String siteUrl) {
        String url = siteUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Returns an error message for an unusable site URL, or null if it is fine. */
    public static @Nullable String validate(@NotNull String siteUrl) {
        String url = normalize(siteUrl);
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                return "Adresa musí začínat https:// (nebo http://).";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "Adresa neobsahuje název serveru.";
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                return "Adresa nesmí obsahovat ? ani #.";
            }
            return null;
        }
        catch (URISyntaxException e) {
            return "Neplatná adresa: " + e.getReason();
        }
    }
}
