package cz.vse.moodle.api;

import com.intellij.util.net.JdkProxyProvider;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared HTTP plumbing. Blocking calls: never use them on the EDT. */
public final class MoodleHttp {
    static final String USER_AGENT = "MoodleVSE-IntelliJ-Plugin";
    /**
     * Moodle only issues auto-login keys to its own apps ({@code core_useragent::is_moodle_app()} looks for
     * "MoodleMobile"). The plugin logs in exactly like the app, so it identifies as one for that call.
     */
    static final String MOBILE_APP_USER_AGENT = "MoodleMobile " + USER_AGENT;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private MoodleHttp() {
    }

    private static final class Holder {
        // Honors the IDE proxy settings (Settings | Appearance & Behavior | System Settings | HTTP Proxy).
        static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(JdkProxyProvider.getInstance().getProxySelector())
            .authenticator(JdkProxyProvider.getInstance().getAuthenticator())
            .build();
    }

    public static @NotNull HttpClient defaultClient() {
        return Holder.CLIENT;
    }

    /** Client with its own cookie jar, used for a Moodle web session (see {@code VplWebSession}). */
    public static @NotNull HttpClient newCookieClient(@NotNull CookieManager cookies) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookies)
            .proxy(JdkProxyProvider.getInstance().getProxySelector())
            .authenticator(JdkProxyProvider.getInstance().getAuthenticator())
            .build();
    }

    static @NotNull String postForm(@NotNull HttpClient http, @NotNull String url, @NotNull Map<String, String> form)
        throws IOException {
        return postForm(http, url, form, USER_AGENT);
    }

    static @NotNull String postForm(@NotNull HttpClient http, @NotNull String url, @NotNull Map<String, String> form,
                                    @NotNull String userAgent) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
            .build();
        return send(http, request);
    }

    public static @NotNull String postJson(@NotNull HttpClient http, @NotNull String url, @NotNull String json) throws IOException {
        return postJson(http, url, json, REQUEST_TIMEOUT);
    }

    public static @NotNull String postJson(@NotNull HttpClient http, @NotNull String url, @NotNull String json,
                                           @NotNull Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return send(http, request);
    }

    /** GET that follows redirects; the response tells where it ended up (e.g. the login page). */
    public static @NotNull HttpResponse<String> get(@NotNull HttpClient http, @NotNull String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<String> response = exchange(http, request);
        if (response.statusCode() != 200) {
            throw new IOException("Server Moodle odpověděl HTTP " + response.statusCode() + ".");
        }
        return response;
    }

    private static @NotNull HttpResponse<String> exchange(@NotNull HttpClient http, @NotNull HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Požadavek na Moodle byl přerušen.");
        }
    }

    private static @NotNull String send(@NotNull HttpClient http, @NotNull HttpRequest request) throws IOException {
        HttpResponse<String> response = exchange(http, request);
        if (response.statusCode() != 200) {
            // Deliberately without the URL: REST URLs may carry the token.
            throw new IOException("Server Moodle odpověděl HTTP " + response.statusCode() + ".");
        }
        return response.body();
    }

    static @NotNull String encodeForm(@NotNull Map<String, String> form) {
        return form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    }
}
