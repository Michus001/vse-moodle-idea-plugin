package cz.vse.moodle.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import cz.vse.moodle.api.SiteUrls;
import cz.vse.moodle.session.MoodleSessionService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Settings | Tools | Moodle VŠE */
public final class MoodleSettingsConfigurable implements Configurable {
    private JBTextField siteUrlField;
    private JBTextField courseIdsField;
    private TextFieldWithBrowseButton projectsDirField;
    private JPanel panel;

    @Override
    public @Nls String getDisplayName() {
        return "Moodle VŠE";
    }

    @Override
    public @Nullable JComponent createComponent() {
        siteUrlField = new JBTextField();
        siteUrlField.getEmptyText().setText(MoodleSettings.DEFAULT_SITE_URL);
        courseIdsField = new JBTextField();
        courseIdsField.getEmptyText().setText(MoodleSettings.DEFAULT_COURSE_IDS);
        projectsDirField = new TextFieldWithBrowseButton();
        projectsDirField.addBrowseFolderListener(null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Složka pro úlohy VPL"));
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Adresa Moodle:", siteUrlField)
            .addComponentToRightColumn(hint("Po změně adresy budete přihlášeni k novému webu (pokud k němu máte uložený token)."))
            .addLabeledComponent("Kurzy s úlohami VPL:", courseIdsField)
            .addComponentToRightColumn(hint("ID kurzů oddělená čárkou (číslo za course/view.php?id=) nebo celé adresy kurzů."))
            .addLabeledComponent("Složka pro úlohy:", projectsDirField)
            .addComponentToRightColumn(hint("Sem se ukládají projekty stažených úloh (kurz/úloha)."))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        reset();
        return panel;
    }

    private static @NotNull JBLabel hint(@NotNull String text) {
        JBLabel hint = new JBLabel(text);
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setForeground(UIUtil.getContextHelpForeground());
        return hint;
    }

    @Override
    public boolean isModified() {
        MoodleSettings settings = MoodleSettings.getInstance();
        return !enteredUrl().equals(settings.getSiteUrl())
            || !enteredCourseIds().equals(settings.getCourseIdsText())
            || !enteredProjectsDir().equals(settings.getProjectsDir().toString());
    }

    @Override
    public void apply() throws ConfigurationException {
        String url = enteredUrl();
        String error = SiteUrls.validate(url);
        if (error != null) {
            throw new ConfigurationException(error);
        }
        List<Long> courseIds = MoodleSettings.parseCourseIds(enteredCourseIds());
        if (courseIds == null) {
            throw new ConfigurationException("Kurzy zadejte jako čísla oddělená čárkou, např. " + MoodleSettings.DEFAULT_COURSE_IDS + ".");
        }
        String projectsDir = enteredProjectsDir();
        try {
            if (!Path.of(projectsDir).isAbsolute()) {
                throw new ConfigurationException("Složka pro úlohy musí být absolutní cesta.");
            }
        }
        catch (InvalidPathException e) {
            throw new ConfigurationException("Neplatná složka pro úlohy: " + e.getReason());
        }

        MoodleSettings settings = MoodleSettings.getInstance();
        boolean coursesChanged = !enteredCourseIds().equals(settings.getCourseIdsText());
        settings.setCourseIdsText(enteredCourseIds());
        settings.setProjectsDir(projectsDir);
        if (!url.equals(settings.getSiteUrl())) {
            settings.setSiteUrl(url);
            MoodleSessionService.getInstance().siteUrlChanged();
        }
        else if (coursesChanged) {
            ApplicationManager.getApplication().getMessageBus().syncPublisher(MoodleSettingsListener.TOPIC).coursesChanged();
        }
    }

    @Override
    public void reset() {
        if (siteUrlField == null) return;
        MoodleSettings settings = MoodleSettings.getInstance();
        siteUrlField.setText(settings.getSiteUrl());
        courseIdsField.setText(settings.getCourseIdsText());
        projectsDirField.setText(settings.getProjectsDir().toString());
    }

    @Override
    public void disposeUIResources() {
        siteUrlField = null;
        courseIdsField = null;
        projectsDirField = null;
        panel = null;
    }

    private @NotNull String enteredUrl() {
        String text = siteUrlField != null ? siteUrlField.getText() : "";
        return text.isBlank() ? MoodleSettings.DEFAULT_SITE_URL : SiteUrls.normalize(text);
    }

    private @NotNull String enteredCourseIds() {
        String text = courseIdsField != null ? courseIdsField.getText().trim() : "";
        return text.isEmpty() ? MoodleSettings.DEFAULT_COURSE_IDS : text;
    }

    private @NotNull String enteredProjectsDir() {
        String text = projectsDirField != null ? projectsDirField.getText().trim() : "";
        return text.isEmpty() ? MoodleSettings.defaultProjectsDir().toString() : text;
    }
}
