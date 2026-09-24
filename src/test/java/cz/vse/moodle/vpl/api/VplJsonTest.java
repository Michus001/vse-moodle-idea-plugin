package cz.vse.moodle.vpl.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cz.vse.moodle.api.MoodleException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class VplJsonTest {
    @Test
    public void unwrapsSuccessfulEnvelope() throws Exception {
        JsonObject response = VplJson.parseEnvelope("{\"success\":true,\"response\":{\"version\":7},\"error\":\"\"}");
        assertEquals(7, response.get("version").getAsInt());
    }

    @Test
    public void reportsVplErrors() {
        MoodleException e = assertThrows(MoodleException.class,
            () -> VplJson.parseEnvelope("{\"success\":false,\"response\":{},\"error\":\"Nepřihlášen(a)\"}"));
        assertEquals(VplJson.VPL_ERROR, e.getErrorCode());
        assertEquals("Nepřihlášen(a)", e.getMessage());
    }

    @Test
    public void rejectsHtml() {
        MoodleException e = assertThrows(MoodleException.class, () -> VplJson.parseEnvelope("<html>login</html>"));
        assertEquals("invalidresponse", e.getErrorCode());
    }

    @Test
    public void parsesLoadResponse() throws Exception {
        JsonObject response = VplJson.parseEnvelope("""
            {"success":true,"error":"","response":{
              "version":"42","comments":"",
              "files":[{"name":"src/Main.java","contents":"class Main {}","encoding":0},
                       {"name":"logo.png","contents":"AAEC","encoding":1}],
              "compilationexecution":{"compilation":"","evaluation":"-Test 1: OK","execution":"",
                                      "grade":"Navrhovaná známka: 10 / 10","nevaluations":"3",
                                      "freeevaluations":"2","reductionbyevaluation":"1"},
              "timeLeft":3600}}
            """);
        VplSubmission submission = VplJson.parseSubmission(response);
        assertEquals(42, submission.version());
        assertEquals(2, submission.files().size());
        assertEquals("src/Main.java", submission.files().getFirst().name());
        assertEquals("class Main {}", new String(submission.files().getFirst().data(), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{0, 1, 2}, submission.files().get(1).data());
        assertEquals(Long.valueOf(3600), submission.timeLeft());

        VplResult result = submission.result();
        assertEquals("Navrhovaná známka: 10 / 10", result.grade());
        assertEquals(3, result.evaluations());
        assertTrue(result.isEvaluated());
        assertTrue(result.nextEvaluationIsPenalized());
    }

    @Test
    public void loadWithoutSubmission() throws Exception {
        JsonObject response = VplJson.parseEnvelope("""
            {"success":true,"error":"","response":{"version":0,"comments":"","files":[],
              "compilationexecution":{"grade":"","nevaluations":0,"freeevaluations":0,"reductionbyevaluation":"0"}}}
            """);
        VplSubmission submission = VplJson.parseSubmission(response);
        assertEquals(0, submission.version());
        assertNull(submission.timeLeft());
        assertFalse(submission.result().isEvaluated());
        assertFalse(submission.result().nextEvaluationIsPenalized());
        assertNull(VplJson.parseResult(JsonParser.parseString("false")));
    }

    @Test
    public void encodesFilesLikeTheVplEditor() {
        JsonArray json = VplJson.filesToJson(List.of(
            VplFile.text("Main.java", "System.out.println(\"Ahoj světe\");"),
            new VplFile("data.bin", new byte[]{0, (byte) 0xff})));
        JsonObject text = json.get(0).getAsJsonObject();
        assertEquals("Main.java", text.get("name").getAsString());
        assertEquals("System.out.println(\"Ahoj světe\");", text.get("contents").getAsString());
        assertEquals(0, text.get("encoding").getAsInt());
        JsonObject binary = json.get(1).getAsJsonObject();
        assertEquals("AP8=", binary.get("contents").getAsString());
        assertEquals(1, binary.get("encoding").getAsInt());
    }

    @Test
    public void saveRequestsConfirmationWhenNewerVersionExists() throws Exception {
        VplSaveResult conflict = VplJson.parseSave(VplJson.parseEnvelope(
            "{\"success\":true,\"error\":\"\",\"response\":{\"requestsconfirmation\":true,\"saved\":false,\"question\":\"Nahradit?\",\"version\":9}}"));
        assertFalse(conflict.saved());
        assertEquals("Nahradit?", conflict.question());
        assertEquals(9, conflict.version());

        VplSaveResult saved = VplJson.parseSave(VplJson.parseEnvelope(
            "{\"success\":true,\"error\":\"\",\"response\":{\"requestsconfirmation\":false,\"saved\":true,\"version\":10}}"));
        assertTrue(saved.saved());
        assertEquals(10, saved.version());
    }

    @Test
    public void parsesExecutionAndBuildsMonitorUri() throws Exception {
        VplExecution execution = VplJson.parseExecution(VplJson.parseEnvelope("""
            {"success":true,"error":"","response":{"server":"jail.vse.cz","monitorPath":"abc/monitor",
              "executionPath":"def/execute","port":80,"securePort":443,"wsProtocol":"always_use_wss","processid":17}}
            """));
        assertEquals(17, execution.processId());
        assertEquals("wss://jail.vse.cz:443/abc/monitor", execution.monitorUri().toString());
        assertEquals("ws://jail.vse.cz:80/abc/monitor",
            new VplExecution("jail.vse.cz", 80, 443, "abc/monitor", 1, "always_use_ws").monitorUri().toString());
    }

    @Test
    public void detectsBinaryFiles() {
        assertFalse(VplFile.text("a.txt", "Příliš žluťoučký kůň").isBinary());
        assertTrue(new VplFile("a.class", new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe}).isBinary());
        assertTrue(new VplFile("a.dat", new byte[]{'a', 0, 'b'}).isBinary());
    }
}
