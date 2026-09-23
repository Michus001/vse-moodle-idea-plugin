package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Error reported by Moodle itself (as opposed to a network failure, which is an {@link java.io.IOException}).
 * The message is meant to be shown to the user.
 */
public class MoodleException extends Exception {
    private static final Set<String> TOKEN_ERROR_CODES = Set.of("invalidtoken", "accessexception", "invalidlogin");

    private final String errorCode;
    private final @Nullable String exceptionClass;

    public MoodleException(@NotNull String errorCode, @NotNull String message) {
        this(errorCode, message, null);
    }

    public MoodleException(@NotNull String errorCode, @NotNull String message, @Nullable String exceptionClass) {
        super(message);
        this.errorCode = errorCode;
        this.exceptionClass = exceptionClass;
    }

    public @NotNull String getErrorCode() {
        return errorCode;
    }

    public @Nullable String getExceptionClass() {
        return exceptionClass;
    }

    /** True when the stored token can no longer be used and the user has to log in again. */
    public boolean isInvalidToken() {
        return TOKEN_ERROR_CODES.contains(errorCode);
    }
}
