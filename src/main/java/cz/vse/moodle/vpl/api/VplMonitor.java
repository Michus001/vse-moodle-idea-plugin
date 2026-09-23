package cz.vse.moodle.vpl.api;

import cz.vse.moodle.api.MoodleHttp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Follows an evaluation in the VPL jail server. The jail sends text frames
 * {@code message:<state>[:<detail>]}, then {@code retrieve:} when the result is ready (or {@code close:}).
 */
public final class VplMonitor {
    public enum Outcome {
        /** The result is ready: call {@link VplApi#retrieve}. */
        RETRIEVE,
        /** The jail closed the monitor without a result. */
        CLOSED,
        CANCELLED
    }

    private VplMonitor() {
    }

    /**
     * Blocks until the jail reports the result.
     *
     * @param onStatus  receives user readable progress ("Překládám…")
     * @param cancelled polled regularly; when true the monitor is closed, which also stops the evaluation
     */
    public static @NotNull Outcome await(@NotNull URI monitorUri, @NotNull Duration timeout,
                                         @NotNull Consumer<String> onStatus, @NotNull BooleanSupplier cancelled)
        throws IOException {
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        WebSocket socket;
        try {
            socket = MoodleHttp.defaultClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(monitorUri, new Listener(outcome, onStatus))
                .get(20, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Vyhodnocení bylo přerušeno.");
        }
        catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new IOException("Nelze se připojit k vyhodnocovacímu serveru " + monitorUri.getHost() + " (" + describe(cause) + ").", cause);
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    socket.abort();
                    return Outcome.CANCELLED;
                }
                if (System.nanoTime() > deadline) {
                    socket.abort();
                    throw new IOException("Vyhodnocení nedoběhlo včas.");
                }
                try {
                    return outcome.get(250, TimeUnit.MILLISECONDS);
                }
                catch (TimeoutException ignored) {
                    // poll cancellation again
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            socket.abort();
            throw new InterruptedIOException("Vyhodnocení bylo přerušeno.");
        }
        catch (ExecutionException e) {
            throw new IOException("Spojení s vyhodnocovacím serverem selhalo (" + describe(e.getCause()) + ").", e.getCause());
        }
        finally {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            }
        }
    }

    /** Translates jail states to Czech; unknown states are shown as they are. */
    static @NotNull String describeState(@NotNull String state, @Nullable String detail) {
        String text = switch (state) {
            case "connecting" -> "Připojuji…";
            case "connected" -> "Připojeno";
            case "compilation", "compiling" -> "Překládám…";
            case "running" -> "Spouštím…";
            case "evaluating", "evaluation" -> "Vyhodnocuji…";
            case "retrieve", "retrieving" -> "Stahuji výsledek…";
            default -> state;
        };
        return detail == null || detail.isBlank() ? text : text + " " + detail;
    }

    private static @NotNull String describe(@Nullable Throwable e) {
        if (e == null) return "neznámá chyba";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Outcome> outcome;
        private final Consumer<String> onStatus;
        private final StringBuilder buffer = new StringBuilder();

        Listener(@NotNull CompletableFuture<Outcome> outcome, @NotNull Consumer<String> onStatus) {
            this.outcome = outcome;
            this.onStatus = onStatus;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handle(message);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(@NotNull String message) {
            int colon = message.indexOf(':');
            String action = colon >= 0 ? message.substring(0, colon) : message;
            String content = colon >= 0 ? message.substring(colon + 1) : "";
            switch (action) {
                case "message" -> {
                    int split = content.indexOf(':');
                    onStatus.accept(split >= 0
                        ? describeState(content.substring(0, split), content.substring(split + 1))
                        : describeState(content, null));
                }
                case "retrieve" -> outcome.complete(Outcome.RETRIEVE);
                case "close" -> outcome.complete(Outcome.CLOSED);
                default -> {
                    // "compilation:" and "run:" belong to interactive runs, not to evaluation.
                }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            outcome.complete(Outcome.CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            outcome.completeExceptionally(error);
        }
    }
}
