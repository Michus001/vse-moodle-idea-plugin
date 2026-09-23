package cz.vse.moodle.ui;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Czech formatting of dates and remaining time for the tool window. */
final class UiFormat {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d. M. yyyy H:mm");

    private UiFormat() {
    }

    static @NotNull String dateTime(@NotNull Instant instant) {
        return DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    /** "zbývá 2 dny 3 h", "zbývá 45 min", "po termínu". */
    static @NotNull String remaining(@NotNull Duration left) {
        if (left.isNegative() || left.isZero()) return "po termínu";
        long days = left.toDays();
        long hours = left.toHoursPart();
        if (days > 0) return "zbývá " + days + " " + (days == 1 ? "den" : days < 5 ? "dny" : "dní") + (hours > 0 ? " " + hours + " h" : "");
        if (left.toHours() > 0) return "zbývá " + left.toHours() + " h " + left.toMinutesPart() + " min";
        return "zbývá " + Math.max(1, left.toMinutes()) + " min";
    }

    static @NotNull String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
