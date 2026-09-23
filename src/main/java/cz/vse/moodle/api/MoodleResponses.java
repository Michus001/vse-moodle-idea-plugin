package cz.vse.moodle.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parsing helpers for Moodle web service responses.
 * <p>
 * Moodle reports errors with HTTP 200 and a JSON body such as
 * {@code {"exception":"moodle_exception","errorcode":"invalidtoken","message":"..."}}.
 */
public final class MoodleResponses {
    private MoodleResponses() {
    }

    /** Parses a REST response body and throws if it is a Moodle error object. */
    public static @NotNull JsonElement parseRest(@NotNull String body) throws MoodleException {
        JsonElement json = parseJson(body);
        throwIfError(json);
        return json;
    }

    public static @NotNull JsonElement parseJson(@NotNull String body) throws MoodleException {
        try {
            JsonElement json = JsonParser.parseString(body);
            if (json == null || json.isJsonNull()) {
                throw new MoodleException("invalidresponse", "Server Moodle vrátil prázdnou odpověď.");
            }
            return json;
        }
        catch (JsonParseException e) {
            throw new MoodleException("invalidresponse", "Server Moodle vrátil neplatnou odpověď (očekáván JSON).");
        }
    }

    /** Throws {@link MoodleException} when {@code json} is a Moodle error object. */
    public static void throwIfError(@NotNull JsonElement json) throws MoodleException {
        MoodleException error = toError(json);
        if (error != null) {
            throw error;
        }
    }

    /** Returns the error described by {@code json}, or null if it is a regular response. */
    public static @Nullable MoodleException toError(@NotNull JsonElement json) {
        if (!json.isJsonObject()) {
            return null;
        }
        JsonObject obj = json.getAsJsonObject();
        boolean isError = obj.has("exception") || (obj.has("errorcode") && obj.has("message"));
        if (!isError) {
            return null;
        }
        String errorCode = getString(obj, "errorcode");
        String message = getString(obj, "message");
        String exception = getString(obj, "exception");
        return new MoodleException(
            errorCode != null ? errorCode : "unknown",
            message != null ? message : "Moodle vrátil chybu.",
            exception);
    }

    static @Nullable String getString(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static int getInt(@NotNull JsonObject obj, @NotNull String key, int defaultValue) {
        JsonElement value = obj.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return value.getAsInt();
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
