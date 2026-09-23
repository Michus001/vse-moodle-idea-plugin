package cz.vse.moodle.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Full name of a course, or null when the user can't see it. */
    public @Nullable String getCourseName(long courseId) throws IOException, MoodleException {
        JsonElement json = call("core_course_get_courses_by_field", Map.of(
            "field", "id",
            "value", Long.toString(courseId)));
        if (!json.isJsonObject() || !(json.getAsJsonObject().get("courses") instanceof JsonArray courses)
            || courses.isEmpty() || !courses.get(0).isJsonObject()) {
            return null;
        }
        return MoodleResponses.getString(courses.get(0).getAsJsonObject(), "fullname");
    }

    /** Activities of one module type (e.g. {@code "vpl"}) in a course, in course order. */
    public @NotNull List<CourseModule> getCourseModules(long courseId, @NotNull String modName) throws IOException, MoodleException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("courseid", Long.toString(courseId));
        params.put("options[0][name]", "modname");
        params.put("options[0][value]", modName);
        params.put("options[1][name]", "excludecontents");
        params.put("options[1][value]", "1");
        return parseCourseModules(call("core_course_get_contents", params), modName);
    }

    /**
     * Requests a one-time auto-login key (what the mobile app uses to open pages in a browser).
     * Moodle allows this once every 6 minutes per user and never for site administrators.
     */
    public @NotNull AutologinKey getAutologinKey(@NotNull String privateToken) throws IOException, MoodleException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("wstoken", token);
        form.put("wsfunction", "tool_mobile_get_autologin_key");
        form.put("moodlewsrestformat", "json");
        form.put("privatetoken", privateToken);
        String body = MoodleHttp.postForm(http, siteUrl + "/webservice/rest/server.php", form, MoodleHttp.MOBILE_APP_USER_AGENT);
        JsonElement json = MoodleResponses.parseRest(body);
        String key = json.isJsonObject() ? MoodleResponses.getString(json.getAsJsonObject(), "key") : null;
        String url = json.isJsonObject() ? MoodleResponses.getString(json.getAsJsonObject(), "autologinurl") : null;
        if (key == null || url == null) {
            throw new MoodleException("invalidresponse", "Odpověď tool_mobile_get_autologin_key neobsahuje klíč.");
        }
        return new AutologinKey(key, url);
    }

    static @NotNull List<CourseModule> parseCourseModules(@NotNull JsonElement json, @NotNull String modName) throws MoodleException {
        if (!json.isJsonArray()) {
            throw new MoodleException("invalidresponse", "Neočekávaná odpověď na core_course_get_contents.");
        }
        List<CourseModule> result = new ArrayList<>();
        for (JsonElement sectionElement : json.getAsJsonArray()) {
            if (!sectionElement.isJsonObject()) continue;
            JsonObject section = sectionElement.getAsJsonObject();
            String sectionName = MoodleResponses.getString(section, "name");
            if (!(section.get("modules") instanceof JsonArray modules)) continue;
            for (JsonElement moduleElement : modules) {
                if (!moduleElement.isJsonObject()) continue;
                JsonObject module = moduleElement.getAsJsonObject();
                if (!modName.equals(MoodleResponses.getString(module, "modname"))) continue;
                long id = MoodleResponses.getLong(module, "id", -1);
                String name = MoodleResponses.getString(module, "name");
                if (id < 0 || name == null) continue;
                Instant opens = null;
                Instant due = null;
                if (module.get("dates") instanceof JsonArray dates) {
                    for (JsonElement dateElement : dates) {
                        if (!dateElement.isJsonObject()) continue;
                        JsonObject date = dateElement.getAsJsonObject();
                        long timestamp = MoodleResponses.getLong(date, "timestamp", 0);
                        if (timestamp <= 0) continue;
                        String dataId = MoodleResponses.getString(date, "dataid");
                        if ("duedate".equals(dataId)) {
                            due = Instant.ofEpochSecond(timestamp);
                        }
                        else if ("startdate".equals(dataId) || "allowsubmissionsfromdate".equals(dataId)) {
                            opens = Instant.ofEpochSecond(timestamp);
                        }
                    }
                }
                result.add(new CourseModule(id, name, modName, MoodleResponses.getString(module, "url"),
                    sectionName != null ? sectionName : "",
                    MoodleResponses.getBoolean(module, "uservisible", true),
                    blankToNull(MoodleResponses.getString(module, "availabilityinfo")),
                    blankToNull(MoodleResponses.getString(module, "description")),
                    opens, due));
            }
        }
        return result;
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
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
