package cz.vse.moodle.vpl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import cz.vse.moodle.api.MoodleClient;
import cz.vse.moodle.api.MoodleException;
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
    private @Nullable VplWebSession session;

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
            throw new MoodleException("notloggedin", "Nejste přihlášeni do Moodle. Přihlaste se v okně Moodle na kartě Účet.");
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
        String privateToken = moodle.getPrivateToken();
        if (privateToken == null) {
            throw new MoodleException("noprivatetoken",
                "Úlohy VPL vyžadují přihlášení přes tlačítko „Přihlásit se“ (SSO). Ručně vložený token nestačí. " +
                "Odhlaste se a přihlaste znovu.");
        }
        session = null;
        VplWebSession fresh = VplWebSession.open(client, userId, privateToken);
        session = fresh;
        return fresh;
    }

    public synchronized void invalidate() {
        session = null;
    }

    @Override
    public void dispose() {
        invalidate();
    }
}
