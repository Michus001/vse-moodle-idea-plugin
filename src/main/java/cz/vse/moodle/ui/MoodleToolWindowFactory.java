package cz.vse.moodle.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import cz.vse.moodle.session.MoodleSessionService;
import org.jetbrains.annotations.NotNull;

public final class MoodleToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MoodleAccountPanel panel = new MoodleAccountPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        // Normally done at IDE startup; covers the plugin being installed/enabled without a restart.
        MoodleSessionService.getInstance().restoreSessionIfNeeded();
    }
}
