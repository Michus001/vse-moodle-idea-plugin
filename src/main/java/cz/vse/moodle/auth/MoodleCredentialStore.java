package cz.vse.moodle.auth;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import cz.vse.moodle.api.SiteUrls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stores tokens in the IDE {@link PasswordSafe}, keyed by site URL.
 * PasswordSafe may hit the OS keychain, so call this from a background thread.
 */
public final class MoodleCredentialStore {
    private static final String SUBSYSTEM = "Moodle VŠE";

    private MoodleCredentialStore() {
    }

    public static @Nullable MoodleToken load(@NotNull String siteUrl) {
        String token = PasswordSafe.getInstance().getPassword(attributes(siteUrl, "token"));
        if (token == null || token.isBlank()) {
            return null;
        }
        return new MoodleToken(token, PasswordSafe.getInstance().getPassword(attributes(siteUrl, "privatetoken")));
    }

    public static void save(@NotNull String siteUrl, @NotNull MoodleToken token) {
        PasswordSafe.getInstance().set(attributes(siteUrl, "token"), new Credentials("token", token.token()));
        PasswordSafe.getInstance().set(attributes(siteUrl, "privatetoken"),
            token.privateToken() != null ? new Credentials("privatetoken", token.privateToken()) : null);
    }

    public static void clear(@NotNull String siteUrl) {
        PasswordSafe.getInstance().set(attributes(siteUrl, "token"), null);
        PasswordSafe.getInstance().set(attributes(siteUrl, "privatetoken"), null);
        saveWebSession(siteUrl, null);
    }

    /** Serialized Moodle web session (cookies), see {@code VplWebSession#serialize()}. */
    public static @Nullable String loadWebSession(@NotNull String siteUrl) {
        return PasswordSafe.getInstance().getPassword(attributes(siteUrl, "websession"));
    }

    public static void saveWebSession(@NotNull String siteUrl, @Nullable String serialized) {
        PasswordSafe.getInstance().set(attributes(siteUrl, "websession"),
            serialized != null ? new Credentials("websession", serialized) : null);
    }

    private static @NotNull CredentialAttributes attributes(@NotNull String siteUrl, @NotNull String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SUBSYSTEM, key + "@" + SiteUrls.normalize(siteUrl)));
    }
}
