package cz.vse.moodle.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Subset of {@code core_webservice_get_site_info}. */
public record SiteInfo(
    long userId,
    @NotNull String username,
    @NotNull String fullName,
    @Nullable String siteName
) {
}
