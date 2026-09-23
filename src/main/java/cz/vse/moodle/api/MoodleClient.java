package cz.vse.moodle.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated client for the Moodle REST web service ({@code webservice/rest/server.php}).
 * <p>
 * All methods block; call them from a background thread. Add new web service wrappers here
 * (e.g. course contents and file downloads) on top of {@link #call(String, Map)}.
 */
public final class MoodleClient {
    private final String siteUrl;
    private final String token;
    private final HttpClient http;

    public MoodleClient(@NotNull String siteUrl, @NotNull String token) {
        this(siteUrl, token, MoodleHttp.defaultClient());
    }

    public MoodleClient(@NotNull String siteUrl, @NotNull String token, @NotNull HttpClient http) {
        this.siteUrl = SiteUrls.normalize(siteUrl);
        this.token = token;
        this.http = http;
    }

    public @NotNull String getSiteUrl() {
        return siteUrl;
    }

    /**
     * Calls a web service function. Array parameters use Moodle's form notation,
     * e.g. {@code values[0]=42}.
     *
     * @throws MoodleException when Moodle returns an error object (invalid token, missing capability, ...)
     * @throws IOException     on network or HTTP-level failures
     */
    public @NotNull JsonElement call(@NotNull String function, @NotNull Map<String, String> params)
        throws IOException, MoodleException {
        Map<String, String> form = new LinkedHashMap<>();
        // Sent in the POST body rather than the query string so the token doesn't end up in access logs.
        form.put("wstoken", token);
        form.put("wsfunction", function);
        form.put("moodlewsrestformat", "json");
        form.putAll(params);
        String body = MoodleHttp.postForm(http, siteUrl + "/webservice/rest/server.php", form);
        return MoodleResponses.parseRest(body);
    }

    public @NotNull SiteInfo getSiteInfo() throws IOException, MoodleException {
        JsonElement json = call("core_webservice_get_site_info", Map.of());
        return parseSiteInfo(json);
    }

    /** Returns the user's e-mail, or null when it isn't visible to the token owner. */
    public @Nullable String getUserEmail(long userId) throws IOException, MoodleException {
        JsonElement json = call("core_user_get_users_by_field", Map.of(
            "field", "id",
            "values[0]", Long.toString(userId)));
        return parseUserEmail(json);
    }

    static @NotNull SiteInfo parseSiteInfo(@NotNull JsonElement json) throws MoodleException {
        if (!json.isJsonObject()) {
            throw new MoodleException("invalidresponse", "Neočekávaná odpověď na core_webservice_get_site_info.");
        }
        JsonObject obj = json.getAsJsonObject();
        JsonElement userId = obj.get("userid");
        String username = MoodleResponses.getString(obj, "username");
        if (userId == null || !userId.isJsonPrimitive() || username == null) {
            throw new MoodleException("invalidresponse", "Odpověď core_webservice_get_site_info neobsahuje uživatele.");
        }
        String fullName = MoodleResponses.getString(obj, "fullname");
        return new SiteInfo(userId.getAsLong(), username, fullName != null ? fullName : username,
            MoodleResponses.getString(obj, "sitename"));
    }

    static @Nullable String parseUserEmail(@NotNull JsonElement json) {
        if (!json.isJsonArray()) {
            return null;
        }
        JsonArray users = json.getAsJsonArray();
        if (users.isEmpty() || !users.get(0).isJsonObject()) {
            return null;
        }
        String email = MoodleResponses.getString(users.get(0).getAsJsonObject(), "email");
        return email == null || email.isBlank() ? null : email;
    }

    @Override
    public String toString() {
        return "MoodleClient{" + siteUrl + "}";
    }
}
