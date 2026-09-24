package cz.vse.moodle.vpl;

import cz.vse.moodle.settings.MoodleSettings;
import cz.vse.moodle.vpl.api.VplFile;
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VplProjectFilesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void refusesPathsOutsideTheProject() throws IOException {
        Path root = temp.getRoot().toPath();
        assertEquals(root.resolve("src/Main.java"), VplProjectFiles.resolve(root, "src/Main.java"));
        assertEquals(root.resolve("src/Main.java"), VplProjectFiles.resolve(root, "src\\Main.java"));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, "../evil.sh"));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, "src/../../evil.sh"));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, "/etc/passwd"));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, "C:\\Windows\\x"));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, "."));
        assertThrows(IOException.class, () -> VplProjectFiles.resolve(root, ""));
    }

    @Test
    public void collectsSubmissionFilesWithoutIdeFiles() throws IOException {
        Path root = temp.getRoot().toPath();
        VplProjectFiles.writeFiles(root, List.of(
            VplFile.text("src/Main.java", "class Main {}"),
            VplFile.text("README.txt", "zadání")));
        Files.writeString(root.resolve("src/Helper.java"), "class Helper {}");
        Files.createDirectories(root.resolve(".idea"));
        Files.writeString(root.resolve(".idea/workspace.xml"), "<x/>");
        Files.createDirectories(root.resolve("out/production"));
        Files.writeString(root.resolve("out/production/Main.class"), "x");
        Files.writeString(root.resolve("uloha.iml"), "<module/>");
        Files.writeString(root.resolve(VplTaskMetadata.FILE_NAME), "{}");

        List<VplFile> files = VplProjectFiles.collect(root, List.of("src/Main.java", "README.txt"));
        assertEquals(List.of("src/Main.java", "README.txt", "src/Helper.java"), files.stream().map(VplFile::name).toList());
        assertEquals("zadání", new String(files.get(1).data(), StandardCharsets.UTF_8));
    }

    @Test
    public void refusesHugeFiles() throws IOException {
        Path root = temp.getRoot().toPath();
        Files.write(root.resolve("big.bin"), new byte[(int) VplProjectFiles.MAX_FILE_SIZE + 1]);
        assertThrows(IOException.class, () -> VplProjectFiles.collect(root, List.of()));
    }

    @Test
    public void writesJavaProjectForJavaTasks() throws IOException {
        Path root = temp.newFolder("Hello world").toPath();
        List<VplFile> files = List.of(VplFile.text("src/Main.java", "class Main {}"));
        VplProjectFiles.writeJavaProjectConfig(root, "Hello world", files, "corretto-21");

        assertTrue(Files.readString(root.resolve(".idea/misc.xml")).contains("project-jdk-name=\"corretto-21\""));
        assertTrue(Files.readString(root.resolve(".idea/modules.xml")).contains("Hello_world.iml"));
        assertTrue(Files.readString(root.resolve("Hello_world.iml")).contains("$MODULE_DIR$/src\" isTestSource=\"false\""));

        // An existing .idea is never overwritten.
        Files.writeString(root.resolve(".idea/misc.xml"), "custom");
        VplProjectFiles.writeJavaProjectConfig(root, "Hello world", files, null);
        assertEquals("custom", Files.readString(root.resolve(".idea/misc.xml")));
    }

    @Test
    public void flatJavaTaskUsesRootAsSourceFolder() throws IOException {
        Path root = temp.newFolder("flat").toPath();
        VplProjectFiles.writeJavaProjectConfig(root, "flat", List.of(VplFile.text("Main.java", "")), null);
        String iml = Files.readString(root.resolve("flat.iml"));
        assertTrue(iml.contains("<sourceFolder url=\"file://$MODULE_DIR$\""));
        assertFalse(Files.readString(root.resolve(".idea/misc.xml")).contains("project-jdk-name"));
    }

    @Test
    public void skipsProjectConfigForOtherLanguages() throws IOException {
        Path root = temp.newFolder("py").toPath();
        VplProjectFiles.writeJavaProjectConfig(root, "py", List.of(VplFile.text("main.py", "print(1)")), null);
        assertFalse(Files.exists(root.resolve(".idea")));
    }

    @Test
    public void makesSafeDirectoryNames() {
        assertEquals("Úloha 3 - Pole - seznamy", VplProjectFiles.safeDirName("Úloha 3: Pole / seznamy"));
        assertEquals("uloha", VplProjectFiles.safeDirName("..."));
        assertEquals("a - b", VplProjectFiles.safeDirName("a<>b"));
    }

    @Test
    public void metadataRoundTrip() throws IOException {
        Path root = temp.getRoot().toPath();
        assertNull(VplTaskMetadata.read(root));
        VplTaskMetadata metadata = new VplTaskMetadata();
        metadata.siteUrl = "https://moodle.vse.cz";
        metadata.cmid = 601;
        metadata.name = "Hello <world>";
        metadata.requestedFiles = List.of("src/Main.java");
        metadata.write(root);

        VplTaskMetadata read = VplTaskMetadata.read(root);
        assertNotNull(read);
        assertEquals(601, read.cmid);
        assertEquals("Hello <world>", read.name);
        assertEquals(List.of("src/Main.java"), read.requestedFiles);
        assertEquals("https://moodle.vse.cz/mod/vpl/view.php?id=601", read.activityUrl());
    }

    @Test
    public void parsesCourseIds() {
        assertEquals(List.of(23982L), MoodleSettings.parseCourseIds("23982"));
        assertEquals(List.of(23982L, 24001L), MoodleSettings.parseCourseIds(" 23982, 24001;23982 "));
        assertEquals(List.of(23982L), MoodleSettings.parseCourseIds("https://moodle.vse.cz/course/view.php?id=23982#section-2"));
        assertEquals(List.of(), MoodleSettings.parseCourseIds(""));
        assertNull(MoodleSettings.parseCourseIds("abc"));
        assertNull(MoodleSettings.parseCourseIds("1"));
    }

    @Test
    public void comparesJdkVersions() {
        assertTrue(VplTaskOpener.compareVersions("openjdk version \"21.0.2\"", "17") > 0);
        assertTrue(VplTaskOpener.compareVersions("1.8.0_392", "11") < 0);
    }
}
