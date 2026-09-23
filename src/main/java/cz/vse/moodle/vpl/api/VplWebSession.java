package cz.vse.moodle.vpl.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cz.vse.moodle.api.AutologinKey;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.api.MoodleHttp;
import cz.vse.moodle.api.MoodleResponses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moodle web session (cookies) for VPL's editor endpoint {@code mod/vpl/forms/edit.json.php}.
 * <p>
 * VPL's own web service ({@code mod_vpl_*}) isn't part of the mobile app service, so the SSO token can't call it.
 * The editor endpoint only needs a logged-in session, which the plugin gets the same way the mobile app opens
 * pages in a browser: {@code tool_mobile_get_autologin_key} + {@code admin/tool/mobile/autologin.php}.
 * All methods block.
 */
public final class VplWebSession {
    private static final Pattern SESSKEY = Pattern.compile("\"sesskey\"\\s*:\\s*\"([A-Za-z0-9]+)\"");
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(90);

    private final String siteUrl;
    private final long userId;
    private final HttpClient http;
    private final String sesskey;

    private VplWebSession(@NotNull String siteUrl, long userId, @NotNull HttpClient http, @NotNull String sesskey) {
        this.siteUrl = siteUrl;
        this.userId = userId;
        this.http = http;
        this.sesskey = sesskey;
    }

    /**
     * Logs in via an auto-login key. Moodle hands out a key at most every 6 minutes, so callers should reuse
     * the session while {@link #isAlive()}.
     */
    public static @NotNull VplWebSession open(@NotNull MoodleClient client, long userId, @NotNull String privateToken)
        throws IOException, MoodleException {
        AutologinKey key = client.getAutologinKey(privateToken);
        HttpClient http = MoodleHttp.newCookieClient(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        String site = client.getSiteUrl();
        String url = key.autologinUrl()
            + "?userid=" + userId
            + "&key=" + encode(key.key())
            + "&urltogo=" + encode(site + "/user/preferences.php");
        HttpResponse<String> response = MoodleHttp.get(http, url);
        String sesskey = parseSesskey(response.body());
        if (sesskey == null || response.uri().getPath().contains("/login/")) {
            throw new MoodleException("autologinfailed", "Moodle nepřijal automatické přihlášení do webu. Zkuste se odhlásit a znovu přihlásit.");
        }
        return new VplWebSession(site, userId, http, sesskey);
    }

    public @NotNull String getSiteUrl() {
        return siteUrl;
    }

    public long getUserId() {
        return userId;
    }

    /** Checks the session with {@code core_session_time_remaining}; false when Moodle logged it out. */
    public boolean isAlive() throws IOException {
        String body = MoodleHttp.postJson(http,
            siteUrl + "/lib/ajax/service.php?sesskey=" + encode(sesskey) + "&info=core_session_time_remaining",
            "[{\"index\":0,\"methodname\":\"core_session_time_remaining\",\"args\":{}}]");
        try {
            return isAliveResponse(MoodleResponses.parseJson(body), userId);
        }
        catch (MoodleException e) {
            return false;
        }
    }

    /**
     * Calls an action of the VPL editor for the activity {@code cmid}.
     *
     * @throws MoodleException with error code {@code vplerror} when VPL refuses the action
     */
    @NotNull JsonObject action(long cmid, @NotNull String action, @NotNull JsonObject data) throws IOException, MoodleException {
        String url = siteUrl + "/mod/vpl/forms/edit.json.php?id=" + cmid + "&action=" + encode(action);
        return VplJson.parseEnvelope(MoodleHttp.postJson(http, url, data.toString(), ACTION_TIMEOUT));
    }

    static @Nullable String parseSesskey(@NotNull String html) {
        Matcher matcher = SESSKEY.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    static boolean isAliveResponse(@NotNull JsonElement json, long userId) {
        JsonElement first = json.isJsonArray() && !json.getAsJsonArray().isEmpty() ? json.getAsJsonArray().get(0) : json;
        if (!first.isJsonObject() || MoodleResponses.getBoolean(first.getAsJsonObject(), "error", true)) {
            return false;
        }
        JsonElement data = first.getAsJsonObject().get("data");
        if (data == null || !data.isJsonObject()) {
            return false;
        }
        JsonObject info = data.getAsJsonObject();
        return MoodleResponses.getLong(info, "userid", -1) == userId && MoodleResponses.getLong(info, "timeremaining", 0) > 0;
    }

    private static @NotNull String encode(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "VplWebSession{" + siteUrl + ", user " + userId + "}";
    }
}
