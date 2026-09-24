package cz.vse.moodle.vpl.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Response of the editor's {@code load} action: the requested files merged with the last submission.
 *
 * @param version  id of the last submission, 0 when the student hasn't submitted anything yet
 * @param result   result of the last submission, or the evaluation counters when there is none
 * @param timeLeft seconds to the due date, null when there is no due date (or it passed over an hour ago)
 */
public record VplSubmission(@NotNull List<VplFile> files,
                            long version,
                            @NotNull String comments,
                            @Nullable VplResult result,
                            @Nullable Long timeLeft) {
}
