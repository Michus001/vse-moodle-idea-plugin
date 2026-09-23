package cz.vse.moodle.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
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

/** Settings | Tools | Moodle VŠE */
public final class MoodleSettingsConfigurable implements Configurable {
    private JBTextField siteUrlField;
    private JPanel panel;

    @Override
    public @Nls String getDisplayName() {
        return "Moodle VŠE";
    }

    @Override
    public @Nullable JComponent createComponent() {
        siteUrlField = new JBTextField();
        siteUrlField.getEmptyText().setText(MoodleSettings.DEFAULT_SITE_URL);
        JBLabel hint = new JBLabel("Po změně adresy budete přihlášeni k novému webu (pokud k němu máte uložený token).");
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setForeground(UIUtil.getContextHelpForeground());
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Adresa Moodle:", siteUrlField)
            .addComponentToRightColumn(hint)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return !enteredUrl().equals(MoodleSettings.getInstance().getSiteUrl());
    }

    @Override
    public void apply() throws ConfigurationException {
        String url = enteredUrl();
        String error = SiteUrls.validate(url);
        if (error != null) {
            throw new ConfigurationException(error);
        }
        MoodleSettings settings = MoodleSettings.getInstance();
        if (!url.equals(settings.getSiteUrl())) {
            settings.setSiteUrl(url);
            MoodleSessionService.getInstance().siteUrlChanged();
        }
    }

    @Override
    public void reset() {
        if (siteUrlField != null) {
            siteUrlField.setText(MoodleSettings.getInstance().getSiteUrl());
        }
    }

    @Override
    public void disposeUIResources() {
        siteUrlField = null;
        panel = null;
    }

    private @NotNull String enteredUrl() {
        String text = siteUrlField != null ? siteUrlField.getText() : "";
        return text.isBlank() ? MoodleSettings.DEFAULT_SITE_URL : SiteUrls.normalize(text);
    }
}
