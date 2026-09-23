package cz.vse.moodle.vpl;

import cz.vse.moodle.vpl.api.VplFile;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VplSubmitterStampTest {
    private static String text(VplFile file) {
        return new String(file.data(), StandardCharsets.UTF_8);
    }

    @Test
    public void appendsEmailCommentToSourceFiles() {
        VplFile java = VplSubmitterStamp.apply(VplFile.text("src/Cinema.java", "class Cinema {}\n"), "jan.novak@vse.cz");
        assertEquals("class Cinema {}\n// " + VplSubmitterStamp.MARKER + " jan.novak@vse.cz\n", text(java));

        VplFile python = VplSubmitterStamp.apply(VplFile.text("main.py", "print(1)"), "jan.novak@vse.cz");
        assertEquals("print(1)\n# " + VplSubmitterStamp.MARKER + " jan.novak@vse.cz\n", text(python));

        VplFile html = VplSubmitterStamp.apply(VplFile.text("index.html", "<p/>\r\n"), "a-->b");
        assertEquals("<p/>\r\n<!-- " + VplSubmitterStamp.MARKER + " a- ->b -->\r\n", text(html));
    }

    @Test
    public void leavesDataAndBinaryFilesAlone() {
        VplFile data = VplFile.text("input.txt", "1 2 3\n");
        assertSame(data, VplSubmitterStamp.apply(data, "jan.novak@vse.cz"));
        VplFile noExtension = VplFile.text("Makefile", "all:\n");
        assertSame(noExtension, VplSubmitterStamp.apply(noExtension, "jan.novak@vse.cz"));
        VplFile binary = new VplFile("Lib.java", new byte[]{(byte) 0xca, (byte) 0xfe, 0});
        assertSame(binary, VplSubmitterStamp.apply(binary, "jan.novak@vse.cz"));
    }

    @Test
    public void stampDoesNotAccumulate() {
        VplFile original = VplFile.text("Main.java", "class Main {}\n");
        VplFile once = VplSubmitterStamp.apply(original, "jan.novak@vse.cz");
        VplFile twice = VplSubmitterStamp.apply(once, "jan.novak@vse.cz");
        assertEquals(text(once), text(twice));

        VplFile otherUser = VplSubmitterStamp.apply(once, "eva@vse.cz");
        assertEquals("class Main {}\n// " + VplSubmitterStamp.MARKER + " eva@vse.cz\n", text(otherUser));

        assertArrayEquals(original.data(), VplSubmitterStamp.strip(once).data());
        assertEquals(List.of(original), VplSubmitterStamp.strip(List.of(once)));
    }

    @Test
    public void stampIsPureAscii() {
        // VPL jails may run javac with US-ASCII; any other byte breaks the compilation.
        VplFile stamped = VplSubmitterStamp.apply(VplFile.text("Vstupy.java", "class Vstupy {}\n"), "jiří.nováková@vse.cz☃");
        String stamp = text(stamped).substring("class Vstupy {}\n".length());
        assertEquals("// Odevzdano pres IntelliJ (Moodle VSE): jiri.novakova@vse.cz?\n", stamp);
        for (char c : stamp.toCharArray()) {
            assertTrue("non-ASCII char " + (int) c, c < 128);
        }
    }

    @Test
    public void replacesLegacyNonAsciiStamp() {
        VplFile legacy = VplFile.text("Vstupy.java", "class Vstupy {}\n// " + VplSubmitterStamp.LEGACY_MARKER + " mict01@vse.cz\n");
        assertEquals("class Vstupy {}\n", text(VplSubmitterStamp.strip(legacy)));
        assertEquals("class Vstupy {}\n// " + VplSubmitterStamp.MARKER + " mict01@vse.cz\n",
            text(VplSubmitterStamp.apply(legacy, "mict01@vse.cz")));
    }

    @Test
    public void stripKeepsFilesWithoutStamp() {
        VplFile file = VplFile.text("Main.java", "// Odevzdal(a) je jen text uprostřed\nclass Main {}\n");
        assertSame(file, VplSubmitterStamp.strip(file));
    }
}
