package cz.vse.moodle.session;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowserBase;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.api.MoodlePublicApi;
import cz.vse.moodle.api.MoodlePublicConfig;
import cz.vse.moodle.api.SiteInfo;
import cz.vse.moodle.auth.ManualTokenDialog;
import cz.vse.moodle.auth.MoodleCredentialStore;
import cz.vse.moodle.auth.MoodleToken;
import cz.vse.moodle.auth.SsoLoginDialog;
import cz.vse.moodle.auth.SsoLoginRequest;
import cz.vse.moodle.settings.MoodleSettings;
import org.cef.CefApp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the login state and the authenticated {@link MoodleClient}.
 * <p>
 * Every operation bumps {@link #generation}; results of an operation that has been superseded
 * (e.g. the user logged out while a login was verifying) are dropped.
 */
@Service(Service.Level.APP)
public final class MoodleSessionService {
    private static final Logger LOG = Logger.getInstance(MoodleSessionService.class);

    private final AtomicLong generation = new AtomicLong();
    private volatile @NotNull MoodleSessionState state = MoodleSessionState.UNKNOWN;
    private volatile @Nullable MoodleClient client;
    private volatile @Nullable String privateToken;

    public static @NotNull MoodleSessionService getInstance() {
        return ApplicationManager.getApplication().getService(MoodleSessionService.class);
    }

    public @NotNull MoodleSessionState getState() {
        return state;
    }

    /** Client for the logged-in user, or null when not logged in. Use it from background threads only. */
    public @Nullable MoodleClient getClient() {
        return client;
    }

    /**
     * Private token of the logged-in user, needed to open a Moodle web session ({@code tool_mobile_get_autologin_key}).
     * Null after a manual token login and for site administrators.
     */
    public @Nullable String getPrivateToken() {
        return privateToken;
    }

    /** Verifies the stored token unless that has already been done. */
    public void restoreSessionIfNeeded() {
        if (state.status() == MoodleSessionState.Status.UNKNOWN) {
            restoreSession();
        }
    }

    /** Loads the stored token and checks it with {@code core_webservice_get_site_info}. */
    public void restoreSession() {
        verify(null, MoodleSettings.getInstance().getSiteUrl(), null);
    }

    /** Starts the SSO login in an embedded browser, or the manual token dialog when JCEF isn't available. */
    public void login(@Nullable Project project) {
        if (!JBCefApp.isSupported()) {
            loginWithToken(project);
            return;
        }
        long gen = generation.incrementAndGet();
        String siteUrl = MoodleSettings.getInstance().getSiteUrl();
        setState(MoodleSessionState.loading("Připravuji přihlášení…"));

        new Task.Backgroundable(project, "Moodle VŠE: příprava přihlášení", true) {
            private SsoLoginRequest request;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    MoodlePublicConfig config = MoodlePublicApi.getPublicConfig(siteUrl);
                    request = SsoLoginRequest.create(siteUrl, config);
                }
                catch (MoodleException e) {
                    failure = e.getMessage();
                    return;
                }
                catch (IOException e) {
                    failure = connectionError(siteUrl, e);
                    return;
                }
                // launch.php only issues a token right after a fresh login, so drop any old Moodle session.
                clearBrowserSession(siteUrl);
            }

            @Override
            public void onSuccess() {
                if (isStale(gen)) return;
                if (failure != null) {
                    setState(MoodleSessionState.loggedOut(failure));
                    return;
                }
                SsoLoginDialog dialog = new SsoLoginDialog(project, request);
                boolean completed = dialog.showAndGet();
                if (isStale(gen)) return;
                MoodleToken token = dialog.getToken();
                if (!completed || token == null) {
                    setState(MoodleSessionState.loggedOut(completed ? dialog.getError() : null));
                    return;
                }
                verify(project, siteUrl, token);
            }

            @Override
            public void onCancel() {
                if (!isStale(gen)) setState(MoodleSessionState.loggedOut(null));
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.warn("Moodle login failed", error);
                if (!isStale(gen)) setState(MoodleSessionState.loggedOut("Přihlášení selhalo: " + error.getMessage()));
            }
        }.queue();
    }

    /** Asks the user to paste a token (Moodle: Preferences | Security keys) and verifies it. */
    public void loginWithToken(@Nullable Project project) {
        String siteUrl = MoodleSettings.getInstance().getSiteUrl();
        ManualTokenDialog dialog = new ManualTokenDialog(project, siteUrl);
        if (dialog.showAndGet()) {
            verify(project, siteUrl, dialog.getToken());
        }
    }

    public void logout() {
        generation.incrementAndGet();
        String siteUrl = MoodleSettings.getInstance().getSiteUrl();
        client = null;
        privateToken = null;
        setState(MoodleSessionState.loggedOut(null));
        ApplicationManager.getApplication().executeOnPooledThread(() -> MoodleCredentialStore.clear(siteUrl));
    }

    /** Called after the site URL was changed in the settings; tokens are stored per site. */
    public void siteUrlChanged() {
        client = null;
        privateToken = null;
        restoreSession();
    }

    /**
     * Verifies a token and fetches the user.
     *
     * @param newToken token from a fresh login (saved on success), or null to verify the stored one
     *                 (deleted if Moodle rejects it)
     */
    private void verify(@Nullable Project project, @NotNull String siteUrl, @Nullable MoodleToken newToken) {
        long gen = generation.incrementAndGet();
        setState(MoodleSessionState.loading(newToken != null ? "Načítám údaje uživatele…" : "Ověřuji uložené přihlášení…"));

        new Task.Backgroundable(project, "Moodle VŠE: ověřování přihlášení", false) {
            private MoodleSessionState result;
            private MoodleClient newClient;
            private String newPrivateToken;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                MoodleToken token = newToken != null ? newToken : MoodleCredentialStore.load(siteUrl);
                if (token == null) {
                    result = MoodleSessionState.loggedOut(null);
                    return;
                }
                MoodleClient candidate = new MoodleClient(siteUrl, token.token());
                try {
                    MoodleUser user = fetchUser(candidate);
                    if (newToken != null && !isStale(gen)) {
                        MoodleCredentialStore.save(siteUrl, newToken);
                    }
                    newClient = candidate;
                    newPrivateToken = token.privateToken();
                    result = MoodleSessionState.loggedIn(user);
                }
                catch (MoodleException e) {
                    if (e.isInvalidToken()) {
                        if (newToken == null) {
                            MoodleCredentialStore.clear(siteUrl);
                        }
                        result = MoodleSessionState.loggedOut(newToken == null
                            ? "Platnost přihlášení vypršela. Přihlaste se prosím znovu."
                            : "Moodle token odmítl: " + e.getMessage());
                    }
                    else {
                        String message = "Moodle vrátil chybu: " + e.getMessage();
                        result = newToken == null ? MoodleSessionState.error(message) : MoodleSessionState.loggedOut(message);
                    }
                }
                catch (IOException e) {
                    String message = connectionError(siteUrl, e);
                    result = newToken == null ? MoodleSessionState.error(message) : MoodleSessionState.loggedOut(message);
                }
            }

            @Override
            public void onSuccess() {
                if (isStale(gen)) return;
                client = newClient;
                privateToken = newPrivateToken;
                setState(result);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.warn("Moodle session verification failed", error);
                if (!isStale(gen)) setState(MoodleSessionState.error("Ověření přihlášení selhalo: " + error.getMessage()));
            }
        }.queue();
    }

    private static @NotNull MoodleUser fetchUser(@NotNull MoodleClient client) throws IOException, MoodleException {
        SiteInfo info = client.getSiteInfo();
        String email = null;
        try {
            email = client.getUserEmail(info.userId());
        }
        catch (MoodleException e) {
            if (e.isInvalidToken()) throw e;
            // The e-mail is optional: the service may not allow core_user_get_users_by_field.
            LOG.info("Moodle e-mail not available: " + e.getErrorCode());
        }
        return new MoodleUser(info.userId(), info.username(), info.fullName(), email);
    }

    private static void clearBrowserSession(@NotNull String siteUrl) {
        try {
            // JCEF may run out of process (cef_server); the cookie manager only works once it is connected.
            JBCefApp.getInstance();
            CountDownLatch initialized = new CountDownLatch(1);
            CefApp.getInstance().onInitialization(state -> initialized.countDown());
            if (!initialized.await(15, TimeUnit.SECONDS)) {
                LOG.warn("JCEF not initialized in time, Moodle cookies not cleared");
                return;
            }
            JBCefBrowserBase.getGlobalJBCefCookieManager().deleteCookies(siteUrl, "").get(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (Exception e) {
            LOG.warn("Could not clear Moodle cookies in the embedded browser", e);
        }
    }

    private static @NotNull String connectionError(@NotNull String siteUrl, @NotNull IOException e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "Nelze se spojit se serverem " + siteUrl + " (" + detail + ").";
    }

    private boolean isStale(long gen) {
        return generation.get() != gen;
    }

    private void setState(@NotNull MoodleSessionState newState) {
        state = newState;
        ApplicationManager.getApplication().invokeLater(
            () -> ApplicationManager.getApplication().getMessageBus().syncPublisher(MoodleSessionListener.TOPIC).sessionChanged(newState),
            ModalityState.any());
    }
}
