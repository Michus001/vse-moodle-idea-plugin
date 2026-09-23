package cz.vse.moodle.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
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
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.session.MoodleSessionListener;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.session.MoodleSessionState;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.settings.MoodleSettingsListener;
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
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** "Úlohy" tab: VPL activities of the configured courses. */
final class VplAssignmentsPanel extends JPanel implements Disposable {
    private record CourseItem(long id, @Nullable String name) {
        @Override
        public String toString() {
            return name != null ? name : "Kurz " + id;
        }
    }

    private final Project project;
    private final ComboBox<CourseItem> courseCombo = new ComboBox<>();
    private final JBCheckBox onlyOpen = new JBCheckBox("Jen otevřené", true);
    private final DefaultListModel<CourseModule> model = new DefaultListModel<>();
    private final JBList<CourseModule> list = new JBList<>(model);
    private final JBLabel status = new JBLabel();
    private final JButton openButton = new JButton("Otevřít v IntelliJ");
    private final JButton browserButton = new JButton("Zobrazit v Moodle");
    private final Map<Long, String> courseNames = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private List<CourseModule> loaded = List.of();
    private @Nullable String loadError;
    private boolean updatingCombo;

    VplAssignmentsPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        JButton refresh = new JButton(AllIcons.Actions.Refresh);
        refresh.setToolTipText("Načíst znovu");
        refresh.addActionListener(e -> reload());
        courseCombo.addActionListener(e -> {
            if (!updatingCombo) reload();
        });
        onlyOpen.addActionListener(e -> showModules());
        JPanel top = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        top.add(courseCombo, BorderLayout.CENTER);
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        topRight.add(onlyOpen);
        topRight.add(refresh);
        top.add(topRight, BorderLayout.EAST);
        top.setBorder(JBUI.Borders.empty(8, 8, 4, 8));
        add(top, BorderLayout.NORTH);

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

        var bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(MoodleSessionListener.TOPIC, (MoodleSessionListener) state -> {
            if (state.status() == MoodleSessionState.Status.LOGGED_IN || state.status() == MoodleSessionState.Status.LOGGED_OUT) {
                reload();
            }
        });
        bus.subscribe(MoodleSettingsListener.TOPIC, (MoodleSettingsListener) this::fillCourses);

        fillCourses();
    }

    private void fillCourses() {
        updatingCombo = true;
        try {
            CourseItem selected = (CourseItem) courseCombo.getSelectedItem();
            courseCombo.removeAllItems();
            for (long id : MoodleSettings.getInstance().getCourseIds()) {
                courseCombo.addItem(new CourseItem(id, courseNames.get(id)));
            }
            for (int i = 0; i < courseCombo.getItemCount(); i++) {
                if (selected != null && courseCombo.getItemAt(i).id() == selected.id()) courseCombo.setSelectedIndex(i);
            }
            courseCombo.setVisible(courseCombo.getItemCount() > 0);
        }
        finally {
            updatingCombo = false;
        }
        reload();
    }

    private void reload() {
        long gen = generation.incrementAndGet();
        CourseItem course = (CourseItem) courseCombo.getSelectedItem();
        MoodleClient client = MoodleSessionService.getInstance().getClient();
        loaded = List.of();
        loadError = null;
        model.clear();
        updateButtons();
        if (course == null) {
            showStatus("Nastavte kurzy v Settings → Tools → Moodle VŠE.", false);
            return;
        }
        if (client == null) {
            showStatus("Pro zobrazení úloh se přihlaste na kartě Účet.", false);
            return;
        }
        showStatus("Načítám úlohy…", false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CourseModule> modules;
            String name;
            String error = null;
            try {
                name = courseNames.containsKey(course.id()) ? courseNames.get(course.id()) : client.getCourseName(course.id());
                modules = client.getCourseModules(course.id(), "vpl");
            }
            catch (MoodleException e) {
                name = null;
                modules = List.of();
                error = e.getErrorCode().equals("errorcoursecontextnotvalid") || e.getErrorCode().equals("requireloginerror")
                    ? "K tomuto kurzu nemáte přístup (nejste do něj zapsáni?)." : "Moodle vrátil chybu: " + e.getMessage();
            }
            catch (IOException e) {
                name = null;
                modules = List.of();
                error = "Nelze načíst úlohy: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String courseName = name;
            List<CourseModule> result = modules;
            String failure = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (generation.get() != gen) return;
                if (courseName != null && !courseName.equals(courseNames.get(course.id()))) {
                    courseNames.put(course.id(), courseName);
                    renameCourse(course.id(), courseName);
                }
                loaded = result;
                loadError = failure;
                showModules();
            }, ModalityState.any(), o -> project.isDisposed());
        });
    }

    private void renameCourse(long id, @NotNull String name) {
        updatingCombo = true;
        try {
            for (int i = 0; i < courseCombo.getItemCount(); i++) {
                if (courseCombo.getItemAt(i).id() == id) {
                    boolean selected = courseCombo.getSelectedIndex() == i;
                    courseCombo.removeItemAt(i);
                    courseCombo.insertItemAt(new CourseItem(id, name), i);
                    if (selected) courseCombo.setSelectedIndex(i);
                }
            }
        }
        finally {
            updatingCombo = false;
        }
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
        if (loadError != null) {
            showStatus(loadError, true);
        }
        else if (loaded.isEmpty()) {
            showStatus("V kurzu nejsou žádné úlohy VPL.", false);
        }
        else {
            showStatus(hidden > 0 ? "Skryto " + hidden + " uzavřených nebo nedostupných úloh." : "", false);
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
        CourseItem course = (CourseItem) courseCombo.getSelectedItem();
        if (selected == null || course == null || !selected.userVisible()) return;
        VplTaskOpener.open(project, course.id(), courseNames.get(course.id()), selected);
    }

    private static @NotNull String activityUrl(@NotNull CourseModule module) {
        return module.url() != null ? module.url()
            : MoodleSettings.getInstance().getSiteUrl() + "/mod/vpl/view.php?id=" + module.id();
    }

    @Override
    public void dispose() {
        generation.incrementAndGet();
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
                append("  · " + module.sectionName(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            setToolTipText(module.availabilityInfo() != null ? "<html>" + module.availabilityInfo() + "</html>" : null);
        }
    }
}
