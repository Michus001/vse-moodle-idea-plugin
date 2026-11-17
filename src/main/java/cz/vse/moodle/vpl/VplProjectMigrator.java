package cz.vse.moodle.vpl;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import cz.vse.moodle.settings.MoodleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs {@link VplProjectMigration} inside the IDE: knows which projects are open and tells the student what happened. */
public final class VplProjectMigrator {
    private static final Logger LOG = Logger.getInstance(VplProjectMigrator.class);
    private static final Object LOCK = new Object();

    private VplProjectMigrator() {
    }

    /**
     * Delay of the startup migration: IntelliJ reopens the last projects right after the frame is created, and a folder
     * must not be renamed while its project is being opened (only an already open project is detected by {@link #isOpen}).
     */
    private static final long STARTUP_DELAY_SECONDS = 30;

    /** Called at IDE startup. */
    public static void migrateAllAfterStartup() {
        AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(VplProjectMigrator::migrateAll, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** Migrates all tasks in the projects folder on a pooled thread. */
    public static void migrateAllInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread(VplProjectMigrator::migrateAll);
    }

    private static void migrateAll() {
        try {
            List<VplProjectMigration.Result> results;
            synchronized (LOCK) {
                results = VplProjectMigration.migrateAll(MoodleSettings.getInstance().getProjectsDir(), VplProjectMigrator::isOpen);
            }
            results.forEach(result -> report(result, null));
        }
        catch (RuntimeException e) {
            LOG.warn("Migration of VPL task folders failed", e);
        }
    }

    /**
     * Migrates one task before it is opened (called from a background task).
     *
     * @return the folder the task is in now: the new one, or the old one when the migration was not possible
     */
    public static @NotNull Path migrateIfNeeded(@NotNull Path projectsDir, @NotNull VplProjectLocator.TaskDir task,
                                                @Nullable Project project) {
        if (!VplProjectMigration.needsMigration(projectsDir, task.dir())) {
            return task.dir();
        }
        VplProjectMigration.Result result;
        synchronized (LOCK) {
            result = VplProjectMigration.migrate(projectsDir, task, VplProjectMigrator::isOpen);
        }
        report(result, project);
        return result.current();
    }

    /** True if {@code dir} is (inside, or contains) the folder of a project open in this IDE. */
    static boolean isOpen(@NotNull Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        return Stream.of(ProjectManager.getInstance().getOpenProjects())
            .map(Project::getBasePath)
            .filter(basePath -> basePath != null)
            .map(basePath -> Path.of(basePath).toAbsolutePath().normalize())
            .anyMatch(base -> base.startsWith(normalized) || normalized.startsWith(base));
    }

    private static void report(@NotNull VplProjectMigration.Result result, @Nullable Project project) {
        String name = result.from().getFileName().toString();
        if (result.ok() && result.problem() == null) {
            LOG.info("Renamed VPL task folder " + result.from() + " -> " + result.to());
            return;
        }
        LOG.warn("Could not migrate VPL task folder " + result.from() + " -> " + result.to() + ": " + result.status()
            + (result.problem() != null ? " (" + result.problem() + ")" : ""));
        String content = switch (result.status()) {
            case SKIPPED_OPEN -> "Projekt úlohy „" + name + "“ je otevřený, proto ho nelze přejmenovat na ASCII název. "
                + "Zavřete projekt a zkuste to znovu.";
            case CONFLICT -> "Složku úlohy „" + name + "“ nelze přejmenovat, protože složka " + result.to()
                + " už existuje. Nic nebylo smazáno; zkontrolujte obě složky a jednu z nich přesuňte jinam.";
            case FAILED -> "Složku úlohy „" + name + "“ se nepodařilo přejmenovat (" + result.problem() + "). "
                + "Nejspíš jsou některé soubory otevřené nebo zamčené. Zavřete projekt úlohy a zkuste to znovu.";
            case MIGRATED -> "Složka úlohy byla přejmenována na " + result.to() + ", ale " + result.problem() + ".";
        };
        content += "\n\nNázvy s diakritikou mohou ve Windows bez Unicode nastavení způsobit chybu při překladu (Build).";
        var notification = NotificationGroupManager.getInstance().getNotificationGroup("Moodle VŠE")
            .createNotification("Přejmenování složky úlohy", escape(content), NotificationType.WARNING);
        if (result.status() != VplProjectMigration.Status.CONFLICT) {
            notification.addAction(NotificationAction.createSimpleExpiring("Zkusit znovu", VplProjectMigrator::migrateAllInBackground));
        }
        notification.notify(project);
    }

    private static @NotNull String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
