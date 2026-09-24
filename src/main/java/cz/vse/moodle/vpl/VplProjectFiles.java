package cz.vse.moodle.vpl;

import cz.vse.moodle.vpl.api.VplFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maps VPL files to a project directory and back. No IntelliJ APIs, so it is unit-testable. */
public final class VplProjectFiles {
    /** Directories that never belong to a submission (IDE, build output, VCS). */
    private static final Set<String> IGNORED_DIRS = Set.of(".idea", ".git", ".svn", ".gradle", ".vscode",
        "out", "build", "target", "bin", "node_modules", "__pycache__");
    private static final Set<String> IGNORED_FILES = Set.of(VplTaskMetadata.FILE_NAME, ".DS_Store", "Thumbs.db");
    /** Guard against accidentally submitting large binaries. */
    static final long MAX_FILE_SIZE = 1024 * 1024;

    private VplProjectFiles() {
    }

    /**
     * Resolves a file name coming from Moodle inside {@code root}.
     *
     * @throws IOException for absolute paths or paths escaping {@code root} ({@code ../})
     */
    public static @NotNull Path resolve(@NotNull Path root, @NotNull String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("Neplatný název souboru z Moodle: " + name);
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(normalized).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IOException("Neplatný název souboru z Moodle: " + name);
        }
        return target;
    }

    public static void writeFiles(@NotNull Path root, @NotNull List<VplFile> files) throws IOException {
        for (VplFile file : files) {
            Path target = resolve(root, file.name());
            Files.createDirectories(target.getParent());
            Files.write(target, file.data());
        }
    }

    /**
     * Files to submit: everything in the project except IDE/build/VCS files and this plugin's metadata.
     * Names are relative with {@code /} separators, sorted so that requested files come first.
     */
    public static @NotNull List<VplFile> collect(@NotNull Path root, @NotNull List<String> requestedFiles) throws IOException {
        List<Path> paths = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && !IGNORED_FILES.contains(name) && !name.toLowerCase(Locale.ROOT).endsWith(".iml")) {
                    paths.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        List<VplFile> files = new ArrayList<>();
        for (Path path : paths) {
            if (Files.size(path) > MAX_FILE_SIZE) {
                throw new IOException("Soubor " + base.relativize(path) + " je větší než 1 MB. Přesuňte ho mimo složku úlohy.");
            }
            files.add(new VplFile(relativeName(base, path), Files.readAllBytes(path)));
        }
        files.sort(Comparator
            .comparingInt((VplFile f) -> requestedFiles.contains(f.name()) ? requestedFiles.indexOf(f.name()) : Integer.MAX_VALUE)
            .thenComparing(VplFile::name));
        return files;
    }

    static @NotNull String relativeName(@NotNull Path base, @NotNull Path file) {
        return base.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    /** A directory name safe on all platforms, e.g. "Úloha 3: Pole / seznamy" -> "Úloha 3 - Pole - seznamy". */
    public static @NotNull String safeDirName(@NotNull String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]+", " - ")
            .replaceAll("[\\p{Cntrl}]", "")
            .replaceAll("\\s+", " ")
            .replaceAll("( - )+", " - ")
            .trim()
            .replaceAll("^[.\\s-]+|[.\\s-]+$", "");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80).trim();
        }
        return safe.isEmpty() ? "uloha" : safe;
    }

    /**
     * Writes a minimal IntelliJ Java project ({@code .idea/} + module) when the task contains Java sources,
     * so the student can compile and run it right away. Existing IDE configuration is left alone.
     *
     * @param jdkName name of a configured JDK (Project Structure), or null to let IntelliJ ask
     */
    public static void writeJavaProjectConfig(@NotNull Path root, @NotNull String moduleName, @NotNull List<VplFile> files,
                                              @Nullable String jdkName) throws IOException {
        boolean hasJava = files.stream().anyMatch(f -> f.name().endsWith(".java"));
        Path idea = root.resolve(".idea");
        if (!hasJava || Files.exists(idea)) {
            return;
        }
        boolean srcLayout = files.stream().anyMatch(f -> f.name().startsWith("src/"));
        String module = safeFileName(moduleName);
        Files.createDirectories(idea);
        Files.writeString(idea.resolve("misc.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectRootManager" version="2" default="true"%s>
                <output url="file://$PROJECT_DIR$/out" />
              </component>
            </project>
            """.formatted(jdkName != null ? " project-jdk-name=\"" + xml(jdkName) + "\" project-jdk-type=\"JavaSDK\"" : ""),
            StandardCharsets.UTF_8);
        Files.writeString(idea.resolve("modules.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectModuleManager">
                <modules>
                  <module fileurl="file://$PROJECT_DIR$/%1$s.iml" filepath="$PROJECT_DIR$/%1$s.iml" />
                </modules>
              </component>
            </project>
            """.formatted(xml(module)), StandardCharsets.UTF_8);
        Files.writeString(root.resolve(module + ".iml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <module type="JAVA_MODULE" version="4">
              <component name="NewModuleRootManager" inherit-compiler-output="true">
                <exclude-output />
                <content url="file://$MODULE_DIR$">
                  <sourceFolder url="file://$MODULE_DIR$%s" isTestSource="false" />
                  <excludeFolder url="file://$MODULE_DIR$/out" />
                </content>
                <orderEntry type="inheritedJdk" />
                <orderEntry type="sourceFolder" forTests="false" />
              </component>
            </module>
            """.formatted(srcLayout ? "/src" : ""), StandardCharsets.UTF_8);
    }

    private static @NotNull String safeFileName(@NotNull String name) {
        String safe = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^[._]+", "");
        return safe.isEmpty() ? "uloha" : safe;
    }

    private static @NotNull String xml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
