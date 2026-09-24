package cz.vse.moodle.vpl;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.vpl.api.VplApi;
import cz.vse.moodle.vpl.api.VplFile;
import cz.vse.moodle.vpl.api.VplSubmission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Downloads a VPL activity into {@code <projects dir>/<course>/<activity>} and opens it as a project. */
public final class VplTaskOpener {
    private static final Logger LOG = Logger.getInstance(VplTaskOpener.class);

    private VplTaskOpener() {
    }

    public static void open(@Nullable Project current, long courseId, @Nullable String courseName, @NotNull CourseModule activity) {
        new Task.Backgroundable(current, "Moodle VPL: stahování úlohy " + activity.name(), true) {
            private Path dir;
            private String firstFile;
            private VirtualFile fileToOpen;
            private String error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    MoodleClient client = VplService.requireClient();
                    Path courseDir = MoodleSettings.getInstance().getProjectsDir()
                        .resolve(VplProjectFiles.safeDirName(courseName != null ? courseName : "Kurz " + courseId));
                    dir = chooseDir(courseDir, activity);
                    VplTaskMetadata existing = VplTaskMetadata.read(dir);
                    if (existing != null) {
                        // Already downloaded: open it as it is, the student may have local changes.
                        firstFile = existing.requestedFiles.isEmpty() ? null : existing.requestedFiles.getFirst();
                        return;
                    }

                    VplApi api = VplService.getInstance().getApi();
                    indicator.setText("Stahuji zadané soubory…");
                    List<VplFile> requested = api.requestedFiles(activity.id());
                    indicator.setText("Stahuji poslední odevzdání…");
                    VplSubmission submission = api.load(activity.id());
                    List<VplFile> files = VplSubmitterStamp.strip(submission.files().isEmpty() ? requested : submission.files());

                    Files.createDirectories(dir);
                    VplProjectFiles.writeFiles(dir, files);
                    VplTaskMetadata metadata = new VplTaskMetadata();
                    metadata.siteUrl = client.getSiteUrl();
                    metadata.courseId = courseId;
                    metadata.courseName = courseName;
                    metadata.cmid = activity.id();
                    metadata.name = activity.name();
                    metadata.due = activity.due() != null ? activity.due().getEpochSecond() : 0;
                    metadata.version = submission.version();
                    metadata.requestedFiles = requested.stream().map(VplFile::name).toList();
                    metadata.write(dir);
                    VplProjectFiles.writeJavaProjectConfig(dir, dir.getFileName().toString(), files, findJdkName());
                    firstFile = !metadata.requestedFiles.isEmpty() ? metadata.requestedFiles.getFirst()
                        : files.isEmpty() ? null : files.getFirst().name();
                }
                catch (IOException | MoodleException e) {
                    LOG.info("Could not download VPL task " + activity.id(), e);
                    error = VplTaskService.errorMessage(e);
                    return;
                }
                // VFS refresh is slow, so it must not run on the EDT.
                indicator.setText("Připravuji projekt…");
                VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir);
                if (root != null) {
                    root.refresh(false, true);
                }
                if (firstFile != null) {
                    try {
                        fileToOpen = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(VplProjectFiles.resolve(dir, firstFile));
                    }
                    catch (IOException ignored) {
                        // invalid name: nothing to open
                    }
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    Messages.showErrorDialog(current, error, "Úlohu se nepodařilo stáhnout");
                    return;
                }
                openProject(dir, fileToOpen);
            }
        }.queue();
    }

    /** Uses "<course>/<activity>"; falls back to "<activity> (<cmid>)" when that folder holds something else. */
    private static @NotNull Path chooseDir(@NotNull Path courseDir, @NotNull CourseModule activity) throws IOException {
        Path dir = courseDir.resolve(VplProjectFiles.safeDirName(activity.name()));
        VplTaskMetadata existing = VplTaskMetadata.read(dir);
        if (!Files.exists(dir) || (existing != null && existing.cmid == activity.id()) || isEmptyDir(dir)) {
            return dir;
        }
        return courseDir.resolve(VplProjectFiles.safeDirName(activity.name()) + " (" + activity.id() + ")");
    }

    private static boolean isEmptyDir(@NotNull Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /** The newest configured Java SDK, so the generated project compiles without further setup. */
    private static @Nullable String findJdkName() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> Stream.of(ProjectJdkTable.getInstance().getAllJdks())
            .filter(sdk -> "JavaSDK".equals(sdk.getSdkType().getName()))
            .max(Comparator.comparing(sdk -> sdk.getVersionString() != null ? sdk.getVersionString() : "", VplTaskOpener::compareVersions))
            .map(Sdk::getName)
            .orElse(null));
    }

    /** Compares the first number found in JDK version strings ("openjdk version 21.0.2" vs "17"). */
    static int compareVersions(@NotNull String a, @NotNull String b) {
        return Integer.compare(majorVersion(a), majorVersion(b));
    }

    private static int majorVersion(@NotNull String version) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(version);
        if (!matcher.find()) return 0;
        int major = Integer.parseInt(matcher.group(1));
        // "1.8" style
        if (major == 1 && matcher.find()) return Integer.parseInt(matcher.group(1));
        return major;
    }

    /** Runs on the EDT; the VFS was already refreshed in the background. */
    private static void openProject(@NotNull Path dir, @Nullable VirtualFile fileToOpen) {
        Project project = ProjectUtil.openOrImport(dir, null, true);
        if (project == null) return;
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Moodle");
            if (toolWindow != null) {
                toolWindow.activate(null, false);
            }
            if (fileToOpen != null && fileToOpen.isValid()) {
                FileEditorManager.getInstance(project).openFile(fileToOpen, true);
            }
        });
    }
}
