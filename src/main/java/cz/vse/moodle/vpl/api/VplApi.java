package cz.vse.moodle.vpl.api;

import com.google.gson.JsonObject;
import cz.vse.moodle.api.MoodleException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Typed access to VPL's editor actions for the current user. Blocking; call from background threads.
 * <p>
 * When an action fails because the web session expired, a new session is requested once and the action retried.
 */
public final class VplApi {
    /** Supplies a logged-in session; {@code expired} is the session that just stopped working, if any. */
    @FunctionalInterface
    public interface SessionSupplier {
        @NotNull VplWebSession get(@Nullable VplWebSession expired) throws IOException, MoodleException;
    }

    private final SessionSupplier sessions;

    public VplApi(@NotNull SessionSupplier sessions) {
        this.sessions = sessions;
    }

    /** Requested files merged with the student's last submission. */
    public @NotNull VplSubmission load(long cmid) throws IOException, MoodleException {
        return VplJson.parseSubmission(call(cmid, "load", new JsonObject()));
    }

    /** The teacher's initial ("requested") files. */
    public @NotNull List<VplFile> requestedFiles(long cmid) throws IOException, MoodleException {
        return VplJson.parseFiles(call(cmid, "resetfiles", new JsonObject()).get("files"));
    }

    /**
     * Saves the files as a new submission.
     *
     * @param baseVersion the submission the files are based on; VPL refuses to save (asks for confirmation)
     *                    when a newer one exists. {@code -1} overwrites unconditionally.
     */
    public @NotNull VplSaveResult save(long cmid, @NotNull List<VplFile> files, @NotNull String comments, long baseVersion)
        throws IOException, MoodleException {
        JsonObject data = new JsonObject();
        data.add("files", VplJson.filesToJson(files));
        data.addProperty("comments", comments);
        // VPL treats a missing/0 version as "don't check".
        data.addProperty("version", baseVersion > 0 ? baseVersion : -1);
        return VplJson.parseSave(call(cmid, "save", data));
    }

    /** Starts evaluating the last saved submission. */
    public @NotNull VplExecution evaluate(long cmid) throws IOException, MoodleException {
        return VplJson.parseExecution(call(cmid, "evaluate", new JsonObject()));
    }

    /** Fetches the result once the monitor said {@code retrieve}. */
    public @NotNull VplResult retrieve(long cmid, long processId) throws IOException, MoodleException {
        JsonObject data = new JsonObject();
        data.addProperty("processid", processId);
        VplResult result = VplJson.parseResult(call(cmid, "retrieve", data));
        if (result == null) {
            throw new MoodleException("invalidresponse", "VPL nevrátil výsledek vyhodnocení.");
        }
        return result;
    }

    public void cancel(long cmid, long processId) throws IOException, MoodleException {
        JsonObject data = new JsonObject();
        data.addProperty("processid", processId);
        call(cmid, "cancel", data);
    }

    private @NotNull JsonObject call(long cmid, @NotNull String action, @NotNull JsonObject data) throws IOException, MoodleException {
        VplWebSession session = sessions.get(null);
        try {
            return session.action(cmid, action, data);
        }
        catch (MoodleException e) {
            // Errors are plain localized text, so ask Moodle whether the session is still valid.
            if (session.isAlive()) {
                throw e;
            }
        }
        return sessions.get(session).action(cmid, action, data);
    }
}
