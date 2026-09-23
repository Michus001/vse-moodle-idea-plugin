package cz.vse.moodle.vpl.api;

import com.google.gson.JsonArray;
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
import java.net.HttpCookie;
import java.net.URI;
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
    private final CookieManager cookies;
    private final HttpClient http;
    private final String sesskey;

    private VplWebSession(@NotNull String siteUrl, long userId, @NotNull CookieManager cookies, @NotNull String sesskey) {
        this.siteUrl = siteUrl;
        this.userId = userId;
        this.cookies = cookies;
        this.http = MoodleHttp.newCookieClient(cookies);
        this.sesskey = sesskey;
    }

    /**
     * Logs in via an auto-login key. Moodle hands out a key at most every 6 minutes, so callers should reuse
     * the session while {@link #isAlive()}.
     */
    public static @NotNull VplWebSession open(@NotNull MoodleClient client, long userId, @NotNull String privateToken)
        throws IOException, MoodleException {
        AutologinKey key = client.getAutologinKey(privateToken);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = MoodleHttp.newCookieClient(cookies);
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
        return new VplWebSession(site, userId, cookies, sesskey);
    }

    /**
     * Serializes the session (cookies + sesskey) so it survives an IDE restart; without that every restart
     * would need a new auto-login key, which Moodle allows only every 6 minutes. The result is a secret.
     */
    public @NotNull String serialize() {
        JsonObject json = new JsonObject();
        json.addProperty("siteUrl", siteUrl);
        json.addProperty("userId", userId);
        json.addProperty("sesskey", sesskey);
        JsonArray list = new JsonArray();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookie.hasExpired()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("name", cookie.getName());
            item.addProperty("value", cookie.getValue());
            item.addProperty("path", cookie.getPath() != null ? cookie.getPath() : "/");
            list.add(item);
        }
        json.add("cookies", list);
        return json.toString();
    }

    /** Recreates a session from {@link #serialize()}; null when the data is unusable or for another site/user. */
    public static @Nullable VplWebSession restore(@NotNull String serialized, @NotNull String siteUrl, long userId) {
        try {
            JsonElement parsed = MoodleResponses.parseJson(serialized);
            if (!parsed.isJsonObject()) return null;
            JsonObject json = parsed.getAsJsonObject();
            String sesskey = MoodleResponses.getString(json, "sesskey");
            if (!siteUrl.equals(MoodleResponses.getString(json, "siteUrl"))
                || MoodleResponses.getLong(json, "userId", -1) != userId || sesskey == null
                || !(json.get("cookies") instanceof JsonArray list) || list.isEmpty()) {
                return null;
            }
            URI site = URI.create(siteUrl + "/");
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String name = MoodleResponses.getString(item, "name");
                String value = MoodleResponses.getString(item, "value");
                if (name == null || value == null) continue;
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setPath(MoodleResponses.getString(item, "path"));
                cookie.setDomain(site.getHost());
                cookie.setVersion(0);
                cookie.setSecure("https".equals(site.getScheme()));
                cookies.getCookieStore().add(site, cookie);
            }
            return new VplWebSession(siteUrl, userId, cookies, sesskey);
        }
        catch (MoodleException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Cookie header values the session sends to {@code uri}. */
    @NotNull java.util.List<String> cookieHeaderFor(@NotNull URI uri) throws IOException {
        return cookies.get(uri, java.util.Map.of()).getOrDefault("Cookie", java.util.List.of());
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
