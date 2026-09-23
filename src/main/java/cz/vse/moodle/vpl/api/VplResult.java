package cz.vse.moodle.vpl.api;

import org.jetbrains.annotations.NotNull;

/**
 * Compilation/evaluation result of a submission as VPL shows it in its editor.
 *
 * @param compilation           compiler output (empty when it compiled cleanly)
 * @param evaluation            evaluation comments (test report)
 * @param execution             raw execution output, only when there is no grade/comments
 * @param grade                 human readable proposed grade, e.g. "Navrhovaná známka: 8 / 10"; empty if not evaluated
 * @param evaluations           how many times the student has evaluated this activity
 * @param freeEvaluations       evaluations without a penalty
 * @param reductionByEvaluation penalty per extra evaluation as configured (e.g. "1" or "10%"), empty/"0" if none
 */
public record VplResult(@NotNull String compilation,
                        @NotNull String evaluation,
                        @NotNull String execution,
                        @NotNull String grade,
                        int evaluations,
                        int freeEvaluations,
                        @NotNull String reductionByEvaluation) {

    public boolean isEvaluated() {
        return !grade.isEmpty() || !evaluation.isEmpty() || !compilation.isEmpty() || !execution.isEmpty();
    }

    /** True when the next evaluation lowers the grade. */
    public boolean nextEvaluationIsPenalized() {
        String reduction = reductionByEvaluation.trim();
        boolean hasReduction = !reduction.isEmpty() && !reduction.equals("0") && !reduction.equals("0%");
        return hasReduction && evaluations >= freeEvaluations;
    }
}
