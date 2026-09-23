package cz.vse.moodle.vpl.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Response of the editor's {@code save} action.
 *
 * @param saved    false when VPL wants a confirmation first (a newer submission exists)
 * @param version  id of the saved submission, or of the newer submission when {@code saved} is false
 * @param question VPL's confirmation question when {@code saved} is false
 */
public record VplSaveResult(boolean saved, long version, @Nullable String question) {
    public static @NotNull VplSaveResult saved(long version) {
        return new VplSaveResult(true, version, null);
    }
}
