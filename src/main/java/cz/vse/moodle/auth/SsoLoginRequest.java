package cz.vse.moodle.auth;

import cz.vse.moodle.api.MoodlePublicConfig;
import cz.vse.moodle.api.SiteUrls;
import org.jetbrains.annotations.NotNull;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One SSO login attempt.
 *
 * @param launchUrl URL of launch.php including service, passport and urlscheme
 * @param loginUrl  where to send the user when launch.php refuses to start the login itself
 *                  (sites with "login type = in the app", such as moodle.vse.cz)
 */
public record SsoLoginRequest(
    @NotNull String siteUrl,
    @NotNull String passport,
    @NotNull String launchUrl,
    @NotNull String loginUrl,
    @NotNull Set<String> hashSiteUrls
) {
    public static final String MOBILE_SERVICE = "moodle_mobile_app";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static @NotNull SsoLoginRequest create(@NotNull String siteUrl, @NotNull MoodlePublicConfig config) {
        String site = SiteUrls.normalize(siteUrl);
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String passport = HexFormat.of().formatHex(bytes);

        String launchUrl = config.launchUrl()
            + (config.launchUrl().contains("?") ? "&" : "?")
            + "service=" + MOBILE_SERVICE
            + "&passport=" + passport
            + "&urlscheme=" + URLEncoder.encode(config.urlScheme(), StandardCharsets.UTF_8);

        // With a single SSO provider go straight to it; otherwise let the user choose on the login page.
        List<MoodlePublicConfig.IdentityProvider> providers = config.identityProviders();
        String loginUrl = providers.size() == 1 ? providers.getFirst().url() : site + "/login/index.php";

        Set<String> hashSiteUrls = new LinkedHashSet<>();
        hashSiteUrls.add(site);
        if (config.wwwroot() != null) {
            hashSiteUrls.add(config.wwwroot());
        }
        return new SsoLoginRequest(site, passport, launchUrl, loginUrl, Set.copyOf(hashSiteUrls));
    }

    public boolean isLaunchPage(@NotNull String url) {
        int query = launchUrl.indexOf('?');
        return url.startsWith(query >= 0 ? launchUrl.substring(0, query) : launchUrl);
    }
}
