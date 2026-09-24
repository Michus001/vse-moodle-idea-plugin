package cz.vse.moodle.vpl.api;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VplWebSessionTest {
    @Test
    public void extractsSesskeyFromMoodlePage() {
        String html = "<script>M.cfg = {\"wwwroot\":\"https:\\/\\/moodle.vse.cz\",\"sesskey\":\"Ab12Cd34Ef\",\"sessiontimeout\":\"28800\"};</script>";
        assertEquals("Ab12Cd34Ef", VplWebSession.parseSesskey(html));
        assertNull(VplWebSession.parseSesskey("<html>Přihlášení</html>"));
    }

    @Test
    public void recognizesLiveSession() {
        assertTrue(VplWebSession.isAliveResponse(JsonParser.parseString(
            "[{\"error\":false,\"data\":{\"userid\":42,\"timeremaining\":28000}}]"), 42));
        assertFalse(VplWebSession.isAliveResponse(JsonParser.parseString(
            "[{\"error\":false,\"data\":{\"userid\":7,\"timeremaining\":28000}}]"), 42));
        assertFalse(VplWebSession.isAliveResponse(JsonParser.parseString(
            "[{\"error\":false,\"data\":{\"userid\":42,\"timeremaining\":0}}]"), 42));
        assertFalse(VplWebSession.isAliveResponse(JsonParser.parseString(
            "{\"error\":true,\"exception\":{\"errorcode\":\"servicerequireslogin\"}}"), 42));
        assertFalse(VplWebSession.isAliveResponse(JsonParser.parseString(
            "[{\"error\":{\"errorcode\":\"invalidsesskey\"}}]"), 42));
    }

    @Test
    public void restoresSerializedSession() throws Exception {
        String serialized = "{\"siteUrl\":\"https://moodle.vse.cz\",\"userId\":42,\"sesskey\":\"abc\","
            + "\"cookies\":[{\"name\":\"MoodleSession\",\"value\":\"s3cr3t\",\"path\":\"/\"}]}";
        VplWebSession session = VplWebSession.restore(serialized, "https://moodle.vse.cz", 42);
        assertNotNull(session);
        assertEquals(List.of("MoodleSession=s3cr3t"),
            session.cookieHeaderFor(URI.create("https://moodle.vse.cz/mod/vpl/forms/edit.json.php?id=1")));
        assertEquals(List.of(), session.cookieHeaderFor(URI.create("https://evil.example.com/")));

        VplWebSession again = VplWebSession.restore(session.serialize(), "https://moodle.vse.cz", 42);
        assertNotNull(again);
        assertEquals(List.of("MoodleSession=s3cr3t"), again.cookieHeaderFor(URI.create("https://moodle.vse.cz/")));

        assertNull(VplWebSession.restore(serialized, "https://moodle.vse.cz", 7));
        assertNull(VplWebSession.restore(serialized, "https://jiny.moodle.cz", 42));
        assertNull(VplWebSession.restore("garbage", "https://moodle.vse.cz", 42));
    }

    @Test
    public void translatesMonitorStates() {
        assertEquals("Překládám…", VplMonitor.describeState("compilation", null));
        assertEquals("Vyhodnocuji… 3/10", VplMonitor.describeState("evaluating", "3/10"));
        assertEquals("custom", VplMonitor.describeState("custom", ""));
    }
}
