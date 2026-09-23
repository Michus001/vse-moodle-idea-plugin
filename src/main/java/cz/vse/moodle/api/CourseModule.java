package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Activity from {@code core_course_get_contents}.
 *
 * @param id               course module id ({@code cmid}), used by all module web services
 * @param userVisible      false when the activity is shown but not available (restrictions, hidden)
 * @param availabilityInfo HTML explaining why the activity isn't available, if Moodle provides it
 * @param description      HTML description; only present when the teacher shows it on the course page
 * @param opens            "startdate"/"allowsubmissionsfromdate" from the activity dates, if any
 * @param due              "duedate" from the activity dates, if any
 */
public record CourseModule(long id,
                           @NotNull String name,
                           @NotNull String modName,
                           @Nullable String url,
                           @NotNull String sectionName,
                           boolean userVisible,
                           @Nullable String availabilityInfo,
                           @Nullable String description,
                           @Nullable Instant opens,
                           @Nullable Instant due) {

    /** Available to the user and within its submission period at {@code now}. */
    public boolean isOpenAt(@NotNull Instant now) {
        return userVisible
            && (opens == null || !now.isBefore(opens))
            && (due == null || now.isBefore(due));
    }
}
