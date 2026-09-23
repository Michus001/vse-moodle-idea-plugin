package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.http.HttpClient;

/** Moodle calls that don't need a token ({@code lib/ajax/service-nologin.php}). */
public final class MoodlePublicApi {
    private MoodlePublicApi() {
    }

    public static @NotNull MoodlePublicConfig getPublicConfig(@NotNull String siteUrl) throws IOException, MoodleException {
        return getPublicConfig(MoodleHttp.defaultClient(), siteUrl);
    }

    public static @NotNull MoodlePublicConfig getPublicConfig(@NotNull HttpClient http, @NotNull String siteUrl)
        throws IOException, MoodleException {
        String site = SiteUrls.normalize(siteUrl);
        String body = MoodleHttp.postJson(http, site + "/lib/ajax/service-nologin.php",
            "[{\"index\":0,\"methodname\":\"tool_mobile_get_public_config\",\"args\":{}}]");
        return MoodlePublicConfig.parseAjaxResponse(body, site);
    }
}
