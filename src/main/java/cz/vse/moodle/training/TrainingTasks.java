package cz.vse.moodle.training;

import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.vpl.api.VplResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conventions of the training section of a course: which VPL activities are practice tasks, their difficulty
 * (leading stars in the name, e.g. {@code "★★ Seřazení seznamu"}) and when a task counts as solved.
 */
public final class TrainingTasks {
    /** "Navrhovaná známka: 8,5 / 10" */
    private static final Pattern GRADE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*/\\s*(\\d+(?:[.,]\\d+)?)");
    /** Summary printed by VPL's default evaluator: "| 5 tests run/ 4 tests passed |" */
    private static final Pattern TESTS = Pattern.compile("(\\d+)\\s+tests?\\s+run\\s*/\\s*(\\d+)\\s+tests?\\s+passed",
        Pattern.CASE_INSENSITIVE);
    private static final double EPSILON = 1e-9;

    private TrainingTasks() {
    }

    /**
     * True for activities in the configured training section or any of its subsections, and also for activities in
     * a subsection of that name (a teacher may create "Trénink" as a subsection of another section).
     */
    public static boolean isTraining(@NotNull CourseModule module) {
        return isTraining(module, MoodleSettings.getInstance().getTrainingSection());
    }

    public static boolean isTraining(@NotNull CourseModule module, @NotNull String trainingSection) {
        return isTrainingSection(module.sectionName(), trainingSection)
            || (module.subsectionName() != null && isTrainingSection(module.subsectionName(), trainingSection));
    }

    /** Topic of a training activity: the subsection inside the training section, null directly in it. */
    public static @Nullable String topic(@NotNull CourseModule module) {
        return topic(module, MoodleSettings.getInstance().getTrainingSection());
    }

    public static @Nullable String topic(@NotNull CourseModule module, @NotNull String trainingSection) {
        return isTrainingSection(module.sectionName(), trainingSection) ? module.subsectionName() : null;
    }

    public static boolean isTrainingSection(@NotNull String sectionName, @NotNull String trainingSection) {
        return normalize(sectionName).equalsIgnoreCase(normalize(trainingSection));
    }

    private static @NotNull String normalize(@NotNull String name) {
        return name.strip().replaceAll("\\s+", " ");
    }

    /** Number of leading stars: {@code "★★ Seřazení seznamu"} → 2; 0 when the name has none. */
    public static int difficulty(@NotNull String name) {
        int stars = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '★' || c == '*') stars++;
            else if (!Character.isWhitespace(c)) break;
        }
        return stars;
    }

    /** The name without the leading stars. */
    public static @NotNull String title(@NotNull String name) {
        int i = 0;
        while (i < name.length() && (name.charAt(i) == '★' || name.charAt(i) == '*' || Character.isWhitespace(name.charAt(i)))) {
            i++;
        }
        return i < name.length() ? name.substring(i) : name.strip();
    }

    public static @NotNull String stars(int difficulty) {
        return "★".repeat(Math.max(0, difficulty));
    }

    /**
     * Share of the maximum the result reached (0..1): from VPL's proposed grade, or from the test summary
     * when the activity isn't graded. Null when neither is present (e.g. nothing was evaluated).
     */
    public static @Nullable Double score(@NotNull VplResult result) {
        Double grade = lastRatio(GRADE.matcher(result.grade()), false);
        return grade != null ? grade : lastRatio(TESTS.matcher(result.evaluation()), true);
    }

    public static boolean isFullScore(double score) {
        return score >= 1 - EPSILON;
    }

    private static @Nullable Double lastRatio(@NotNull Matcher matcher, boolean totalFirst) {
        Double ratio = null;
        while (matcher.find()) {
            double first = Double.parseDouble(matcher.group(1).replace(',', '.'));
            double second = Double.parseDouble(matcher.group(2).replace(',', '.'));
            double value = totalFirst ? second : first;
            double max = totalFirst ? first : second;
            if (max > 0) ratio = Math.max(0, Math.min(1, value / max));
        }
        return ratio;
    }

    /**
     * The task to practise next: the easiest unsolved one (in course order among equally difficult tasks),
     * preferring a task other than {@code current}.
     */
    public static @Nullable CourseModule nextTask(@NotNull List<CourseModule> tasks,
                                                  @NotNull Function<CourseModule, TrainingStatus> status,
                                                  @Nullable Long current) {
        List<CourseModule> unsolved = new ArrayList<>();
        for (CourseModule task : tasks) {
            if (task.userVisible() && status.apply(task) != TrainingStatus.SOLVED) unsolved.add(task);
        }
        unsolved.sort(Comparator.comparingInt(task -> difficulty(task.name())));
        for (CourseModule task : unsolved) {
            if (current == null || task.id() != current) return task;
        }
        return unsolved.isEmpty() ? null : unsolved.getFirst();
    }
}
