package cz.vse.moodle.api;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CourseModulesTest {
    private static final String CONTENTS = """
        [
          {"id":1,"name":"Úvod","modules":[
            {"id":501,"name":"Fórum","modname":"forum","uservisible":true}
          ]},
          {"id":2,"name":"Týden 1","modules":[
            {"id":601,"name":"Hello world","modname":"vpl","url":"https://moodle.vse.cz/mod/vpl/view.php?id=601",
             "uservisible":true,"description":"<p>Napište program</p>",
             "dates":[{"label":"Začátek:","timestamp":1790000000,"dataid":"startdate"},
                      {"label":"Termín:","timestamp":1791000000,"dataid":"duedate"}]},
            {"id":602,"name":"Bez termínu","modname":"vpl","uservisible":true,"availabilityinfo":"","dates":[]},
            {"id":603,"name":"Skrytá","modname":"vpl","uservisible":false,"availabilityinfo":"Dostupné od 1. 10."}
          ]}
        ]
        """;

    @Test
    public void parsesVplModulesWithDates() throws Exception {
        List<CourseModule> modules = MoodleClient.parseCourseModules(MoodleResponses.parseRest(CONTENTS), "vpl");
        assertEquals(3, modules.size());

        CourseModule hello = modules.getFirst();
        assertEquals(601, hello.id());
        assertEquals("Hello world", hello.name());
        assertEquals("Týden 1", hello.sectionName());
        assertEquals("<p>Napište program</p>", hello.description());
        assertEquals(Instant.ofEpochSecond(1790000000), hello.opens());
        assertEquals(Instant.ofEpochSecond(1791000000), hello.due());

        CourseModule noDue = modules.get(1);
        assertNull(noDue.due());
        assertNull(noDue.availabilityInfo());

        assertFalse(modules.get(2).userVisible());
        assertEquals("Dostupné od 1. 10.", modules.get(2).availabilityInfo());
    }

    @Test
    public void openDependsOnDatesAndVisibility() throws Exception {
        List<CourseModule> modules = MoodleClient.parseCourseModules(MoodleResponses.parseRest(CONTENTS), "vpl");
        CourseModule hello = modules.getFirst();
        assertFalse(hello.isOpenAt(Instant.ofEpochSecond(1789999999)));
        assertTrue(hello.isOpenAt(Instant.ofEpochSecond(1790000000)));
        assertFalse(hello.isOpenAt(Instant.ofEpochSecond(1791000000)));
        assertTrue(modules.get(1).isOpenAt(Instant.now()));
        assertFalse(modules.get(2).isOpenAt(Instant.now()));
    }

    @Test
    public void rejectsUnexpectedResponse() {
        assertThrows(MoodleException.class, () -> MoodleClient.parseCourseModules(MoodleResponses.parseRest("{\"foo\":1}"), "vpl"));
    }
}
