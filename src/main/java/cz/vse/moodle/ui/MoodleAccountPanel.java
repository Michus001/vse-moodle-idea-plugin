package cz.vse.moodle.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import cz.vse.moodle.session.MoodleSessionListener;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.session.MoodleSessionState;
import cz.vse.moodle.session.MoodleUser;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.settings.MoodleSettingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.text.AbstractDocument;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionListener;

/** "Student" tab of the Moodle tool window: login button, or the logged-in student and the other team members. */
final class MoodleAccountPanel extends JPanel implements Disposable {
    private final Project project;
    private final JPanel body = new JPanel();

    MoodleAccountPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(JBUI.Borders.empty(12));
        add(body, BorderLayout.NORTH);

        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe(MoodleSessionListener.TOPIC, (MoodleSessionListener) this::render);
        render(MoodleSessionService.getInstance().getState());
    }

    private void render(@NotNull MoodleSessionState state) {
        body.removeAll();
        MoodleSessionService service = MoodleSessionService.getInstance();
        switch (state.status()) {
            case UNKNOWN, LOADING -> {
                JBLabel label = new JBLabel(state.message() != null ? state.message() : "Načítám…", new AnimatedIcon.Default(), SwingConstants.LEFT);
                append(label);
            }
            case LOGGED_OUT -> {
                append(new JBLabel("Nejste přihlášeni do Moodle VŠE."));
                addMessage(state.message(), true);
                addGap();
                JButton login = new JButton("Přihlásit se");
                login.addActionListener(e -> service.login(project));
                append(login);
                addGap();
                append(new ActionLink("Vložit token ručně…", (ActionListener) e -> service.loginWithToken(project)));
                addSiteHint();
            }
            case LOGGED_IN -> renderUser(state.user(), service);
            case ERROR -> {
                append(new JBLabel("Přihlášení se nepodařilo ověřit."));
                addMessage(state.message(), true);
                addGap();
                JButton retry = new JButton("Zkusit znovu");
                retry.addActionListener(e -> service.restoreSession());
                append(retry);
                addGap();
                append(new ActionLink("Odhlásit", (ActionListener) e -> service.logout()));
                addSiteHint();
            }
        }
        body.revalidate();
        body.repaint();
    }

    private void renderUser(@Nullable MoodleUser user, @NotNull MoodleSessionService service) {
        if (user == null) return;
        JBLabel name = new JBLabel(user.fullName());
        name.setFont(JBFont.h3().asBold());
        append(name);
        addGap();
        append(field("Uživatelské jméno", user.username()));
        append(field("E-mail", user.email() != null ? user.email() : "nedostupný"));
        addGap();
        addTeamField();
        addGap();
        JButton logout = new JButton("Odhlásit");
        logout.addActionListener(e -> service.logout());
        append(logout);
        addSiteHint();
    }

    /** "Ostatní členové týmu": appended to submitted files below the logged-in student (see VplSubmitterStamp). */
    private void addTeamField() {
        JBLabel caption = new JBLabel("Ostatní členové týmu:");
        caption.setForeground(UIUtil.getContextHelpForeground());
        append(caption);

        MoodleSettings settings = MoodleSettings.getInstance();
        JBTextField team = new JBTextField(AsciiDocumentFilter.keepAscii(settings.getTeamMembersText()));
        team.getEmptyText().setText("např. xnovj01@vse.cz, xdvop02@vse.cz");
        ((AbstractDocument) team.getDocument()).setDocumentFilter(new AsciiDocumentFilter());
        team.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                settings.setTeamMembersText(team.getText());
            }
        });
        team.setMaximumSize(new Dimension(Integer.MAX_VALUE, team.getPreferredSize().height));
        append(team);

        JBLabel hint = new JBLabel("<html>Oddělte čárkou. Jen znaky bez diakritiky (ASCII). "
            + "Každý člen se připíše na konec odevzdaných souborů pod přihlášeného studenta.</html>");
        hint.setAllowAutoWrapping(true);
        hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        hint.setForeground(UIUtil.getContextHelpForeground());
        append(hint);
    }

    private static @NotNull JComponent field(@NotNull String label, @NotNull String value) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel caption = new JBLabel(label + ":");
        caption.setForeground(UIUtil.getContextHelpForeground());
        row.add(caption, BorderLayout.WEST);
        JBLabel valueLabel = new JBLabel(value);
        valueLabel.setCopyable(true);
        row.add(valueLabel, BorderLayout.CENTER);
        row.setBorder(JBUI.Borders.emptyBottom(4));
        return row;
    }

    private void addMessage(@Nullable String message, boolean isError) {
        if (message == null || message.isBlank()) return;
        JBLabel label = new JBLabel("<html>" + escape(message) + "</html>");
        label.setAllowAutoWrapping(true);
        if (isError) label.setForeground(JBColor.RED);
        label.setBorder(JBUI.Borders.emptyTop(6));
        append(label);
    }

    private void addSiteHint() {
        addGap();
        JBLabel site = new JBLabel(MoodleSettings.getInstance().getSiteUrl());
        site.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        site.setForeground(UIUtil.getContextHelpForeground());
        append(site);
        append(new ActionLink("Nastavení…", (ActionListener) e -> ShowSettingsUtil.getInstance().showSettingsDialog(project, MoodleSettingsConfigurable.class)));
    }

    private void addGap() {
        JPanel gap = new JPanel();
        gap.setOpaque(false);
        gap.setBorder(JBUI.Borders.emptyTop(8));
        append(gap);
    }

    private void append(@NotNull JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(component);
    }

    private static @NotNull String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void dispose() {
    }
}
