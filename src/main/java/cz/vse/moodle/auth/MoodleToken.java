package cz.vse.moodle.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Web service token issued by Moodle. {@link #toString()} never reveals the secrets.
 *
 * @param token        token for {@code webservice/rest/server.php}
 * @param privateToken token used by the mobile app for auto-login; only present right after SSO login
 */
public record MoodleToken(@NotNull String token, @Nullable String privateToken) {
    @Override
    public String toString() {
        return "MoodleToken{***" + (privateToken != null ? ", privateToken=***" : "") + "}";
    }
}
