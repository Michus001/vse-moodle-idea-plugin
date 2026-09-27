package cz.vse.moodle.training;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import cz.vse.moodle.api.SiteUrls;
import cz.vse.moodle.vpl.api.VplResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Local record of the student's progress on training tasks, updated whenever a VPL result of a training task
 * arrives (after "Ověřit", or when a task project loads its last result). The Trénink tab reads only this,
 * so listing the tasks never needs a VPL request per task.
 */
@Service(Service.Level.APP)
@State(name = "MoodleVseTraining", storages = @Storage("moodle-vse-training.xml"))
public final class TrainingProgress implements PersistentStateComponent<TrainingProgress.ProgressState> {

    public static final class Entry {
        public String siteUrl;
        /** Course module id of the VPL activity. */
        public long cmid;
        public boolean solved;
        /** Best share of the maximum reached so far (0..1), -1 when no result was scored yet. */
        public double bestScore = -1;
        /** Grade text of the best result, e.g. "Navrhovaná známka: 8 / 10". */
        public String bestGrade;
        public long updated;
    }

    public static final class ProgressState {
        public List<Entry> tasks = new ArrayList<>();
    }

    /** What the Trénink tab shows for a task. */
    public record TaskProgress(@NotNull TrainingStatus status, @Nullable String bestGrade) {
        static final TaskProgress NEW = new TaskProgress(TrainingStatus.NEW, null);
    }

    private ProgressState state = new ProgressState();

    public static @NotNull TrainingProgress getInstance() {
        return ApplicationManager.getApplication().getService(TrainingProgress.class);
    }

    public synchronized @NotNull TaskProgress get(@NotNull String siteUrl, long cmid) {
        Entry entry = find(siteUrl, cmid);
        if (entry == null) return TaskProgress.NEW;
        return new TaskProgress(entry.solved ? TrainingStatus.SOLVED : TrainingStatus.STARTED, entry.bestGrade);
    }

    /** The task was downloaded and opened: it is no longer new. */
    public void markStarted(@NotNull String siteUrl, long cmid) {
        synchronized (this) {
            if (find(siteUrl, cmid) != null) return;
            entry(siteUrl, cmid);
        }
        fireChanged();
    }

    /** Records a VPL result of the task; keeps the best one. */
    public void record(@NotNull String siteUrl, long cmid, @NotNull VplResult result) {
        if (!result.isEvaluated()) {
            markStarted(siteUrl, cmid);
            return;
        }
        Double score = TrainingTasks.score(result);
        synchronized (this) {
            Entry entry = entry(siteUrl, cmid);
            entry.updated = System.currentTimeMillis();
            if (score != null && score > entry.bestScore) {
                entry.bestScore = score;
                entry.bestGrade = !result.grade().isBlank() ? result.grade().strip() : Math.round(score * 100) + " % testů";
            }
            if (score != null && TrainingTasks.isFullScore(score)) {
                entry.solved = true;
            }
        }
        fireChanged();
    }

    private @Nullable Entry find(@NotNull String siteUrl, long cmid) {
        String site = SiteUrls.normalize(siteUrl);
        for (Entry entry : state.tasks) {
            if (entry.cmid == cmid && entry.siteUrl != null && SiteUrls.normalize(entry.siteUrl).equals(site)) return entry;
        }
        return null;
    }

    private @NotNull Entry entry(@NotNull String siteUrl, long cmid) {
        Entry entry = find(siteUrl, cmid);
        if (entry == null) {
            entry = new Entry();
            entry.siteUrl = SiteUrls.normalize(siteUrl);
            entry.cmid = cmid;
            entry.updated = System.currentTimeMillis();
            state.tasks.add(entry);
        }
        return entry;
    }

    private static void fireChanged() {
        ApplicationManager.getApplication().invokeLater(
            () -> ApplicationManager.getApplication().getMessageBus().syncPublisher(TrainingProgressListener.TOPIC).progressChanged(),
            ModalityState.any());
    }

    @Override
    public synchronized @NotNull ProgressState getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull ProgressState state) {
        this.state = state;
        if (state.tasks == null) state.tasks = new ArrayList<>();
    }
}
