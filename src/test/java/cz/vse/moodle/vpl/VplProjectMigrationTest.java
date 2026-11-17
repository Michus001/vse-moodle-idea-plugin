package cz.vse.moodle.vpl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VplProjectMigrationTest {
    private static final String SITE = "https://moodle.vse.cz";
    private static final String COURSE = "4IT107 Programování v Javě (2026 - 2027 ZS)";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path task(Path projectsDir, String courseDir, String taskDir, long cmid, String name) throws IOException {
        Path dir = projectsDir.resolve(courseDir).resolve(taskDir);
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Main.java"), "class Main {}");
        VplTaskMetadata metadata = new VplTaskMetadata();
        metadata.siteUrl = SITE;
        metadata.courseId = 42;
        metadata.courseName = COURSE;
        metadata.cmid = cmid;
        metadata.name = name;
        metadata.write(dir);
        return dir;
    }

    @Test
    public void renamesNonAsciiFoldersAndModuleFile() throws IOException {
        Path projects = temp.getRoot().toPath();
        Path old = task(projects, COURSE, "Hon na hodnotu", 601, "Hon na hodnotu");
        Path other = task(projects, COURSE, "Řetězce", 602, "Řetězce");
        VplProjectFiles.writeJavaProjectConfig(other, "Řetězce", List.of(), null); // no Java: nothing written
        Files.createDirectories(other.resolve(".idea"));
        Files.writeString(other.resolve(".idea/modules.xml"),
            "<module fileurl=\"file://$PROJECT_DIR$/Řetězce.iml\" filepath=\"$PROJECT_DIR$/Řetězce.iml\" />");
        Files.writeString(other.resolve(".idea/workspace.xml"), "<module name=\"Řetězce\" />");
        Files.writeString(other.resolve("Řetězce.iml"), "<module/>");

        List<VplProjectMigration.Result> results = VplProjectMigration.migrateAll(projects, dir -> false);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(VplProjectMigration.Result::ok));
        Path course = projects.resolve("4IT107_Programovani_v_Jave_2026_-_2027_ZS");
        assertEquals("class Main {}", Files.readString(course.resolve("Hon_na_hodnotu/src/Main.java")));
        Path retezce = course.resolve("Retezce");
        assertTrue(Files.exists(retezce.resolve("Retezce.iml")));
        assertTrue(Files.readString(retezce.resolve(".idea/modules.xml"), StandardCharsets.UTF_8).contains("$PROJECT_DIR$/Retezce.iml"));
        assertEquals("<module name=\"Retezce\" />", Files.readString(retezce.resolve(".idea/workspace.xml"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(old));
        assertFalse("empty old course folder is removed", Files.exists(projects.resolve(COURSE)));

        VplProjectLocator.TaskDir found = VplProjectLocator.find(projects, SITE + "/", 601);
        assertNotNull(found);
        assertEquals(course.resolve("Hon_na_hodnotu"), found.dir());
        assertTrue("second run has nothing to do", VplProjectMigration.migrateAll(projects, dir -> false).isEmpty());
    }

    @Test
    public void keepsEverythingWhenTargetExists() throws IOException {
        Path projects = temp.getRoot().toPath();
        Path old = task(projects, COURSE, "Žluťoučký kůň", 601, "Žluťoučký kůň");
        Path course = projects.resolve("4IT107_Programovani_v_Jave_2026_-_2027_ZS");
        Files.createDirectories(course.resolve("Zlutoucky_kun"));
        Files.createDirectories(course.resolve("Zlutoucky_kun_601"));

        List<VplProjectMigration.Result> results = VplProjectMigration.migrateAll(projects, dir -> false);

        assertEquals(VplProjectMigration.Status.CONFLICT, results.getFirst().status());
        assertEquals(old, results.getFirst().current());
        assertTrue(Files.exists(old.resolve("src/Main.java")));
        assertTrue(Files.exists(old.resolve(VplTaskMetadata.FILE_NAME)));
    }

    @Test
    public void usesIdSuffixWhenPreferredNameIsTaken() throws IOException {
        Path projects = temp.getRoot().toPath();
        task(projects, COURSE, "Žluťoučký kůň", 601, "Žluťoučký kůň");
        Path course = projects.resolve("4IT107_Programovani_v_Jave_2026_-_2027_ZS");
        Files.createDirectories(course.resolve("Zlutoucky_kun"));

        VplProjectMigration.Result result = VplProjectMigration.migrateAll(projects, dir -> false).getFirst();

        assertTrue(result.ok());
        assertEquals(course.resolve("Zlutoucky_kun_601"), result.to());
    }

    @Test
    public void skipsOpenProjects() throws IOException {
        Path projects = temp.getRoot().toPath();
        Path old = task(projects, COURSE, "Hon na hodnotu", 601, "Hon na hodnotu");

        VplProjectMigration.Result result = VplProjectMigration.migrateAll(projects, old::equals).getFirst();

        assertEquals(VplProjectMigration.Status.SKIPPED_OPEN, result.status());
        assertTrue(Files.exists(old.resolve("src/Main.java")));
    }

    @Test
    public void leavesAsciiFoldersAlone() throws IOException {
        Path projects = temp.getRoot().toPath();
        task(projects, "Kurz 5", "Hon na hodnotu (601)", 601, "Hon na hodnotu");
        assertTrue(VplProjectMigration.migrateAll(projects, dir -> false).isEmpty());
    }

    @Test
    public void newTaskDirAvoidsOccupiedFolders() throws IOException {
        Path course = temp.getRoot().toPath().resolve("kurz");
        assertEquals(course.resolve("Hon_na_hodnotu"), VplProjectLocator.newTaskDir(course, 601, "Hon na hodnotu"));
        Files.createDirectories(course.resolve("Hon_na_hodnotu"));
        assertEquals("empty folder is reused", course.resolve("Hon_na_hodnotu"), VplProjectLocator.newTaskDir(course, 601, "Hon na hodnotu"));
        Files.writeString(course.resolve("Hon_na_hodnotu/x.txt"), "x");
        assertEquals(course.resolve("Hon_na_hodnotu_601"), VplProjectLocator.newTaskDir(course, 601, "Hon na hodnotu"));
        assertEquals(course.resolve("uloha-7"), VplProjectLocator.newTaskDir(course, 7, "???"));
        assertNull(VplProjectLocator.find(course, SITE, 601));
    }
}
