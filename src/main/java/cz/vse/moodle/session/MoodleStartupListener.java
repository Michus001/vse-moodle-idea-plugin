package cz.vse.moodle.session;

import com.intellij.ide.AppLifecycleListener;
import cz.vse.moodle.vpl.VplProjectMigrator;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Verifies the stored token right after IDE startup and renames task folders with non-ASCII names. */
public final class MoodleStartupListener implements AppLifecycleListener {
    // appStarted() would fit better but is internal API (Plugin Verifier flags it).
    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        MoodleSessionService.getInstance().restoreSessionIfNeeded();
        VplProjectMigrator.migrateAllAfterStartup();
    }
}
