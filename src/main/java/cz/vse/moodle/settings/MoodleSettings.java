package cz.vse.moodle.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import cz.vse.moodle.api.SiteUrls;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@State(name = "MoodleVseSettings", storages = @Storage("moodle-vse.xml"))
public final class MoodleSettings implements PersistentStateComponent<MoodleSettings.SettingsState> {
    public static final String DEFAULT_SITE_URL = "https://moodle.vse.cz";

    public static final class SettingsState {
        public String siteUrl = DEFAULT_SITE_URL;
    }

    private SettingsState state = new SettingsState();

    public static @NotNull MoodleSettings getInstance() {
        return ApplicationManager.getApplication().getService(MoodleSettings.class);
    }

    /** Normalized site URL without a trailing slash. */
    public @NotNull String getSiteUrl() {
        String url = state.siteUrl;
        return url == null || url.isBlank() ? DEFAULT_SITE_URL : SiteUrls.normalize(url);
    }

    public void setSiteUrl(@NotNull String siteUrl) {
        state.siteUrl = SiteUrls.normalize(siteUrl);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }
}
