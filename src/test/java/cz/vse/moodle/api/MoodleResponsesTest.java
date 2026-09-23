package cz.vse.moodle.api;

import com.google.gson.JsonElement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MoodleResponsesTest {
    @Test
    public void detectsInvalidTokenError() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodleResponses.parseRest(
            "{\"exception\":\"moodle_exception\",\"errorcode\":\"invalidtoken\",\"message\":\"Neplatný token - token nebyl nalezen\"}"));
        assertEquals("invalidtoken", e.getErrorCode());
        assertEquals("moodle_exception", e.getExceptionClass());
        assertEquals("Neplatný token - token nebyl nalezen", e.getMessage());
        assertTrue(e.isInvalidToken());
    }

    @Test
    public void detectsOtherErrorsAsNonTokenErrors() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodleResponses.parseRest(
            "{\"exception\":\"invalid_parameter_exception\",\"errorcode\":\"invalidparameter\",\"message\":\"Invalid parameter\",\"debuginfo\":\"x\"}"));
        assertEquals("invalidparameter", e.getErrorCode());
        assertFalse(e.isInvalidToken());
    }

    @Test
    public void detectsAccessExceptionAsTokenError() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodleResponses.parseRest(
            "{\"exception\":\"webservice_access_exception\",\"errorcode\":\"accessexception\",\"message\":\"Access control exception\"}"));
        assertTrue(e.isInvalidToken());
    }

    @Test
    public void detectsErrorWithoutExceptionKey() {
        MoodleException e = assertThrows(MoodleException.class,
            () -> MoodleResponses.parseRest("{\"errorcode\":\"invalidlogin\",\"message\":\"Invalid login\"}"));
        assertEquals("invalidlogin", e.getErrorCode());
    }

    @Test
    public void passesThroughRegularResponses() throws Exception {
        JsonElement object = MoodleResponses.parseRest("{\"username\":\"xnovj01\",\"userid\":42,\"errorcodes\":[]}");
        assertEquals("xnovj01", object.getAsJsonObject().get("username").getAsString());

        JsonElement array = MoodleResponses.parseRest("[{\"id\":42,\"email\":\"jan@vse.cz\"}]");
        assertTrue(array.isJsonArray());
        assertNull(MoodleResponses.toError(array));

        // "message" alone (e.g. inside a regular result) is not an error.
        assertNull(MoodleResponses.toError(MoodleResponses.parseJson("{\"message\":\"hello\"}")));
    }

    @Test
    public void rejectsNonJson() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodleResponses.parseRest("<html>error</html>"));
        assertEquals("invalidresponse", e.getErrorCode());
    }

    @Test
    public void parsesSiteInfoAndEmail() throws Exception {
        SiteInfo info = MoodleClient.parseSiteInfo(MoodleResponses.parseRest(
            "{\"sitename\":\"LMS Moodle VŠE\",\"username\":\"xnovj01\",\"fullname\":\"Jan Novák\",\"userid\":42}"));
        assertEquals(42, info.userId());
        assertEquals("xnovj01", info.username());
        assertEquals("Jan Novák", info.fullName());

        assertEquals("jan@vse.cz", MoodleClient.parseUserEmail(MoodleResponses.parseRest("[{\"id\":42,\"email\":\"jan@vse.cz\"}]")));
        assertNull(MoodleClient.parseUserEmail(MoodleResponses.parseRest("[]")));
    }
}
