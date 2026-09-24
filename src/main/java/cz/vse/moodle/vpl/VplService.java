package cz.vse.moodle.vpl;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
import cz.vse.moodle.auth.MoodleCredentialStore;
import cz.vse.moodle.session.MoodleSessionListener;
import cz.vse.moodle.session.MoodleSessionService;
import cz.vse.moodle.session.MoodleSessionState;
import cz.vse.moodle.vpl.api.VplApi;
import cz.vse.moodle.vpl.api.VplWebSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Shares one Moodle web session (see {@link VplWebSession}) between all projects. Moodle issues auto-login keys
 * at most every 6 minutes, so the session is kept until it expires or the user logs out.
 */
@Service(Service.Level.APP)
public final class VplService implements Disposable {
    static final String AUTOLOGIN_LOCKOUT = "autologinkeygenerationlockout";
    private static final long AUTOLOGIN_INTERVAL_MS = 6 * 60_000;
    private static final String LAST_KEY_REQUEST = "cz.vse.moodle.vpl.lastAutologinKeyRequest";

    private @Nullable VplWebSession session;
    private long lastKeyRequest;

    public VplService() {
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(MoodleSessionListener.TOPIC,
            (MoodleSessionListener) state -> {
                if (state.status() != MoodleSessionState.Status.LOGGED_IN) {
                    invalidate();
                }
            });
    }

    public static @NotNull VplService getInstance() {
        return ApplicationManager.getApplication().getService(VplService.class);
    }

    /** API bound to the currently logged-in user. Use it from background threads only. */
    public @NotNull VplApi getApi() {
        return new VplApi(this::session);
    }

    /** The logged-in REST client, or an exception with a message for the user. */
    public static @NotNull MoodleClient requireClient() throws MoodleException {
        MoodleClient client = MoodleSessionService.getInstance().getClient();
        if (client == null) {
            throw new MoodleException("notloggedin", "Nejste přihlášeni do Moodle. Přihlaste se v okně Moodle na kartě Student.");
        }
        return client;
    }

    private synchronized @NotNull VplWebSession session(@Nullable VplWebSession expired) throws IOException, MoodleException {
        MoodleSessionService moodle = MoodleSessionService.getInstance();
        MoodleClient client = requireClient();
        MoodleSessionState state = moodle.getState();
        if (state.user() == null) {
            throw new MoodleException("notloggedin", "Nejste přihlášeni do Moodle.");
        }
        long userId = state.user().id();
        VplWebSession current = session;
        if (current != null && current != expired
            && current.getUserId() == userId && current.getSiteUrl().equals(client.getSiteUrl())) {
            return current;
        }
        session = null;
        String siteUrl = client.getSiteUrl();
        if (expired == null) {
            // After an IDE restart: reuse the stored session instead of spending an auto-login key.
            String stored = MoodleCredentialStore.loadWebSession(siteUrl);
            VplWebSession restored = stored != null ? VplWebSession.restore(stored, siteUrl, userId) : null;
            if (restored != null && restored.isAlive()) {
                session = restored;
                return restored;
            }
        }
        String privateToken = moodle.getPrivateToken();
        if (privateToken == null) {
            throw new MoodleException("noprivatetoken",
                "Úlohy VPL vyžadují přihlášení přes tlačítko „Přihlásit se“ (SSO). Ručně vložený token nestačí. " +
                "Odhlaste se a přihlaste znovu.");
        }
        VplWebSession fresh;
        try {
            fresh = VplWebSession.open(client, userId, privateToken);
        }
        catch (MoodleException e) {
            if (AUTOLOGIN_LOCKOUT.equals(e.getErrorCode())) {
                throw new MoodleException(AUTOLOGIN_LOCKOUT, lockoutMessage());
            }
            throw e;
        }
        lastKeyRequest = System.currentTimeMillis();
        PropertiesComponent.getInstance().setValue(LAST_KEY_REQUEST, Long.toString(lastKeyRequest));
        MoodleCredentialStore.saveWebSession(siteUrl, fresh.serialize());
        session = fresh;
        return fresh;
    }

    /** Moodle hands out an auto-login key once per 6 minutes; tell the user how long to wait if we know. */
    private @NotNull String lockoutMessage() {
        long last = lastKeyRequest > 0 ? lastKeyRequest
            : PropertiesComponent.getInstance().getLong(LAST_KEY_REQUEST, 0);
        long waitMs = last + AUTOLOGIN_INTERVAL_MS - System.currentTimeMillis();
        String when = waitMs > 0 && waitMs <= AUTOLOGIN_INTERVAL_MS
            ? "za " + Math.max(1, (waitMs + 59_999) / 60_000) + " min" : "za několik minut";
        return "Moodle povoluje nové přihlášení do webu jen jednou za 6 minut a to poslední bylo před chvílí "
            + "(např. v jiném okně IDE nebo před restartem). Zkuste to prosím znovu " + when + ".";
    }

    public synchronized void invalidate() {
        session = null;
    }

    @Override
    public void dispose() {
        invalidate();
    }
}
