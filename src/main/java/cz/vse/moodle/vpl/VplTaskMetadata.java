package cz.vse.moodle.vpl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Links a project directory to a VPL activity. Stored as {@value #FILE_NAME} in the project root;
 * it contains no secrets, so it is safe to commit or share.
 */
public final class VplTaskMetadata {
    public static final String FILE_NAME = ".moodle-vpl.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public String siteUrl;
    public long courseId;
    public String courseName;
    /** Course module id of the VPL activity. */
    public long cmid;
    public String name;
    /** Due date as epoch seconds, 0 when there is none. */
    public long due;
    /** Id of the submission the local files are based on (0 = nothing submitted yet). */
    public long version;
    /** Files the teacher provides; they are always submitted. */
    public List<String> requestedFiles = new ArrayList<>();

    public @NotNull String activityUrl() {
        return siteUrl + "/mod/vpl/view.php?id=" + cmid;
    }

    public static @Nullable VplTaskMetadata read(@NotNull Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            VplTaskMetadata metadata = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), VplTaskMetadata.class);
            if (metadata == null || metadata.siteUrl == null || metadata.cmid <= 0) {
                return null;
            }
            if (metadata.requestedFiles == null) {
                metadata.requestedFiles = new ArrayList<>();
            }
            return metadata;
        }
        catch (IOException | JsonParseException e) {
            return null;
        }
    }

    public void write(@NotNull Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(FILE_NAME), GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
    }
}
