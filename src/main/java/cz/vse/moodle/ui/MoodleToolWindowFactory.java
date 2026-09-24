package cz.vse.moodle.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.vpl.VplTaskService;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

public final class MoodleToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager contents = toolWindow.getContentManager();
        if (VplTaskService.getInstance(project).isVplProject()) {
            VplTaskPanel task = new VplTaskPanel(project);
            addContent(contents, task, "Úloha", task);
        }
        VplAssignmentsPanel assignments = new VplAssignmentsPanel(project);
        addContent(contents, assignments, "Úlohy", assignments);
        MoodleAccountPanel account = new MoodleAccountPanel(project);
        addContent(contents, account, "Student", account);
        contents.setSelectedContent(contents.getContent(0));

        // Normally done at IDE startup; covers the plugin being installed/enabled without a restart.
        MoodleSessionService.getInstance().restoreSessionIfNeeded();
    }

    private static void addContent(@NotNull ContentManager contents, @NotNull JComponent component, @NotNull String name,
                                   @NotNull Disposable disposer) {
        Content content = ContentFactory.getInstance().createContent(component, name, false);
        content.setDisposer(disposer);
        contents.addContent(content);
    }
}
