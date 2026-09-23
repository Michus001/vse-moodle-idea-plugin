package cz.vse.moodle.auth;

import org.jetbrains.annotations.NotNull;

/** The launch redirect could not be parsed or verified. The message never contains token data. */
public class InvalidLaunchTokenException extends Exception {
    public InvalidLaunchTokenException(@NotNull String message) {
        super(message);
    }
}
