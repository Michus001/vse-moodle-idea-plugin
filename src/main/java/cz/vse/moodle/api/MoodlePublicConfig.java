package cz.vse.moodle.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Subset of {@code tool_mobile_get_public_config} needed for the SSO login.
 *
 * @param wwwroot           site root as Moodle knows it ({@code $CFG->wwwroot}), may be null
 * @param launchUrl         URL of {@code admin/tool/mobile/launch.php}
 * @param urlScheme         URL scheme Moodle redirects to with the token
 * @param identityProviders SSO providers offered on the login page
 */
public record MoodlePublicConfig(
    @Nullable String wwwroot,
    @NotNull String launchUrl,
    @NotNull String urlScheme,
    @NotNull List<IdentityProvider> identityProviders
) {
    public static final String DEFAULT_URL_SCHEME = "moodlemobile";

    public record IdentityProvider(@NotNull String name, @NotNull String url) {
    }

    /**
     * Parses the response of {@code lib/ajax/service-nologin.php} for a single
     * {@code tool_mobile_get_public_config} call.
     */
    public static @NotNull MoodlePublicConfig parseAjaxResponse(@NotNull String body, @NotNull String siteUrl)
        throws MoodleException {
        JsonElement json = MoodleResponses.parseJson(body);
        // A request-level failure is returned as a plain error object instead of an array.
        MoodleResponses.throwIfError(json);
        if (!json.isJsonArray() || json.getAsJsonArray().isEmpty() || !json.getAsJsonArray().get(0).isJsonObject()) {
            throw new MoodleException("invalidresponse", "Neočekávaná odpověď na tool_mobile_get_public_config.");
        }
        JsonObject result = json.getAsJsonArray().get(0).getAsJsonObject();
        JsonElement error = result.get("error");
        if (error != null && error.isJsonPrimitive() && error.getAsBoolean()) {
            JsonElement exception = result.get("exception");
            MoodleException e = exception != null ? MoodleResponses.toError(exception) : null;
            throw e != null ? e : new MoodleException("unknown", "Moodle odmítl vrátit veřejnou konfiguraci.");
        }
        JsonElement data = result.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new MoodleException("invalidresponse", "Odpověď tool_mobile_get_public_config neobsahuje data.");
        }
        return fromData(data.getAsJsonObject(), siteUrl);
    }

    static @NotNull MoodlePublicConfig fromData(@NotNull JsonObject data, @NotNull String siteUrl) throws MoodleException {
        if (MoodleResponses.getInt(data, "enablewebservices", 0) != 1
            || MoodleResponses.getInt(data, "enablemobilewebservice", 0) != 1) {
            throw new MoodleException("mobileservicedisabled",
                "Na tomto Moodle nejsou zapnuté mobilní webové služby, přihlášení přes SSO proto není možné. "
                + "Zkuste vložit token ručně.");
        }
        String launchUrl = MoodleResponses.getString(data, "launchurl");
        if (launchUrl == null || launchUrl.isBlank()) {
            launchUrl = siteUrl + "/admin/tool/mobile/launch.php";
        }
        // Not part of the public config in current Moodle versions; used if a future version exposes it.
        String scheme = MoodleResponses.getString(data, "tool_mobile_forcedurlscheme");
        if (scheme == null || scheme.isBlank()) {
            scheme = DEFAULT_URL_SCHEME;
        }
        List<IdentityProvider> providers = new ArrayList<>();
        JsonElement ips = data.get("identityproviders");
        if (ips != null && ips.isJsonArray()) {
            JsonArray array = ips.getAsJsonArray();
            for (JsonElement ip : array) {
                if (!ip.isJsonObject()) continue;
                String name = MoodleResponses.getString(ip.getAsJsonObject(), "name");
                String url = MoodleResponses.getString(ip.getAsJsonObject(), "url");
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    providers.add(new IdentityProvider(name != null ? name : url, url));
                }
            }
        }
        return new MoodlePublicConfig(MoodleResponses.getString(data, "wwwroot"), launchUrl, scheme, List.copyOf(providers));
    }
}
