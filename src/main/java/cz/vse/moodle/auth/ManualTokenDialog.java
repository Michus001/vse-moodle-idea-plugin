package cz.vse.moodle.auth;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/** Fallback when JCEF isn't available: the user pastes the token from Moodle's security keys page. */
public final class ManualTokenDialog extends DialogWrapper {
    private final JBPasswordField tokenField = new JBPasswordField();
    private final String siteUrl;

    public ManualTokenDialog(@Nullable Project project, @NotNull String siteUrl) {
        super(project, true);
        this.siteUrl = siteUrl;
        setTitle("Přihlášení do Moodle VŠE tokenem");
        setOKButtonText("Přihlásit se");
        init();
    }

    public @NotNull MoodleToken getToken() {
        return new MoodleToken(new String(tokenField.getPassword()).trim(), null);
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JBLabel hint = new JBLabel("<html>V Moodle (" + siteUrl + ") otevřete <b>Předvolby → Bezpečnostní klíče</b><br>"
            + "a zkopírujte klíč služby <b>Moodle mobile web service</b>.</html>");
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        tokenField.setColumns(40);
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Token:", tokenField)
            .addComponentToRightColumn(hint)
            .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return tokenField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String token = new String(tokenField.getPassword()).trim();
        if (token.isEmpty()) {
            return new ValidationInfo("Vložte token.", tokenField);
        }
        if (!token.matches("[A-Za-z0-9]+")) {
            return new ValidationInfo("Token smí obsahovat jen písmena a číslice.", tokenField);
        }
        return null;
    }
}
