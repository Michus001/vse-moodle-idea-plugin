package cz.vse.moodle.vpl;

import cz.vse.moodle.util.PathNames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Where VPL tasks live on disk: {@code <projects dir>/<course>/<task>}, both names sanitized by {@link PathNames}.
 * Existing tasks are found by the Moodle ids in their {@link VplTaskMetadata}, never by the folder name,
 * so renaming a course or a task in Moodle (or changing the sanitization) does not lose the student's work.
 * No IntelliJ APIs, so it is unit-testable.
 */
public final class VplProjectLocator {
    private VplProjectLocator() {
    }

    public static @NotNull String courseDirName(long courseId, @Nullable String courseName) {
        return PathNames.safeDirName(courseName, "kurz-" + courseId);
    }

    public static @NotNull String taskDirName(long cmid, @Nullable String name) {
        return PathNames.safeDirName(name, "uloha-" + cmid);
    }

    /** Variant used when {@link #taskDirName} is already taken by something else. */
    public static @NotNull String taskDirNameWithId(long cmid, @Nullable String name) {
        return PathNames.safeDirName(name, "uloha", "_" + cmid);
    }

    /** A task folder: {@code <projects dir>/<course>/<task>}. */
    public record TaskDir(@NotNull Path dir, @NotNull VplTaskMetadata metadata) {
    }

    /** All task folders (with valid metadata) two levels below {@code projectsDir}. */
    public static @NotNull List<TaskDir> findAll(@NotNull Path projectsDir) {
        List<TaskDir> result = new ArrayList<>();
        for (Path courseDir : subdirectories(projectsDir)) {
            for (Path taskDir : subdirectories(courseDir)) {
                VplTaskMetadata metadata = VplTaskMetadata.read(taskDir);
                if (metadata != null) {
                    result.add(new TaskDir(taskDir, metadata));
                }
            }
        }
        return result;
    }

    /** The folder of an already downloaded task, matched by site and course module id. */
    public static @Nullable TaskDir find(@NotNull Path projectsDir, @NotNull String siteUrl, long cmid) {
        return findAll(projectsDir).stream()
            .filter(task -> task.metadata().cmid == cmid && sameSite(task.metadata().siteUrl, siteUrl))
            .findFirst()
            .orElse(null);
    }

    /**
     * Folder for a task that is not downloaded yet: {@code <course>/<task>}, or {@code <course>/<task>_<cmid>}
     * (then {@code _2}, {@code _3}…) when that folder already holds something else.
     */
    public static @NotNull Path newTaskDir(@NotNull Path courseDir, long cmid, @Nullable String name) throws IOException {
        Path dir = courseDir.resolve(taskDirName(cmid, name));
        if (isFree(dir)) return dir;
        String withId = taskDirNameWithId(cmid, name);
        dir = courseDir.resolve(withId);
        for (int i = 2; !isFree(dir); i++) {
            dir = courseDir.resolve(PathNames.safeDirName(withId, "uloha", "_" + i));
        }
        return dir;
    }

    static boolean sameSite(@Nullable String a, @Nullable String b) {
        return a != null && b != null && normalizeSite(a).equals(normalizeSite(b));
    }

    private static @NotNull String normalizeSite(@NotNull String url) {
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static boolean isFree(@NotNull Path dir) throws IOException {
        if (!Files.exists(dir)) return true;
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    private static @NotNull List<Path> subdirectories(@NotNull Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }
}
