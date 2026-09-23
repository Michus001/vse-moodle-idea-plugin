package cz.vse.moodle.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class MoodlePublicConfigTest {
    private static final String SITE = "https://moodle.vse.cz";

    @Test
    public void parsesVseLikeConfig() throws Exception {
        MoodlePublicConfig config = MoodlePublicConfig.parseAjaxResponse("""
            [{"error":false,"data":{"wwwroot":"https://moodle.vse.cz","enablewebservices":1,"enablemobilewebservice":1,
              "typeoflogin":1,"launchurl":"https://moodle.vse.cz/admin/tool/mobile/launch.php",
              "identityproviders":[{"name":"OpenID Connect","iconurl":"x","url":"https://moodle.vse.cz/auth/oidc/?source=loginpage"}]}}]
            """, SITE);
        assertEquals("https://moodle.vse.cz/admin/tool/mobile/launch.php", config.launchUrl());
        assertEquals("moodlemobile", config.urlScheme());
        assertEquals(1, config.identityProviders().size());
        assertEquals("https://moodle.vse.cz/auth/oidc/?source=loginpage", config.identityProviders().getFirst().url());
    }

    @Test
    public void usesFallbackLaunchUrlAndForcedScheme() throws Exception {
        MoodlePublicConfig config = MoodlePublicConfig.parseAjaxResponse("""
            [{"error":false,"data":{"enablewebservices":1,"enablemobilewebservice":1,"tool_mobile_forcedurlscheme":"vseapp"}}]
            """, SITE);
        assertEquals(SITE + "/admin/tool/mobile/launch.php", config.launchUrl());
        assertEquals("vseapp", config.urlScheme());
    }

    @Test
    public void failsWhenMobileServicesDisabled() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodlePublicConfig.parseAjaxResponse(
            "[{\"error\":false,\"data\":{\"enablewebservices\":1,\"enablemobilewebservice\":0}}]", SITE));
        assertEquals("mobileservicedisabled", e.getErrorCode());
    }

    @Test
    public void surfacesAjaxErrors() {
        MoodleException e = assertThrows(MoodleException.class, () -> MoodlePublicConfig.parseAjaxResponse(
            "[{\"error\":true,\"exception\":{\"message\":\"Web services are disabled\",\"errorcode\":\"servicenotavailable\"}}]", SITE));
        assertEquals("servicenotavailable", e.getErrorCode());
        assertEquals("Web services are disabled", e.getMessage());
    }
}
