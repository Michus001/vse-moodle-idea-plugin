package cz.vse.moodle.vpl;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.api.SiteUrls;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.vpl.api.VplApi;
import cz.vse.moodle.vpl.api.VplExecution;
import cz.vse.moodle.vpl.api.VplFile;
import cz.vse.moodle.vpl.api.VplMonitor;
import cz.vse.moodle.vpl.api.VplResult;
import cz.vse.moodle.vpl.api.VplSaveResult;
import cz.vse.moodle.vpl.api.VplSubmission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The VPL task of the current project (if its root contains {@link VplTaskMetadata#FILE_NAME}):
 * submitting, evaluating and downloading the last submission.
 */
@Service(Service.Level.PROJECT)
public final class VplTaskService {
    private static final Logger LOG = Logger.getInstance(VplTaskService.class);
    /** VPL's own limit for an evaluation is usually well below this. */
    private static final Duration EVALUATION_TIMEOUT = Duration.ofMinutes(5);

    private final Project project;
    private volatile @Nullable VplTaskMetadata metadata;
    private volatile boolean metadataLoaded;
    private volatile @Nullable VplResult lastResult;
    private volatile @Nullable Long timeLeft;
    private volatile @Nullable String status;
    private volatile boolean statusIsError;
    private final AtomicBoolean busy = new AtomicBoolean();

    public VplTaskService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull VplTaskService getInstance(@NotNull Project project) {
        return project.getService(VplTaskService.class);
    }

    /** Metadata of the project's VPL task, or null for other projects. */
    public @Nullable VplTaskMetadata getMetadata() {
        if (!metadataLoaded) {
            Path root = projectRoot();
            metadata = root != null ? VplTaskMetadata.read(root) : null;
            metadataLoaded = true;
        }
        return metadata;
    }

    public boolean isVplProject() {
        return getMetadata() != null;
    }

    public @Nullable VplResult getLastResult() {
        return lastResult;
    }

    /** Seconds to the due date as last reported by VPL. */
    public @Nullable Long getTimeLeft() {
        return timeLeft;
    }

    public @Nullable String getStatus() {
        return status;
    }

    public boolean isStatusError() {
        return statusIsError;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** Loads the result of the last submission and the evaluation counters (doesn't touch local files). */
    public void refreshResult() {
        VplTaskMetadata task = getMetadata();
        if (task == null || !checkSite(task, false) || !busy.compareAndSet(false, true)) return;
        setStatus("Načítám stav odevzdání…", false);
        new Task.Backgroundable(project, "Moodle VPL: načítání stavu úlohy", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    VplSubmission submission = VplService.getInstance().getApi().load(task.cmid);
                    lastResult = submission.result();
                    timeLeft = submission.timeLeft();
                    setStatus(submission.version() > 0 ? null : "Úloha zatím nebyla odevzdána.", false);
                }
                catch (IOException | MoodleException e) {
                    setStatus(errorMessage(e), true);
                }
            }

            @Override
            public void onFinished() {
                finish();
            }
        }.queue();
    }

    /**
     * Saves all editors and uploads the project files as a new submission.
     *
     * @param evaluate also run VPL's evaluation and show the result ("Ověřit")
     */
    public void submit(boolean evaluate) {
        VplTaskMetadata task = getMetadata();
        Path root = projectRoot();
        if (task == null || root == null || !checkSite(task, true)) return;
        VplResult known = lastResult;
        if (evaluate && known != null && known.nextEvaluationIsPenalized()) {
            int answer = Messages.showYesNoDialog(project,
                "Máte za sebou " + known.evaluations() + " vyhodnocení (bez penalizace " + known.freeEvaluations() + ").\n"
                    + "Každé další vyhodnocení snižuje známku o " + known.reductionByEvaluation() + ".\n\nPokračovat?",
                "Vyhodnocení se penalizuje", "Vyhodnotit", "Zrušit", Messages.getWarningIcon());
            if (answer != Messages.YES) return;
        }
        if (!busy.compareAndSet(false, true)) return;
        FileDocumentManager.getInstance().saveAllDocuments();
        setStatus("Odevzdávám…", false);

        new Task.Backgroundable(project, evaluate ? "Moodle VPL: ověření úlohy" : "Moodle VPL: odevzdání úlohy", true) {
            private @Nullable VplResult result;
            private boolean saved;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    VplApi api = VplService.getInstance().getApi();
                    indicator.setText("Odesílám soubory do Moodle…");
                    List<VplFile> files = VplProjectFiles.collect(root, task.requestedFiles);
                    if (files.isEmpty()) {
                        setStatus("Ve složce úlohy nejsou žádné soubory k odevzdání.", true);
                        return;
                    }
                    VplSaveResult save = api.save(task.cmid, files, "", task.version);
                    if (!save.saved()) {
                        if (!confirmOverwrite(save.question())) {
                            setStatus("Odevzdání zrušeno: v Moodle je novější verze. Stáhněte ji tlačítkem „Stáhnout z Moodle“.", true);
                            return;
                        }
                        save = api.save(task.cmid, files, "", -1);
                    }
                    task.version = save.version();
                    task.write(root);
                    saved = true;
                    if (!evaluate) {
                        setStatus("Odevzdáno (" + files.size() + " " + souboru(files.size()) + ").", false);
                        return;
                    }
                    indicator.checkCanceled();
                    result = evaluate(api, task, indicator);
                    if (result != null) {
                        lastResult = result;
                        setStatus(null, false);
                    }
                }
                catch (IOException | MoodleException e) {
                    LOG.info("VPL submit failed", e);
                    setStatus((saved ? "Odevzdáno, ale vyhodnocení selhalo: " : "Odevzdání selhalo: ") + errorMessage(e), true);
                }
            }

            @Override
            public void onSuccess() {
                if (evaluate && result != null) {
                    showNotification("Úloha vyhodnocena", summary(result), NotificationType.INFORMATION);
                }
                else if (saved && !evaluate) {
                    showNotification("Úloha odevzdána", task.name, NotificationType.INFORMATION);
                }
                else if (statusIsError && status != null) {
                    showNotification(saved ? "Úlohu se nepodařilo vyhodnotit" : "Úlohu se nepodařilo odevzdat", status, NotificationType.ERROR);
                }
            }

            @Override
            public void onCancel() {
                setStatus(saved ? "Odevzdáno, vyhodnocení bylo zrušeno." : "Zrušeno.", false);
            }

            @Override
            public void onFinished() {
                finish();
            }
        }.queue();
    }

    private @Nullable VplResult evaluate(@NotNull VplApi api, @NotNull VplTaskMetadata task, @NotNull ProgressIndicator indicator)
        throws IOException, MoodleException {
        indicator.setText("Spouštím vyhodnocení…");
        setStatus("Vyhodnocuji…", false);
        VplExecution execution = api.evaluate(task.cmid);
        VplMonitor.Outcome outcome = VplMonitor.await(execution.monitorUri(), EVALUATION_TIMEOUT,
            text -> {
                indicator.setText2(text);
                setStatus(text, false);
            },
            indicator::isCanceled);
        switch (outcome) {
            case RETRIEVE -> {
                indicator.setText("Stahuji výsledek…");
                return api.retrieve(task.cmid, execution.processId());
            }
            case CANCELLED -> {
                try {
                    api.cancel(task.cmid, execution.processId());
                }
                catch (IOException | MoodleException e) {
                    LOG.info("Could not cancel VPL evaluation", e);
                }
                indicator.checkCanceled();
                return null;
            }
            default -> throw new IOException("Vyhodnocovací server ukončil spojení bez výsledku.");
        }
    }

    /** Replaces the local files with the last submission from Moodle (or the initial files if there is none). */
    public void downloadLatest() {
        VplTaskMetadata task = getMetadata();
        Path root = projectRoot();
        if (task == null || root == null || !checkSite(task, true)) return;
        int answer = Messages.showYesNoDialog(project,
            "Soubory úlohy se přepíší posledním odevzdáním z Moodle (soubory, které v Moodle nejsou, zůstanou).\n"
                + "Neodevzdané změny v přepsaných souborech se ztratí.",
            "Stáhnout z Moodle", "Stáhnout", "Zrušit", Messages.getWarningIcon());
        if (answer != Messages.YES || !busy.compareAndSet(false, true)) return;
        FileDocumentManager.getInstance().saveAllDocuments();
        setStatus("Stahuji z Moodle…", false);

        new Task.Backgroundable(project, "Moodle VPL: stahování úlohy", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    VplSubmission submission = VplService.getInstance().getApi().load(task.cmid);
                    VplProjectFiles.writeFiles(root, submission.files());
                    task.version = submission.version();
                    task.write(root);
                    lastResult = submission.result();
                    timeLeft = submission.timeLeft();
                    setStatus("Staženo " + submission.files().size() + " " + souboru(submission.files().size())
                        + (submission.version() > 0 ? " z posledního odevzdání." : " (úloha zatím nebyla odevzdána)."), false);
                    VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
                    if (dir != null) {
                        dir.refresh(false, true);
                    }
                }
                catch (IOException | MoodleException e) {
                    setStatus("Stažení selhalo: " + errorMessage(e), true);
                }
            }

            @Override
            public void onFinished() {
                finish();
            }
        }.queue();
    }

    /** Tasks are bound to a site; refuse to submit to another one configured in the meantime. */
    private boolean checkSite(@NotNull VplTaskMetadata task, boolean report) {
        MoodleSessionService moodle = MoodleSessionService.getInstance();
        if (moodle.getClient() == null) {
            if (report) setStatus("Nejste přihlášeni do Moodle. Přihlaste se na kartě Účet.", true);
            return false;
        }
        if (!SiteUrls.normalize(task.siteUrl).equals(moodle.getClient().getSiteUrl())) {
            if (report) setStatus("Úloha patří k webu " + task.siteUrl + ", ale plugin je přihlášen k " + moodle.getClient().getSiteUrl() + ".", true);
            return false;
        }
        return true;
    }

    private boolean confirmOverwrite(@Nullable String question) {
        AtomicBoolean confirmed = new AtomicBoolean();
        ApplicationManager.getApplication().invokeAndWait(() -> confirmed.set(Messages.showYesNoDialog(project,
            (question != null ? question : "V Moodle je novější odevzdání, než ze kterého vychází váš projekt.")
                + "\n\nOdevzdat přesto soubory z IntelliJ?",
            "Novější odevzdání v Moodle", "Odevzdat", "Zrušit", Messages.getWarningIcon()) == Messages.YES),
            ModalityState.defaultModalityState());
        return confirmed.get();
    }

    private @Nullable Path projectRoot() {
        String basePath = project.getBasePath();
        return basePath != null ? Path.of(basePath) : null;
    }

    private void finish() {
        busy.set(false);
        fireChanged();
    }

    private void setStatus(@Nullable String text, boolean isError) {
        status = text;
        statusIsError = isError;
        fireChanged();
    }

    private void fireChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(VplTaskListener.TOPIC).taskChanged();
            }
        }, ModalityState.any());
    }

    private void showNotification(@NotNull String title, @NotNull String content, @NotNull NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("Moodle VŠE")
            .createNotification(title, escape(content), type)
            .notify(project);
    }

    static @NotNull String summary(@NotNull VplResult result) {
        if (!result.grade().isEmpty()) return result.grade();
        if (!result.compilation().isEmpty()) return "Překlad skončil chybou nebo varováním – podrobnosti v okně Moodle.";
        return "Výsledek je v okně Moodle.";
    }

    static @NotNull String souboru(int count) {
        if (count == 1) return "soubor";
        if (count >= 2 && count <= 4) return "soubory";
        return "souborů";
    }

    static @NotNull String errorMessage(@NotNull Exception e) {
        return e.getMessage() != null ? e.getMessage() : "Chyba spojení (" + e.getClass().getSimpleName() + ").";
    }

    private static @NotNull String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
