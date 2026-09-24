package cz.vse.moodle.vpl.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/**
 * Running evaluation in a VPL jail server ({@code evaluate} action). Progress is reported over a WebSocket
 * at {@link #monitorUri()}; once it says {@code retrieve:} the result is fetched with the {@code retrieve} action.
 *
 * @param wsProtocol site setting: {@code always_use_wss}, {@code always_use_ws} or anything else for "same as the site"
 */
public record VplExecution(@NotNull String server,
                           int port,
                           int securePort,
                           @NotNull String monitorPath,
                           long processId,
                           @Nullable String wsProtocol) {

    public @NotNull URI monitorUri() {
        boolean secure = !"always_use_ws".equals(wsProtocol);
        return URI.create((secure ? "wss://" : "ws://") + server + ":" + (secure ? securePort : port) + "/" + monitorPath);
    }
}
