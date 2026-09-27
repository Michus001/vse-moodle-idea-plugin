package cz.vse.moodle.training;

import cz.vse.moodle.api.CourseModule;
import cz.vse.moodle.vpl.api.VplResult;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TrainingTasksTest {
    @Test
    public void matchesTrainingSectionIgnoringCaseAndSpaces() {
        assertTrue(TrainingTasks.isTrainingSection("Trénink", "Trénink"));
        assertTrue(TrainingTasks.isTrainingSection("  trénink ", "Trénink"));
        assertFalse(TrainingTasks.isTrainingSection("Týden 1", "Trénink"));
        // Decomposed é (macOS input), non-breaking space, HTML from format_string.
        assertTrue(TrainingTasks.isTrainingSection("Trénink", "Trénink"));
        assertTrue(TrainingTasks.isTrainingSection(" Trénink ", "Trénink"));
        assertTrue(TrainingTasks.isTrainingSection("<span class=\"course-mod_subsection\">Tr&eacute;nink</span>", "Trénink"));
    }

    @Test
    public void recognizesTrainingSectionOrSubsection() {
        CourseModule inSection = module("Trénink", "Kolekce");
        assertTrue(TrainingTasks.isTraining(inSection, "Trénink"));
        assertEquals("Kolekce", TrainingTasks.topic(inSection, "Trénink"));

        // "Trénink" created as a subsection of a regular section: training, without a topic.
        CourseModule inSubsection = module("Týden 1", "Trénink");
        assertTrue(TrainingTasks.isTraining(inSubsection, "Trénink"));
        assertNull(TrainingTasks.topic(inSubsection, "Trénink"));

        assertFalse(TrainingTasks.isTraining(module("Týden 1", "Cvičení"), "Trénink"));
        assertFalse(TrainingTasks.isTraining(module("Týden 1", null), "Trénink"));
    }

    @Test
    public void readsDifficultyFromLeadingStars() {
        assertEquals(2, TrainingTasks.difficulty("★★ Seřazení seznamu"));
        assertEquals(3, TrainingTasks.difficulty("*** Hvězdičky z klávesnice"));
        assertEquals(0, TrainingTasks.difficulty("Bez obtížnosti ★"));
        assertEquals("Seřazení seznamu", TrainingTasks.title("★★ Seřazení seznamu"));
        assertEquals("Bez obtížnosti ★", TrainingTasks.title("Bez obtížnosti ★"));
    }

    @Test
    public void scoresFromProposedGrade() {
        assertEquals(1.0, TrainingTasks.score(result("Navrhovaná známka: 10 / 10", "")), 1e-9);
        assertEquals(0.85, TrainingTasks.score(result("Navrhovaná známka: 8,5 / 10", "")), 1e-9);
        assertEquals(0.0, TrainingTasks.score(result("Proposed grade: 0 / 10", "")), 1e-9);
    }

    @Test
    public void scoresFromTestSummaryWhenNotGraded() {
        String evaluation = """
            -Summary of tests
            >+------------------------------+
            >|  5 tests run/ 4 tests passed |
            >+------------------------------+
            """;
        assertEquals(0.8, TrainingTasks.score(result("", evaluation)), 1e-9);
        assertNull(TrainingTasks.score(result("", "Chyba překladu")));
        assertTrue(TrainingTasks.isFullScore(TrainingTasks.score(result("", "| 3 tests run/ 3 tests passed |"))));
    }

    @Test
    public void nextTaskIsEasiestUnsolvedOtherThanCurrent() {
        CourseModule hard = task(1, "★★★ Těžká");
        CourseModule easySolved = task(2, "★ Vyřešená");
        CourseModule medium = task(3, "★★ Střední");
        CourseModule easy = task(4, "★ Lehká");
        List<CourseModule> tasks = List.of(hard, easySolved, medium, easy);
        Map<Long, TrainingStatus> status = Map.of(2L, TrainingStatus.SOLVED, 4L, TrainingStatus.STARTED);

        assertEquals(easy, TrainingTasks.nextTask(tasks, t -> status.getOrDefault(t.id(), TrainingStatus.NEW), null));
        assertEquals(medium, TrainingTasks.nextTask(tasks, t -> status.getOrDefault(t.id(), TrainingStatus.NEW), 4L));
        // Only the current one is left: offer it again.
        assertEquals(easy, TrainingTasks.nextTask(List.of(easySolved, easy), t -> status.getOrDefault(t.id(), TrainingStatus.NEW), 4L));
        assertNull(TrainingTasks.nextTask(List.of(easySolved), t -> TrainingStatus.SOLVED, null));
    }

    private static VplResult result(String grade, String evaluation) {
        return new VplResult("", evaluation, "", grade, 1, 0, "0");
    }

    private static CourseModule module(String section, String subsection) {
        return new CourseModule(1, "Úloha", "vpl", null, section, subsection, true, null, null, null, null);
    }

    private static CourseModule task(long id, String name) {
        return new CourseModule(id, name, "vpl", null, "Trénink", "Kolekce", true, null, null, null, null);
    }
}
