package cz.vse.moodle.vpl;

import cz.vse.moodle.util.PathNames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Renames task folders created by older plugin versions, whose names contain non-ASCII characters
 * ({@code Programování v Javě/Hon na hodnotu}), to ASCII names ({@code Programovani_v_Jave/Hon_na_hodnotu}),
 * so that IntelliJ's build (JPS) works on Windows with a non-Unicode ANSI code page.
 * <p>
 * Nothing is ever deleted except an old course folder left completely empty. A task is skipped (and reported) when
 * its project is open, when the target folder already exists, or when the rename fails (files locked on Windows).
 * No IntelliJ APIs, so it is unit-testable.
 */
public final class VplProjectMigration {
    private VplProjectMigration() {
    }

    public enum Status {
        /** Renamed (or only the module file was renamed). */
        MIGRATED,
        /** The project is open in the IDE; renaming its folder would break it. */
        SKIPPED_OPEN,
        /** The target folder already exists. */
        CONFLICT,
        /** The rename failed, typically because a file in the folder is locked. */
        FAILED
    }

    /**
     * @param from    the original task folder
     * @param to      the folder the task is in now (equal to {@code from} unless moved) or the intended target
     * @param problem what went wrong, for the log and the notification; null on success
     */
    public record Result(@NotNull Status status, @NotNull Path from, @NotNull Path to, @Nullable String problem) {
        public boolean ok() {
            return status == Status.MIGRATED;
        }

        /** Folder where the task's files are after the migration attempt. */
        public @NotNull Path current() {
            return ok() ? to : from;
        }
    }

    /** Migrates every task under {@code projectsDir} that needs it. */
    public static @NotNull List<Result> migrateAll(@NotNull Path projectsDir, @NotNull Predicate<Path> isOpen) {
        List<Result> results = new ArrayList<>();
        for (VplProjectLocator.TaskDir task : VplProjectLocator.findAll(projectsDir)) {
            if (needsMigration(projectsDir, task.dir())) {
                results.add(migrate(projectsDir, task, isOpen));
            }
        }
        return results;
    }

    /** True if the course or task folder name, or the module file of a plugin-generated project, is not ASCII. */
    public static boolean needsMigration(@NotNull Path projectsDir, @NotNull Path taskDir) {
        return !PathNames.isAscii(taskDir.getFileName().toString())
            || !PathNames.isAscii(taskDir.getParent().getFileName().toString())
            || !nonAsciiModuleFiles(taskDir).isEmpty();
    }

    /**
     * Moves {@code task} to {@code <projects dir>/<ascii course>/<ascii task>} and renames a non-ASCII module file.
     * Folder names that are already ASCII are kept.
     */
    public static @NotNull Result migrate(@NotNull Path projectsDir, @NotNull VplProjectLocator.TaskDir task,
                                          @NotNull Predicate<Path> isOpen) {
        Path from = task.dir();
        VplTaskMetadata metadata = task.metadata();
        Path oldCourseDir = from.getParent();
        if (isOpen.test(from)) {
            return new Result(Status.SKIPPED_OPEN, from, from, "projekt úlohy je otevřený");
        }

        String oldCourseName = oldCourseDir.getFileName().toString();
        Path courseDir = PathNames.isAscii(oldCourseName) ? oldCourseDir
            : projectsDir.resolve(VplProjectLocator.courseDirName(metadata.courseId,
                metadata.courseName != null ? metadata.courseName : oldCourseName));
        String oldTaskName = from.getFileName().toString();
        String taskName = metadata.name != null ? metadata.name : oldTaskName;
        Path to = from;
        if (!courseDir.equals(oldCourseDir) || !PathNames.isAscii(oldTaskName)) {
            to = courseDir.resolve(VplProjectLocator.taskDirName(metadata.cmid, taskName));
            if (Files.exists(to)) {
                to = courseDir.resolve(VplProjectLocator.taskDirNameWithId(metadata.cmid, taskName));
            }
            if (Files.exists(to)) {
                return new Result(Status.CONFLICT, from, to, "cílová složka už existuje");
            }
            boolean createdCourseDir = !Files.exists(courseDir);
            try {
                Files.createDirectories(courseDir);
                Files.move(from, to);
            }
            catch (IOException e) {
                if (createdCourseDir) deleteIfEmpty(courseDir);
                return new Result(Status.FAILED, from, to, describe(e));
            }
            if (!oldCourseDir.equals(courseDir)) {
                deleteIfEmpty(oldCourseDir);
            }
        }

        try {
            renameModuleFiles(to);
        }
        catch (IOException e) {
            // The folder is already ASCII, so this only leaves a module file the build may still trip on.
            return new Result(to.equals(from) ? Status.FAILED : Status.MIGRATED, from, to,
                "nepodařilo se přejmenovat soubor modulu (.iml): " + describe(e));
        }
        return new Result(Status.MIGRATED, from, to, null);
    }

    /**
     * Renames {@code <non-ascii>.iml} in the project root to an ASCII name and updates references in {@code .idea}.
     * Only module files registered in {@code .idea/modules.xml} are touched, i.e. projects generated by the plugin.
     */
    static void renameModuleFiles(@NotNull Path root) throws IOException {
        Path modulesXml = root.resolve(".idea/modules.xml");
        for (Path iml : nonAsciiModuleFiles(root)) {
            if (!Files.isRegularFile(modulesXml)) return;
            String oldName = iml.getFileName().toString();
            String oldModule = oldName.substring(0, oldName.length() - ".iml".length());
            if (!Files.readString(modulesXml, StandardCharsets.UTF_8).contains("$PROJECT_DIR$/" + oldName)) {
                continue;
            }
            String newModule = PathNames.safeDirName(oldModule, "uloha");
            Path newIml = root.resolve(newModule + ".iml");
            if (Files.exists(newIml)) {
                throw new IOException("soubor " + newIml.getFileName() + " už existuje");
            }
            Files.move(iml, newIml);
            try (Stream<Path> files = Files.walk(root.resolve(".idea"))) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".xml")).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String updated = content
                        .replace("$PROJECT_DIR$/" + oldName, "$PROJECT_DIR$/" + newModule + ".iml")
                        .replace("<module name=\"" + oldModule + "\"", "<module name=\"" + newModule + "\"");
                    if (!updated.equals(content)) {
                        Files.writeString(file, updated, StandardCharsets.UTF_8);
                    }
                }
            }
        }
    }

    private static @NotNull List<Path> nonAsciiModuleFiles(@NotNull Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".iml") && !PathNames.isAscii(name) && Files.isRegularFile(f);
            }).sorted().toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }

    private static void deleteIfEmpty(@NotNull Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) {
                Files.delete(dir);
            }
        }
        catch (IOException ignored) {
            // leave it
        }
    }

    private static @NotNull String describe(@NotNull IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
