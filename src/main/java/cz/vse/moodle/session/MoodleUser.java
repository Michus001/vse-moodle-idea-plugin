package cz.vse.moodle.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The logged-in user as shown in the UI. {@code email} is null when Moodle doesn't expose it. */
public record MoodleUser(long id, @NotNull String username, @NotNull String fullName, @Nullable String email) {
}
