package cz.vse.moodle.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathNamesTest {
    private static final List<String> SAMPLES = List.of(
        "4IT107 Programování v Javě (2026 - 2027 ZS)", "Hon na hodnotu", "Žluťoučký kůň", "CON", "con.txt", "COM1",
        "LPT9.java", "", "   ", "()!?*", "...", "_._", "Úloha 3: Pole / seznamy", "a<>b", "Łódź Straße Ørsted",
        "x".repeat(200), "CON." + "y".repeat(80), "Приклад 1", "  ..tečky na začátku..  ", "a__b  c", "日本語");

    @Test
    public void removesDiacriticsAndUnsafeCharacters() {
        assertEquals("4IT107_Programovani_v_Jave_2026_-_2027_ZS",
            PathNames.safeDirName("4IT107 Programování v Javě (2026 - 2027 ZS)"));
        assertEquals("Hon_na_hodnotu", PathNames.safeDirName("Hon na hodnotu"));
        assertEquals("Zlutoucky_kun", PathNames.safeDirName("Žluťoučký kůň"));
        assertEquals("Uloha_3_Pole_seznamy", PathNames.safeDirName("Úloha 3: Pole / seznamy"));
        assertEquals("Lodz_Strasse_Orsted", PathNames.safeDirName("Łódź Straße Ørsted"));
        assertEquals("tecky_na_zacatku", PathNames.safeDirName("  ..tečky na začátku..  "));
        assertEquals("a_b_c", PathNames.safeDirName("a__b  c"));
    }

    @Test
    public void avoidsReservedWindowsNames() {
        assertEquals("CON_", PathNames.safeDirName("CON"));
        assertEquals("con_.txt", PathNames.safeDirName("con.txt"));
        assertEquals("COM1_", PathNames.safeDirName("COM1"));
        assertEquals("LPT9_.java", PathNames.safeDirName("LPT9.java"));
        assertEquals("CONSOLE", PathNames.safeDirName("CONSOLE"));
        assertEquals("COM10", PathNames.safeDirName("COM10"));
    }

    @Test
    public void usesFallbackWhenNothingIsLeft() {
        assertEquals("uloha-601", PathNames.safeDirName("", "uloha-601"));
        assertEquals("uloha-601", PathNames.safeDirName(null, "uloha-601"));
        assertEquals("uloha-601", PathNames.safeDirName("   ", "uloha-601"));
        assertEquals("uloha-601", PathNames.safeDirName("()!?*", "uloha-601"));
        assertEquals("uloha-601", PathNames.safeDirName("日本語", "uloha-601"));
        assertEquals("slozka", PathNames.safeDirName("...", "???"));
    }

    @Test
    public void limitsLength() {
        assertEquals(PathNames.MAX_LENGTH, PathNames.safeDirName("x".repeat(200)).length());
        assertEquals("Hon_na_hodnotu_601", PathNames.safeDirName("Hon na hodnotu", "uloha", "_601"));
        String long_ = PathNames.safeDirName("x".repeat(200), "uloha", "_601");
        assertEquals(PathNames.MAX_LENGTH, long_.length());
        assertTrue(long_.endsWith("_601"));
    }

    @Test
    public void resultIsSafeAndIdempotent() {
        for (String sample : SAMPLES) {
            String safe = PathNames.safeDirName(sample);
            assertTrue(sample + " -> " + safe, safe.matches("[A-Za-z0-9._-]+"));
            assertTrue(sample + " -> " + safe, safe.length() <= PathNames.MAX_LENGTH);
            assertFalse(sample + " -> " + safe, safe.startsWith("_") || safe.startsWith(".") || safe.endsWith("."));
            assertEquals(sample, safe, PathNames.safeDirName(safe));
        }
    }

    @Test
    public void detectsAscii() {
        assertTrue(PathNames.isAscii("Hon na hodnotu (601)"));
        assertFalse(PathNames.isAscii("Programování"));
    }
}
