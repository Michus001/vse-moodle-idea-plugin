package cz.vse.moodle.session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of the session.
 *
 * @param user    set only for {@link Status#LOGGED_IN}
 * @param message progress text for {@link Status#LOADING}, error text for {@link Status#ERROR},
 *                optional explanation for {@link Status#LOGGED_OUT} (e.g. expired token)
 */
public record MoodleSessionState(@NotNull Status status, @Nullable MoodleUser user, @Nullable String message) {
    public enum Status {
        /** Stored token hasn't been checked yet. */
        UNKNOWN,
        LOGGED_OUT,
        LOADING,
        LOGGED_IN,
        /** Token is stored but couldn't be verified (network error, server error). */
        ERROR
    }

    public static final MoodleSessionState UNKNOWN = new MoodleSessionState(Status.UNKNOWN, null, null);

    public static @NotNull MoodleSessionState loggedOut(@Nullable String message) {
        return new MoodleSessionState(Status.LOGGED_OUT, null, message);
    }

    public static @NotNull MoodleSessionState loading(@NotNull String message) {
        return new MoodleSessionState(Status.LOADING, null, message);
    }

    public static @NotNull MoodleSessionState loggedIn(@NotNull MoodleUser user) {
        return new MoodleSessionState(Status.LOGGED_IN, user, null);
    }

    public static @NotNull MoodleSessionState error(@NotNull String message) {
        return new MoodleSessionState(Status.ERROR, null, message);
    }
}
