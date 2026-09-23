package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;

/**
 * One-time key from {@code tool_mobile_get_autologin_key}, valid for 60 seconds.
 * Opening {@code autologinUrl?userid=..&key=..&urltogo=..} starts a web session for the user.
 */
public record AutologinKey(@NotNull String key, @NotNull String autologinUrl) {
    @Override
    public String toString() {
        return "AutologinKey{***}";
    }
}
