package cz.vse.moodle.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.util.ui.JBUI;
import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.session.MoodleSessionListener;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.session.MoodleSessionState;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.settings.MoodleSettingsListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Course combo box with a refresh button for the Úlohy and Trénink tabs. Loads the VPL activities of the selected
 * course in the background and reloads after login/logout and when the course settings change.
 */
final class CourseSelector implements Disposable {
    /** Result of loading a course; {@code error} is a message for the user. */
    record Loaded(long courseId, @Nullable String courseName, @NotNull List<CourseModule> modules, @Nullable String error) {
    }

    interface Listener {
        /** Loading started, or can't start (not logged in, no courses): clear the list and show the message. */
        void started(@NotNull String message);

        void loaded(@NotNull Loaded result);
    }

    private record CourseItem(long id, @Nullable String name) {
        @Override
        public String toString() {
            return name != null ? name : "Kurz " + id;
        }
    }

    /** Shared by all tabs and windows, so a course name is fetched once. */
    private static final Map<Long, String> COURSE_NAMES = new ConcurrentHashMap<>();

    private final Project project;
    private final Listener listener;
    private final ComboBox<CourseItem> combo = new ComboBox<>();
    private final JPanel component = new JPanel(new BorderLayout(JBUI.scale(6), 0));
    private final AtomicLong generation = new AtomicLong();
    private boolean updatingCombo;

    /**
     * @param extras components shown between the combo box and the refresh button (e.g. filters)
     */
    CourseSelector(@NotNull Project project, @NotNull Listener listener, @NotNull JComponent... extras) {
        this.project = project;
        this.listener = listener;

        JButton refresh = new JButton(AllIcons.Actions.Refresh);
        refresh.setToolTipText("Načíst znovu");
        refresh.addActionListener(e -> reload());
        combo.addActionListener(e -> {
            if (!updatingCombo) reload();
        });
        component.add(combo, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        for (JComponent extra : extras) right.add(extra);
        right.add(refresh);
        component.add(right, BorderLayout.EAST);
        component.setBorder(JBUI.Borders.empty(8, 8, 4, 8));

        var bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(MoodleSessionListener.TOPIC, (MoodleSessionListener) state -> {
            if (state.status() == MoodleSessionState.Status.LOGGED_IN || state.status() == MoodleSessionState.Status.LOGGED_OUT) {
                reload();
            }
        });
        bus.subscribe(MoodleSettingsListener.TOPIC, (MoodleSettingsListener) this::fillCourses);
    }

    @NotNull JComponent getComponent() {
        return component;
    }

    /** Fills the combo box from the settings and loads the selected course. Call once the listener is ready. */
    void fillCourses() {
        updatingCombo = true;
        try {
            CourseItem selected = (CourseItem) combo.getSelectedItem();
            combo.removeAllItems();
            for (long id : MoodleSettings.getInstance().getCourseIds()) {
                combo.addItem(new CourseItem(id, COURSE_NAMES.get(id)));
            }
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (selected != null && combo.getItemAt(i).id() == selected.id()) combo.setSelectedIndex(i);
            }
            combo.setVisible(combo.getItemCount() > 0);
        }
        finally {
            updatingCombo = false;
        }
        reload();
    }

    void reload() {
        long gen = generation.incrementAndGet();
        CourseItem course = (CourseItem) combo.getSelectedItem();
        MoodleClient client = MoodleSessionService.getInstance().getClient();
        if (course == null) {
            listener.started("Nastavte kurzy v Settings → Tools → Moodle VŠE.");
            return;
        }
        if (client == null) {
            listener.started("Pro zobrazení úloh se přihlaste na kartě Student.");
            return;
        }
        listener.started("Načítám úlohy…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CourseModule> modules;
            String name;
            String error = null;
            try {
                name = COURSE_NAMES.containsKey(course.id()) ? COURSE_NAMES.get(course.id()) : client.getCourseName(course.id());
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
            Loaded result = new Loaded(course.id(), name, modules, error);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (generation.get() != gen) return;
                if (result.courseName() != null && !result.courseName().equals(COURSE_NAMES.get(course.id()))) {
                    COURSE_NAMES.put(course.id(), result.courseName());
                }
                renameCourse(course.id(), COURSE_NAMES.get(course.id()));
                listener.loaded(result);
            }, ModalityState.any(), o -> project.isDisposed());
        });
    }

    /** Selected course id and its name (null until loaded), or null when no course is configured. */
    @Nullable Long selectedCourseId() {
        CourseItem course = (CourseItem) combo.getSelectedItem();
        return course != null ? course.id() : null;
    }

    @Nullable String selectedCourseName() {
        CourseItem course = (CourseItem) combo.getSelectedItem();
        return course != null ? COURSE_NAMES.get(course.id()) : null;
    }

    private void renameCourse(long id, @Nullable String name) {
        if (name == null) return;
        updatingCombo = true;
        try {
            for (int i = 0; i < combo.getItemCount(); i++) {
                CourseItem item = combo.getItemAt(i);
                if (item.id() == id && !name.equals(item.name())) {
                    boolean selected = combo.getSelectedIndex() == i;
                    combo.removeItemAt(i);
                    combo.insertItemAt(new CourseItem(id, name), i);
                    if (selected) combo.setSelectedIndex(i);
                }
            }
        }
        finally {
            updatingCombo = false;
        }
    }

    @Override
    public void dispose() {
        generation.incrementAndGet();
    }
}
