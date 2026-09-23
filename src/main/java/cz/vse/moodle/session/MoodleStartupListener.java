package cz.vse.moodle.session;

import com.intellij.ide.AppLifecycleListener;


/** Verifies the stored token right after IDE startup. */
public final class MoodleStartupListener implements AppLifecycleListener {
    @Override
    public void appStarted() {
        MoodleSessionService.getInstance().restoreSessionIfNeeded();
    }
}
