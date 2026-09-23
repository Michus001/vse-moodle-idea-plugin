package cz.vse.moodle.auth;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LaunchTokenParserTest {
    private static final String SITE = "https://moodle.vse.cz";
    private static final String PASSPORT = "0123456789abcdef";

    @Test
    public void parsesTokenAndPrivateToken() throws Exception {
        String url = redirect(md5(SITE + PASSPORT) + ":::abc123:::priv456");
        MoodleToken token = LaunchTokenParser.parse(url, PASSPORT, List.of(SITE));
        assertEquals("abc123", token.token());
        assertEquals("priv456", token.privateToken());
    }

    @Test
    public void parsesTokenWithoutPrivateToken() throws Exception {
        MoodleToken token = LaunchTokenParser.parse(redirect(md5(SITE + PASSPORT) + ":::abc123"), PASSPORT, List.of(SITE));
        assertEquals("abc123", token.token());
        assertNull(token.privateToken());
    }

    @Test
    public void acceptsHashComputedWithTrailingSlash() throws Exception {
        String url = redirect(md5(SITE + "/" + PASSPORT) + ":::abc123");
        assertEquals("abc123", LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)).token());
    }

    @Test
    public void acceptsConfiguredUrlWithTrailingSlash() throws Exception {
        String url = redirect(md5(SITE + PASSPORT) + ":::abc123");
        assertEquals("abc123", LaunchTokenParser.parse(url, PASSPORT, List.of(SITE + "/")).token());
    }

    @Test
    public void acceptsUppercaseHash() throws Exception {
        String url = redirect(md5(SITE + PASSPORT).toUpperCase() + ":::abc123");
        assertEquals("abc123", LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)).token());
    }

    @Test
    public void rejectsWrongPassport() {
        String url = redirect(md5(SITE + "otherpassport") + ":::abc123");
        assertThrows(InvalidLaunchTokenException.class, () -> LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)));
    }

    @Test
    public void rejectsOtherSite() {
        String url = redirect(md5("https://evil.example.com" + PASSPORT) + ":::abc123");
        assertThrows(InvalidLaunchTokenException.class, () -> LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)));
    }

    @Test
    public void rejectionMessageDoesNotLeakToken() {
        String url = redirect(md5(SITE + "otherpassport") + ":::supersecrettoken");
        InvalidLaunchTokenException e = assertThrows(InvalidLaunchTokenException.class,
            () -> LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)));
        assertFalse(e.getMessage().contains("supersecrettoken"));
    }

    @Test
    public void handlesPercentEncodedPaddingAndTrailingSlash() throws Exception {
        String payload = md5(SITE + PASSPORT) + ":::abc123:::pq";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertTrue("test needs padding", encoded.endsWith("="));
        String url = "moodlemobile://token=" + encoded.replace("=", "%3D").replace("+", "%2B") + "/";
        MoodleToken token = LaunchTokenParser.parse(url, PASSPORT, List.of(SITE));
        assertEquals("abc123", token.token());
        assertEquals("pq", token.privateToken());
    }

    @Test
    public void handlesMissingPadding() throws Exception {
        String payload = md5(SITE + PASSPORT) + ":::abc123:::p";
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertEquals("abc123", LaunchTokenParser.parse("moodlemobile://token=" + encoded, PASSPORT, List.of(SITE)).token());
    }

    @Test
    public void acceptsForcedCustomScheme() throws Exception {
        String encoded = Base64.getEncoder().encodeToString((md5(SITE + PASSPORT) + ":::abc123").getBytes(StandardCharsets.UTF_8));
        assertEquals("abc123", LaunchTokenParser.parse("vseapp://token=" + encoded, PASSPORT, List.of(SITE)).token());
    }

    @Test
    public void rejectsMalformedPayloads() {
        List<String> urls = List.of(
            "moodlemobile://token=",
            "moodlemobile://token=%%%not-base64%%%",
            redirect("no-separator"),
            redirect(md5(SITE + PASSPORT) + ":::"),
            redirect(md5(SITE + PASSPORT) + ":::a:::b:::c"),
            "moodlemobile://something-else");
        for (String url : urls) {
            assertThrows(url, InvalidLaunchTokenException.class, () -> LaunchTokenParser.parse(url, PASSPORT, List.of(SITE)));
        }
    }

    @Test
    public void detectsLaunchRedirects() {
        assertTrue(LaunchTokenParser.isLaunchRedirect("moodlemobile://token=abc"));
        assertTrue(LaunchTokenParser.isLaunchRedirect("MoodleMobile://token=abc"));
        assertTrue(LaunchTokenParser.isLaunchRedirect("custom.scheme-1://token=abc"));
        assertFalse(LaunchTokenParser.isLaunchRedirect("https://moodle.vse.cz/admin/tool/mobile/launch.php?token=abc"));
        assertFalse(LaunchTokenParser.isLaunchRedirect("https://token=abc"));
        assertFalse(LaunchTokenParser.isLaunchRedirect("moodlemobile://other"));
        assertFalse(LaunchTokenParser.isLaunchRedirect("about:blank"));
    }

    private static String redirect(String payload) {
        return "moodlemobile://token=" + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
