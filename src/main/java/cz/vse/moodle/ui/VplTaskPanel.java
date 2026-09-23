package cz.vse.moodle.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import cz.vse.moodle.session.MoodleSessionListener;
import cz.vse.moodle.session.MoodleSessionState;
import cz.vse.moodle.vpl.VplTaskListener;
import cz.vse.moodle.vpl.VplTaskMetadata;
import cz.vse.moodle.vpl.VplTaskService;
import cz.vse.moodle.vpl.api.VplResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.time.Instant;

/** "Úloha" tab of a project created from a VPL activity: submit, evaluate and see the result. */
final class VplTaskPanel extends JPanel implements Disposable {
    private final VplTaskService service;
    private final JPanel header = new JPanel();
    private final JBLabel status = new JBLabel();
    private final JBLabel grade = new JBLabel();
    private final JBTextArea details = new JBTextArea();
    private final JButton evaluateButton = new JButton("Ověřit");
    private final JButton submitButton = new JButton("Odevzdat");
    private final JButton downloadButton = new JButton("Stáhnout z Moodle");

    VplTaskPanel(@NotNull Project project) {
        super(new BorderLayout());
        service = VplTaskService.getInstance(project);

        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        evaluateButton.setToolTipText("Odevzdá soubory projektu do Moodle a nechá je vyhodnotit (VPL vyhodnocuje poslední odevzdání).");
        submitButton.setToolTipText("Odevzdá soubory projektu do Moodle bez vyhodnocení.");
        downloadButton.setToolTipText("Přepíše místní soubory posledním odevzdáním z Moodle.");
        evaluateButton.addActionListener(e -> service.submit(true));
        submitButton.addActionListener(e -> service.submit(false));
        downloadButton.addActionListener(e -> service.downloadLatest());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.add(evaluateButton);
        buttons.add(submitButton);
        buttons.add(downloadButton);

        status.setAllowAutoWrapping(true);
        grade.setFont(JBFont.label().asBold());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(JBUI.Borders.empty(12, 12, 6, 12));
        addLeft(top, header);
        addLeft(top, gap());
        addLeft(top, buttons);
        addLeft(top, gap());
        addLeft(top, status);
        addLeft(top, grade);
        add(top, BorderLayout.NORTH);

        details.setEditable(false);
        details.setFont(JBFont.create(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().getSize())));
        details.setLineWrap(false);
        details.getEmptyText().setText("Výsledek vyhodnocení se zobrazí zde.");
        JBScrollPane scroll = new JBScrollPane(details);
        scroll.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
        add(scroll, BorderLayout.CENTER);

        project.getMessageBus().connect(this).subscribe(VplTaskListener.TOPIC, (VplTaskListener) this::render);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(MoodleSessionListener.TOPIC,
            (MoodleSessionListener) state -> {
                if (state.status() == MoodleSessionState.Status.LOGGED_IN) service.refreshResult();
                render();
            });
        render();
        service.refreshResult();
    }

    private void render() {
        VplTaskMetadata task = service.getMetadata();
        header.removeAll();
        if (task == null) return;

        JBLabel name = new JBLabel(task.name != null ? task.name : "Úloha VPL");
        name.setFont(JBFont.h3().asBold());
        addLeft(header, name);
        if (task.courseName != null) {
            JBLabel course = new JBLabel(task.courseName);
            course.setForeground(UIUtil.getContextHelpForeground());
            addLeft(header, course);
        }
        Instant due = dueDate(task);
        if (due != null) {
            Duration left = Duration.between(Instant.now(), due);
            JBLabel dueLabel = new JBLabel("Termín: " + UiFormat.dateTime(due) + " (" + UiFormat.remaining(left) + ")");
            if (left.toHours() < 24) dueLabel.setForeground(JBColor.RED);
            addLeft(header, dueLabel);
        }
        addLeft(header, new ActionLink("Zadání v Moodle", (ActionListener) e -> BrowserUtil.browse(task.activityUrl())));

        boolean busy = service.isBusy();
        evaluateButton.setEnabled(!busy);
        submitButton.setEnabled(!busy);
        downloadButton.setEnabled(!busy);

        String message = service.getStatus();
        status.setText(message != null ? "<html>" + UiFormat.escape(message) + "</html>" : "");
        status.setIcon(busy ? new AnimatedIcon.Default() : null);
        status.setForeground(service.isStatusError() ? JBColor.RED : UIUtil.getLabelForeground());

        VplResult result = service.getLastResult();
        grade.setText(result != null && !result.grade().isEmpty() ? result.grade() : "");
        String text = result != null ? formatDetails(result) : "";
        if (!text.equals(details.getText())) {
            details.setText(text);
            details.setCaretPosition(0);
        }
        header.revalidate();
        header.repaint();
    }

    /** VPL's time left is more accurate (it includes per-student overrides) than the date stored at download. */
    private Instant dueDate(@NotNull VplTaskMetadata task) {
        Long timeLeft = service.getTimeLeft();
        if (timeLeft != null) return Instant.now().plusSeconds(timeLeft);
        return task.due > 0 ? Instant.ofEpochSecond(task.due) : null;
    }

    static @NotNull String formatDetails(@NotNull VplResult result) {
        StringBuilder text = new StringBuilder();
        section(text, "Překlad", result.compilation());
        section(text, "Hodnocení", result.evaluation());
        section(text, "Výstup programu", result.execution());
        if (result.evaluations() > 0 || result.nextEvaluationIsPenalized()) {
            text.append("Počet vyhodnocení: ").append(result.evaluations());
            if (result.freeEvaluations() > 0) text.append(" (bez penalizace ").append(result.freeEvaluations()).append(")");
            text.append('\n');
        }
        return text.toString();
    }

    private static void section(@NotNull StringBuilder text, @NotNull String title, @NotNull String content) {
        if (content.isBlank()) return;
        text.append("=== ").append(title).append(" ===\n").append(content.strip()).append("\n\n");
    }

    private static @NotNull JComponent gap() {
        JPanel gap = new JPanel();
        gap.setOpaque(false);
        gap.setBorder(JBUI.Borders.emptyTop(8));
        return gap;
    }

    private static void addLeft(@NotNull JPanel parent, @NotNull JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(component);
    }

    @Override
    public void dispose() {
    }
}
