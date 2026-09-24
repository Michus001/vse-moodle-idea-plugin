package cz.vse.moodle.session;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Verifies the stored token right after IDE startup. */
public final class MoodleStartupListener implements AppLifecycleListener {
    // appStarted() would fit better but is internal API (Plugin Verifier flags it).
    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        MoodleSessionService.getInstance().restoreSessionIfNeeded();
    }
}
