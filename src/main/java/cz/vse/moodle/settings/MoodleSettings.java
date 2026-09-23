package cz.vse.moodle.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import cz.vse.moodle.api.SiteUrls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service(Service.Level.APP)
@State(name = "MoodleVseSettings", storages = @Storage("moodle-vse.xml"))
public final class MoodleSettings implements PersistentStateComponent<MoodleSettings.SettingsState> {
    public static final String DEFAULT_SITE_URL = "https://moodle.vse.cz";
    /** Course whose VPL assignments are listed by default. */
    public static final String DEFAULT_COURSE_IDS = "23982";

    public static final class SettingsState {
        public String siteUrl = DEFAULT_SITE_URL;
        /** Comma separated course ids, e.g. "23982, 24001". */
        public String courseIds = DEFAULT_COURSE_IDS;
        /** Where assignment projects are created; null means {@link #defaultProjectsDir()}. */
        public String projectsDir;
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

    /** Course ids in the configured order, without duplicates. */
    public @NotNull List<Long> getCourseIds() {
        List<Long> ids = parseCourseIds(state.courseIds != null ? state.courseIds : DEFAULT_COURSE_IDS);
        return ids != null ? ids : List.of();
    }

    public @NotNull String getCourseIdsText() {
        return state.courseIds != null ? state.courseIds : DEFAULT_COURSE_IDS;
    }

    public void setCourseIdsText(@NotNull String courseIds) {
        state.courseIds = courseIds.trim();
    }

    public @NotNull Path getProjectsDir() {
        String dir = state.projectsDir;
        return dir == null || dir.isBlank() ? defaultProjectsDir() : Path.of(dir);
    }

    public void setProjectsDir(@Nullable String dir) {
        state.projectsDir = dir == null || dir.isBlank() || Path.of(dir).equals(defaultProjectsDir()) ? null : dir.trim();
    }

    public static @NotNull Path defaultProjectsDir() {
        return Path.of(System.getProperty("user.home"), "MoodleVSE");
    }

    /**
     * Parses "23982, 24001" (also accepts course URLs such as {@code course/view.php?id=23982}).
     *
     * @return the ids, or null when some part isn't a course id
     */
    public static @Nullable List<Long> parseCourseIds(@NotNull String text) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            String value = part;
            int idParam = part.lastIndexOf("id=");
            if (idParam >= 0) {
                value = part.substring(idParam + 3).replaceAll("[&#].*$", "");
            }
            try {
                long id = Long.parseLong(value);
                if (id <= 1) return null; // 1 is the site front page, not a course
                ids.add(id);
            }
            catch (NumberFormatException e) {
                return null;
            }
        }
        return new ArrayList<>(ids);
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
