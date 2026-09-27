package cz.vse.moodle.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.training.TrainingProgress;
import cz.vse.moodle.training.TrainingProgressListener;
import cz.vse.moodle.training.TrainingStatus;
import cz.vse.moodle.training.TrainingTasks;
import cz.vse.moodle.vpl.VplTaskOpener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Trénink" tab: practice tasks from the course's training section, grouped by topic (subsection), with the progress
 * recorded locally by {@link TrainingProgress}.
 */
final class TrainingPanel extends JPanel implements Disposable {
    private static final String ALL_DIFFICULTIES = "Všechny obtížnosti";

    /** Tree node of a subsection; the counts are over all its tasks, not only the filtered ones. */
    private record Topic(@NotNull String name, int solved, int total) {
    }

    private final Project project;
    private final CourseSelector courses;
    private final SearchTextField search = new SearchTextField(false);
    private final ComboBox<String> difficultyCombo = new ComboBox<>();
    private final JBCheckBox hideSolved = new JBCheckBox("Skrýt vyřešené");
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final Tree tree = new Tree(treeModel);
    private final JBLabel status = new JBLabel();
    private final JButton openButton = new JButton("Otevřít v IntelliJ");
    private final JButton nextButton = new JButton("Další úloha k procvičení");
    private final JButton browserButton = new JButton("Zobrazit v Moodle");
    /** Topics the student collapsed; others are expanded after every rebuild. */
    private final Set<String> collapsed = new HashSet<>();
    private final List<Integer> difficulties = new ArrayList<>();
    private List<CourseModule> tasks = List.of();
    /** Sections (and subsections) of all VPL activities of the course, for the "no training section" hint. */
    private List<String> otherSections = List.of();
    private @Nullable String loadError;
    /** Shown instead of the summary until the course is loaded ("Načítám úlohy…", "Přihlaste se…"). */
    private @Nullable String pendingMessage;
    private boolean rebuilding;

    TrainingPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        courses = new CourseSelector(project, new CourseSelector.Listener() {
            @Override
            public void started(@NotNull String message) {
                tasks = List.of();
                loadError = null;
                pendingMessage = message;
                rebuild();
            }

            @Override
            public void loaded(@NotNull CourseSelector.Loaded result) {
                List<CourseModule> training = new ArrayList<>();
                for (CourseModule module : result.modules()) {
                    if (TrainingTasks.isTraining(module)) training.add(module);
                }
                tasks = training;
                // Named in the status when no training section is found, to spot a differently named one.
                Set<String> sections = new LinkedHashSet<>();
                for (CourseModule module : result.modules()) {
                    sections.add(module.subsectionName() != null ? module.sectionName() + " / " + module.subsectionName() : module.sectionName());
                }
                otherSections = new ArrayList<>(sections);
                loadError = result.error();
                pendingMessage = null;
                fillDifficulties();
                rebuild();
            }
        });
        Disposer.register(this, courses);

