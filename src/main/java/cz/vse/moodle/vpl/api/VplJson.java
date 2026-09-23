package cz.vse.moodle.vpl.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.api.MoodleResponses;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** JSON (de)serialization for {@code mod/vpl/forms/edit.json.php}, the endpoint of VPL's browser editor. */
final class VplJson {
    /** Error code of {@link MoodleException}s that carry VPL's own error message. */
    static final String VPL_ERROR = "vplerror";

    private VplJson() {
    }

    /**
     * Unwraps {@code {"success":true,"response":{...},"error":""}}.
     *
     * @throws MoodleException with {@link #VPL_ERROR} when VPL reports a failure
     */
    static @NotNull JsonObject parseEnvelope(@NotNull String body) throws MoodleException {
        JsonElement json = MoodleResponses.parseJson(body);
        if (!json.isJsonObject()) {
            throw new MoodleException("invalidresponse", "VPL vrátil neočekávanou odpověď.");
        }
        JsonObject envelope = json.getAsJsonObject();
        if (!MoodleResponses.getBoolean(envelope, "success", false)) {
            String error = MoodleResponses.getString(envelope, "error");
            throw new MoodleException(VPL_ERROR, error == null || error.isBlank() ? "VPL operaci odmítl." : error);
        }
        JsonElement response = envelope.get("response");
        return response != null && response.isJsonObject() ? response.getAsJsonObject() : new JsonObject();
    }

    static @NotNull List<VplFile> parseFiles(@Nullable JsonElement json) {
        List<VplFile> files = new ArrayList<>();
        if (!(json instanceof JsonArray array)) {
            return files;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            String name = MoodleResponses.getString(file, "name");
            String contents = MoodleResponses.getString(file, "contents");
            if (name == null || contents == null) continue;
            byte[] data = MoodleResponses.getInt(file, "encoding", 0) == 1
                ? Base64.getMimeDecoder().decode(contents)
                : contents.getBytes(StandardCharsets.UTF_8);
            files.add(new VplFile(name, data));
        }
        return files;
    }

    static @NotNull JsonArray filesToJson(@NotNull List<VplFile> files) {
        JsonArray array = new JsonArray();
        for (VplFile file : files) {
            JsonObject json = new JsonObject();
            json.addProperty("name", file.name());
            if (file.isBinary()) {
                json.addProperty("contents", Base64.getEncoder().encodeToString(file.data()));
                json.addProperty("encoding", 1);
            }
            else {
                json.addProperty("contents", new String(file.data(), StandardCharsets.UTF_8));
                json.addProperty("encoding", 0);
            }
            array.add(json);
        }
        return array;
    }

    /** Parses VPL's "compilationexecution" object; null for {@code false} (no result yet). */
    static @Nullable VplResult parseResult(@Nullable JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        JsonObject ce = json.getAsJsonObject();
        return new VplResult(
            text(ce, "compilation"),
            text(ce, "evaluation"),
            text(ce, "execution"),
            text(ce, "grade"),
            MoodleResponses.getInt(ce, "nevaluations", 0),
            MoodleResponses.getInt(ce, "freeevaluations", 0),
            text(ce, "reductionbyevaluation"));
    }

    static @NotNull VplSubmission parseSubmission(@NotNull JsonObject response) {
        return new VplSubmission(
            parseFiles(response.get("files")),
            MoodleResponses.getLong(response, "version", 0),
            text(response, "comments"),
            parseResult(response.get("compilationexecution")),
            timeLeft(response));
    }

    static @NotNull VplSaveResult parseSave(@NotNull JsonObject response) throws MoodleException {
        long version = MoodleResponses.getLong(response, "version", -1);
        if (MoodleResponses.getBoolean(response, "requestsconfirmation", false)) {
            return new VplSaveResult(false, version, MoodleResponses.getString(response, "question"));
        }
        if (!MoodleResponses.getBoolean(response, "saved", false) || version < 0) {
            throw new MoodleException("invalidresponse", "VPL odevzdání neuložil.");
        }
        return VplSaveResult.saved(version);
    }

    static @NotNull VplExecution parseExecution(@NotNull JsonObject response) throws MoodleException {
        String server = MoodleResponses.getString(response, "server");
        String monitorPath = MoodleResponses.getString(response, "monitorPath");
        long processId = MoodleResponses.getLong(response, "processid", -1);
        if (server == null || monitorPath == null) {
            throw new MoodleException("invalidresponse", "VPL nevrátil adresu vyhodnocovacího serveru.");
        }
        return new VplExecution(server,
            MoodleResponses.getInt(response, "port", 80),
            MoodleResponses.getInt(response, "securePort", 443),
            monitorPath, processId,
            MoodleResponses.getString(response, "wsProtocol"));
    }

    static @Nullable Long timeLeft(@NotNull JsonObject response) {
        JsonElement value = response.get("timeLeft");
        return value != null && value.isJsonPrimitive() ? MoodleResponses.getLong(response, "timeLeft", 0) : null;
    }

    private static @NotNull String text(@NotNull JsonObject obj, @NotNull String key) {
        String value = MoodleResponses.getString(obj, key);
        return value != null ? value : "";
    }
}
