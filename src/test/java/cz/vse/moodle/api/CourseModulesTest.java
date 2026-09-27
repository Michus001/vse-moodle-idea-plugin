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

    /** Moodle 4.5: subsections come after the regular sections, linked by itemid = instance of the "subsection" module. */
    private static final String WITH_SUBSECTIONS = """
        [
          {"id":2,"name":"Týden 1","component":null,"itemid":null,"modules":[
            {"id":601,"name":"Hello world","modname":"vpl","uservisible":true}
          ]},
          {"id":9,"name":"Trénink","component":null,"itemid":null,"modules":[
            {"id":900,"name":"★ Rozcvička","modname":"vpl","uservisible":true},
            {"id":901,"name":"Proměnné","modname":"subsection","instance":11,"uservisible":true},
            {"id":902,"name":"Kolekce","modname":"subsection","instance":12,"uservisible":true}
          ]},
          {"id":20,"name":"Proměnné","component":"mod_subsection","itemid":11,"modules":[
            {"id":950,"name":"★ Součet","modname":"vpl","uservisible":true}
          ]},
          {"id":21,"name":"Kolekce","component":"mod_subsection","itemid":12,"modules":[
            {"id":960,"name":"★★ Seřazení seznamu","modname":"vpl","uservisible":true},
            {"id":961,"name":"Kvíz","modname":"quiz","uservisible":true}
          ]},
          {"id":22,"name":"Skryté téma","component":"mod_subsection","itemid":13,"modules":[
            {"id":970,"name":"Osiřelá","modname":"vpl","uservisible":true}
          ]}
        ]
        """;

    @Test
    public void placesSubsectionActivitiesUnderTheirParentSection() throws Exception {
        List<CourseModule> modules = MoodleClient.parseCourseModules(MoodleResponses.parseRest(WITH_SUBSECTIONS), "vpl");
        assertEquals(List.of(601L, 900L, 950L, 960L, 970L), modules.stream().map(CourseModule::id).toList());

        assertEquals("Týden 1", modules.get(0).sectionName());
        assertNull(modules.get(0).subsectionName());
        assertEquals("Trénink", modules.get(1).sectionName());
        assertNull(modules.get(1).subsectionName());
        assertEquals("Trénink", modules.get(2).sectionName());
        assertEquals("Proměnné", modules.get(2).subsectionName());
        assertEquals("Trénink", modules.get(3).sectionName());
        assertEquals("Kolekce", modules.get(3).subsectionName());
        // Its placeholder module isn't visible: listed on its own.
        assertEquals("Skryté téma", modules.get(4).sectionName());
        assertNull(modules.get(4).subsectionName());
    }

    @Test
    public void matchesSubsectionsByNameWithoutItemId() throws Exception {
        String json = """
            [
              {"id":9,"name":"Trénink","modules":[{"id":901,"name":"Kolekce","modname":"subsection"}]},
              {"id":21,"name":"Kolekce","component":"mod_subsection","modules":[{"id":960,"name":"Úloha","modname":"vpl"}]}
            ]
            """;
        CourseModule module = MoodleClient.parseCourseModules(MoodleResponses.parseRest(json), "vpl").getFirst();
        assertEquals("Trénink", module.sectionName());
        assertEquals("Kolekce", module.subsectionName());
    }

    @Test
    public void rejectsUnexpectedResponse() {
        assertThrows(MoodleException.class, () -> MoodleClient.parseCourseModules(MoodleResponses.parseRest("{\"foo\":1}"), "vpl"));
    }
}
