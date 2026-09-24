package cz.vse.moodle.vpl;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Tools | Moodle VPL actions; available only in projects created from a VPL activity. */
public abstract class VplTaskAction extends DumbAwareAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VplTaskService service = project != null ? VplTaskService.getInstance(project) : null;
        boolean vpl = service != null && service.isVplProject();
        e.getPresentation().setVisible(vpl);
        e.getPresentation().setEnabled(vpl && !service.isBusy());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            perform(VplTaskService.getInstance(project));
        }
    }

    protected abstract void perform(@NotNull VplTaskService service);

    public static final class Evaluate extends VplTaskAction {
        @Override
        protected void perform(@NotNull VplTaskService service) {
            service.submit(true);
        }
    }

    public static final class Submit extends VplTaskAction {
        @Override
        protected void perform(@NotNull VplTaskService service) {
            service.submit(false);
        }
    }

    public static final class Download extends VplTaskAction {
        @Override
        protected void perform(@NotNull VplTaskService service) {
            service.downloadLatest();
        }
    }
}
