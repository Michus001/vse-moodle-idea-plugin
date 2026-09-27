package cz.vse.moodle.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.training.TrainingTasks;
import cz.vse.moodle.vpl.VplTaskOpener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** "Úlohy" tab: VPL activities of the configured courses, except the training ones (see {@link TrainingPanel}). */
final class VplAssignmentsPanel extends JPanel implements Disposable {
    private final Project project;
    private final CourseSelector courses;
    private final JBCheckBox onlyOpen = new JBCheckBox("Jen otevřené", true);
    private final DefaultListModel<CourseModule> model = new DefaultListModel<>();
    private final JBList<CourseModule> list = new JBList<>(model);
    private final JBLabel status = new JBLabel();
    private final JButton openButton = new JButton("Otevřít v IntelliJ");
    private final JButton browserButton = new JButton("Zobrazit v Moodle");
    private List<CourseModule> loaded = List.of();
    private int trainingCount;
    private @Nullable String loadError;

    VplAssignmentsPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        onlyOpen.addActionListener(e -> showModules());
        courses = new CourseSelector(project, new CourseSelector.Listener() {
            @Override
            public void started(@NotNull String message) {
                loaded = List.of();
                trainingCount = 0;
                loadError = null;
                model.clear();
                updateButtons();
                showStatus(message, false);
            }

            @Override
            public void loaded(@NotNull CourseSelector.Loaded result) {
                List<CourseModule> assignments = new ArrayList<>();
                for (CourseModule module : result.modules()) {
                    if (!TrainingTasks.isTraining(module)) assignments.add(module);
                }
                loaded = assignments;
                trainingCount = result.modules().size() - assignments.size();
                loadError = result.error();
                showModules();
            }
        }, onlyOpen);
        Disposer.register(this, courses);
        add(courses.getComponent(), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ModuleRenderer());
        list.getEmptyText().setText("Žádné úlohy");
        list.addListSelectionListener(e -> updateButtons());
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                openSelected();
                return true;
            }
        }.installOn(list);
        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        openButton.addActionListener(e -> openSelected());
        browserButton.addActionListener(e -> {
            CourseModule selected = list.getSelectedValue();
            if (selected != null) BrowserUtil.browse(activityUrl(selected));
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.add(openButton);
        buttons.add(browserButton);
        status.setAllowAutoWrapping(true);
        status.setForeground(UIUtil.getContextHelpForeground());
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        add(bottom, BorderLayout.SOUTH);

        courses.fillCourses();
    }

    private void showModules() {
        Instant now = Instant.now();
        List<CourseModule> visible = new ArrayList<>();
        for (CourseModule module : loaded) {
            if (!onlyOpen.isSelected() || module.isOpenAt(now)) visible.add(module);
        }
        model.clear();
        visible.forEach(model::addElement);
        int hidden = loaded.size() - visible.size();
        String training = trainingCount > 0 ? " Tréninkové úlohy (" + trainingCount + ") jsou na kartě Trénink." : "";
        if (loadError != null) {
            showStatus(loadError, true);
        }
        else if (loaded.isEmpty()) {
            showStatus(("V kurzu nejsou žádné úlohy VPL ze cvičení." + training).strip(), false);
        }
        else {
            showStatus(((hidden > 0 ? "Skryto " + hidden + " uzavřených nebo nedostupných úloh." : "") + training).strip(), false);
        }
        updateButtons();
    }

    private void showStatus(@NotNull String text, boolean isError) {
        status.setText(text.isEmpty() ? "" : "<html>" + UiFormat.escape(text) + "</html>");
        status.setForeground(isError ? JBColor.RED : UIUtil.getContextHelpForeground());
    }

    private void updateButtons() {
        CourseModule selected = list.getSelectedValue();
        openButton.setEnabled(selected != null && selected.userVisible());
        browserButton.setEnabled(selected != null);
    }

    private void openSelected() {
        CourseModule selected = list.getSelectedValue();
        Long courseId = courses.selectedCourseId();
        if (selected == null || courseId == null || !selected.userVisible()) return;
        VplTaskOpener.open(project, courseId, courses.selectedCourseName(), selected);
    }

    static @NotNull String activityUrl(@NotNull CourseModule module) {
        return module.url() != null ? module.url()
            : MoodleSettings.getInstance().getSiteUrl() + "/mod/vpl/view.php?id=" + module.id();
    }

    @Override
    public void dispose() {
    }

    private static final class ModuleRenderer extends ColoredListCellRenderer<CourseModule> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends CourseModule> list, CourseModule module, int index,
                                             boolean selected, boolean hasFocus) {
            Instant now = Instant.now();
            boolean open = module.isOpenAt(now);
            setIcon(AllIcons.FileTypes.Java);
            append(module.name(), open ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
            if (!module.userVisible()) {
                append("  nedostupná", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            else if (module.opens() != null && now.isBefore(module.opens())) {
                append("  otevře se " + UiFormat.dateTime(module.opens()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            else if (module.due() != null) {
                Duration left = Duration.between(now, module.due());
                boolean soon = !left.isNegative() && left.toHours() < 24;
                append("  do " + UiFormat.dateTime(module.due()) + " (" + UiFormat.remaining(left) + ")",
                    soon ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED) : SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            if (!module.sectionName().isBlank()) {
                String section = module.subsectionName() != null ? module.sectionName() + " / " + module.subsectionName() : module.sectionName();
                append("  · " + section, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setToolTipText(module.availabilityInfo() != null ? "<html>" + module.availabilityInfo() + "</html>" : null);
        }
    }
}