        search.getTextEditor().getEmptyText().setText("Hledat úlohu nebo téma");
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                rebuild();
            }
        });
        difficultyCombo.addActionListener(e -> {
            if (!rebuilding) rebuild();
        });
        hideSolved.addActionListener(e -> rebuild());
        JPanel filters = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        filters.add(search, BorderLayout.CENTER);
        JPanel filtersRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        filtersRight.add(difficultyCombo);
        filtersRight.add(hideSolved);
        filters.add(filtersRight, BorderLayout.EAST);
        filters.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(courses.getComponent());
        top.add(filters);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new TrainingRenderer());
        tree.getEmptyText().setText("Žádné tréninkové úlohy");
        tree.addTreeSelectionListener(e -> updateButtons());
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (!rebuilding && topicOf(event.getPath()) instanceof Topic topic) collapsed.remove(topic.name());
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (!rebuilding && topicOf(event.getPath()) instanceof Topic topic) collapsed.add(topic.name());
            }
        });
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                if (selectedTask() == null) return false;
                openSelected();
                return true;
            }
        }.installOn(tree);
        JBScrollPane scroll = new JBScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        openButton.addActionListener(e -> openSelected());
        nextButton.setToolTipText("Otevře nejlehčí nevyřešenou úlohu z vybraného tématu (bez výběru ze všech témat).");
        nextButton.addActionListener(e -> openNext());
        browserButton.addActionListener(e -> {
            CourseModule selected = selectedTask();
            if (selected != null) BrowserUtil.browse(VplAssignmentsPanel.activityUrl(selected));
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        buttons.add(openButton);
        buttons.add(nextButton);
        buttons.add(browserButton);
        status.setAllowAutoWrapping(true);
        status.setForeground(UIUtil.getContextHelpForeground());
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        add(bottom, BorderLayout.SOUTH);

        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe(TrainingProgressListener.TOPIC, (TrainingProgressListener) this::rebuild);

        courses.fillCourses();
    }

    private void fillDifficulties() {
        String selected = (String) difficultyCombo.getSelectedItem();
        Set<Integer> present = new TreeSet<>();
        for (CourseModule task : tasks) present.add(TrainingTasks.difficulty(task.name()));
        rebuilding = true;
        try {
            difficulties.clear();
            difficultyCombo.removeAllItems();
            difficultyCombo.addItem(ALL_DIFFICULTIES);
            for (int difficulty : present) {
                difficulties.add(difficulty);
                difficultyCombo.addItem(difficultyLabel(difficulty));
            }
            difficultyCombo.setSelectedItem(selected != null ? selected : ALL_DIFFICULTIES);
            if (difficultyCombo.getSelectedIndex() < 0) difficultyCombo.setSelectedIndex(0);
            difficultyCombo.setVisible(present.size() > 1);
        }
        finally {
            rebuilding = false;
        }
    }

    private static @NotNull String difficultyLabel(int difficulty) {
        return difficulty == 0 ? "Bez obtížnosti" : TrainingTasks.stars(difficulty);
    }

    /** Rebuilds the tree from {@link #tasks}, the filters and the recorded progress, keeping selection and expansion. */
    private void rebuild() {
        Object previous = selectedObject();
        rebuilding = true;
        try {
            root.removeAllChildren();
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            int difficultyIndex = difficultyCombo.getSelectedIndex() - 1;
            Integer difficulty = difficultyIndex >= 0 && difficultyIndex < difficulties.size() ? difficulties.get(difficultyIndex) : null;

            // Topics and tasks directly in the section, in course order.
            Map<String, List<CourseModule>> topics = new LinkedHashMap<>();
            List<Object> order = new ArrayList<>();
            for (CourseModule task : tasks) {
                String topic = TrainingTasks.topic(task);
                if (topic == null) {
                    order.add(task);
                }
                else {
                    if (!topics.containsKey(topic)) order.add(topic);
                    topics.computeIfAbsent(topic, k -> new ArrayList<>()).add(task);
                }
            }
            for (Object item : order) {
                if (item instanceof CourseModule task) {
                    if (matches(task, null, query, difficulty)) root.add(new DefaultMutableTreeNode(task, false));
                    continue;
                }
                String name = (String) item;
                List<CourseModule> topicTasks = topics.get(name);
                int solved = (int) topicTasks.stream().filter(task -> status(task) == TrainingStatus.SOLVED).count();
                DefaultMutableTreeNode topicNode = new DefaultMutableTreeNode(new Topic(name, solved, topicTasks.size()));
                for (CourseModule task : topicTasks) {
                    if (matches(task, name, query, difficulty)) topicNode.add(new DefaultMutableTreeNode(task, false));
                }
                if (topicNode.getChildCount() > 0) root.add(topicNode);
            }
            treeModel.reload();
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
                if (node.getUserObject() instanceof Topic topic && (!query.isEmpty() || !collapsed.contains(topic.name()))) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
            if (previous != null) select(previous);
        }
        finally {
            rebuilding = false;
        }
        showSummary();
        updateButtons();
    }

    private boolean matches(@NotNull CourseModule task, @Nullable String topic, @NotNull String query, @Nullable Integer difficulty) {
        if (difficulty != null && TrainingTasks.difficulty(task.name()) != difficulty) return false;
        if (hideSolved.isSelected() && status(task) == TrainingStatus.SOLVED) return false;
        return query.isEmpty()
            || task.name().toLowerCase(Locale.ROOT).contains(query)
            || (topic != null && topic.toLowerCase(Locale.ROOT).contains(query));
    }

    private void showSummary() {
        if (loadError != null) {
            showStatus(loadError, true);
            return;
        }
        if (pendingMessage != null) {
            showStatus(pendingMessage, false);
            return;
        }
        if (tasks.isEmpty()) {
            showStatus("V kurzu není sekce „" + MoodleSettings.getInstance().getTrainingSection()
                + "“ s úlohami VPL. Název sekce lze změnit v Settings → Tools → Moodle VŠE."
                + (otherSections.isEmpty() ? "" : " Úlohy VPL jsou v sekcích: " + String.join(", ", otherSections) + "."), false);
            return;
        }
        long solved = tasks.stream().filter(task -> status(task) == TrainingStatus.SOLVED).count();
        showStatus("Vyřešeno " + solved + " z " + tasks.size() + " tréninkových úloh.", false);
    }

    private @NotNull TrainingProgress.TaskProgress progress(@NotNull CourseModule task) {
        return TrainingProgress.getInstance().get(MoodleSettings.getInstance().getSiteUrl(), task.id());
    }

    private @NotNull TrainingStatus status(@NotNull CourseModule task) {
        return progress(task).status();
    }

    private void showStatus(@NotNull String text, boolean isError) {
        status.setText(text.isEmpty() ? "" : "<html>" + UiFormat.escape(text) + "</html>");
        status.setForeground(isError ? JBColor.RED : UIUtil.getContextHelpForeground());
    }

    private void updateButtons() {
        CourseModule selected = selectedTask();
        openButton.setEnabled(selected != null && selected.userVisible());
        browserButton.setEnabled(selected != null);
        nextButton.setEnabled(!tasks.isEmpty());
    }

    private @Nullable Object selectedObject() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
    }

    private @Nullable CourseModule selectedTask() {
        return selectedObject() instanceof CourseModule task ? task : null;
    }

    /** Topic of the selection: the selected topic, or the topic of the selected task; null for no selection. */
    private @Nullable String selectedTopic() {
        Object selected = selectedObject();
        if (selected instanceof Topic topic) return topic.name();
        if (selected instanceof CourseModule task) return TrainingTasks.topic(task);
        return null;
    }

    private static @Nullable Object topicOf(@NotNull TreePath path) {
        return path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
    }

    /** Selects the node of a task (by id) or a topic (by name); returns false when it's filtered out. */
    private boolean select(@NotNull Object target) {
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            Object value = node.getUserObject();
            boolean same = target instanceof CourseModule wantedTask && value instanceof CourseModule task ? task.id() == wantedTask.id()
                : target instanceof Topic wantedTopic && value instanceof Topic topic && topic.name().equals(wantedTopic.name());
            if (same) {
                TreePath path = new TreePath(node.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return true;
            }
        }
        return false;
    }

    private void openSelected() {
        CourseModule selected = selectedTask();
        if (selected != null) open(selected);
    }

    private void open(@NotNull CourseModule task) {
        Long courseId = courses.selectedCourseId();
        if (courseId == null || !task.userVisible()) return;
        VplTaskOpener.open(project, courseId, courses.selectedCourseName(), task);
    }

    /** "Další úloha k procvičení": the easiest unsolved task of the selected topic, else of the whole section. */
    private void openNext() {
        String topic = selectedTopic();
        CourseModule current = selectedTask();
        Long currentId = current != null ? current.id() : null;
        CourseModule next = null;
        String note = null;
        if (topic != null) {
            List<CourseModule> topicTasks = tasks.stream().filter(task -> topic.equals(TrainingTasks.topic(task))).toList();
            next = TrainingTasks.nextTask(topicTasks, this::status, currentId);
            if (next == null) note = "Téma „" + topic + "“ máte vyřešené, pokračujte dalším tématem.";
        }
        if (next == null) {
            next = TrainingTasks.nextTask(tasks, this::status, currentId);
        }
        if (next == null) {
            showStatus("Všechny tréninkové úlohy máte vyřešené.", false);
            return;
        }
        if (!select(next)) {
            // Hidden by the filters: show everything so the student sees what was opened.
            rebuilding = true;
            try {
                search.setText("");
                difficultyCombo.setSelectedIndex(0);
                hideSolved.setSelected(false);
            }
            finally {
                rebuilding = false;
            }
            rebuild();
            select(next);
        }
        if (note != null) showStatus(note, false);
        open(next);
    }

    @Override
    public void dispose() {
    }

    private final class TrainingRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            Object object = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (object instanceof Topic topic) {
                setIcon(AllIcons.Nodes.Folder);
                append(topic.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + topic.solved() + "/" + topic.total(), topic.solved() == topic.total()
                    ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Label.successForeground", JBColor.GREEN.darker()))
                    : SimpleTextAttributes.GRAYED_ATTRIBUTES);
                setToolTipText("Vyřešeno " + topic.solved() + " z " + topic.total() + " úloh");
                return;
            }
            if (!(object instanceof CourseModule task)) return;
            TrainingProgress.TaskProgress progress = progress(task);
            setIcon(switch (progress.status()) {
                case SOLVED -> AllIcons.RunConfigurations.TestPassed;
                case STARTED -> AllIcons.Actions.Edit;
                case NEW -> AllIcons.FileTypes.Java;
            });
            int difficulty = TrainingTasks.difficulty(task.name());
            if (difficulty > 0) {
                append(TrainingTasks.stars(difficulty) + " ", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                    JBColor.namedColor("Label.warningForeground", new JBColor(0xC77C02, 0xE0A94A))));
            }
            append(TrainingTasks.title(task.name()), task.userVisible() ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
            if (!task.userVisible()) {
                append("  nedostupná", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            else if (progress.bestGrade() != null) {
                append("  " + shortGrade(progress.bestGrade()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            StringBuilder tooltip = new StringBuilder("<html>").append(UiFormat.escape(progress.status().label()));
            if (progress.bestGrade() != null) tooltip.append("<br>Nejlepší: ").append(UiFormat.escape(progress.bestGrade()));
            if (task.availabilityInfo() != null) tooltip.append("<br>").append(task.availabilityInfo());
            setToolTipText(tooltip.append("</html>").toString());
        }
    }

    /** "Navrhovaná známka: 8 / 10" → "8 / 10". */
    static @NotNull String shortGrade(@NotNull String grade) {
        int colon = grade.lastIndexOf(':');
        return colon >= 0 && colon < grade.length() - 1 ? grade.substring(colon + 1).strip() : grade;
    }
}
