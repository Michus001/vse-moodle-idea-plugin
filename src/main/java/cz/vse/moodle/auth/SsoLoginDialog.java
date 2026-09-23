package cz.vse.moodle.auth;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefClient;
import com.intellij.util.ui.JBUI;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded browser that walks the user through the Moodle SSO login and intercepts the
 * {@code <scheme>://token=...} redirect from launch.php.
 * <p>
 * Flow: launch.php is opened first so that Moodle stores the passport in the {@code tool_mobile_launch}
 * cookie. On sites where launch.php refuses to start the login (login type "in the app"), the browser is
 * sent to the SSO provider; after login Moodle returns to launch.php, which then issues the token.
 * <p>
 * Show with {@link #showAndGet()}; afterwards read {@link #getToken()} or {@link #getError()}.
 */
public final class SsoLoginDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(SsoLoginDialog.class);

    private final SsoLoginRequest request;
    private final JBCefBrowser browser;
    private final JBLabel status = new JBLabel("Přihlaste se svým účtem VŠE. Po přihlášení se okno samo zavře.");
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean sentToLogin = new AtomicBoolean();
    private volatile @Nullable MoodleToken token;
    private volatile @Nullable String error;

    public SsoLoginDialog(@Nullable Project project, @NotNull SsoLoginRequest request) {
        super(project, true);
        this.request = request;
        setTitle("Přihlášení do Moodle VŠE");

        JBCefClient client = JBCefApp.getInstance().createClient();
        Disposer.register(getDisposable(), client);
        browser = JBCefBrowser.createBuilder().setClient(client).build();
        // Registered after the client, so it is disposed before it.
        Disposer.register(getDisposable(), browser);

        // JBCefClient's add*Handler multiplexes handlers per browser, so we don't replace
        // handlers JBCefBrowser installs on the underlying CefClient for itself.
        CefBrowser cefBrowser = browser.getCefBrowser();
        client.addRequestHandler(new RequestHandler(), cefBrowser);
        client.addLoadHandler(new LoadHandler(), cefBrowser);
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                if (frame == null || frame.isMain()) {
                    tryCapture(url);
                }
            }
        }, cefBrowser);

        init();
        browser.loadURL(request.launchUrl());
    }

    public @Nullable MoodleToken getToken() {
        return token;
    }

    public @Nullable String getError() {
        return error;
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(status, BorderLayout.NORTH);
        panel.add(browser.getComponent(), BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(900, 700));
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "cz.vse.moodle.SsoLoginDialog";
    }

    /**
     * Called from CEF threads. Returns true when {@code url} is the token redirect, which must then be cancelled.
     */
    private boolean tryCapture(@Nullable String url) {
        if (url == null || !LaunchTokenParser.isLaunchRedirect(url)) {
            return false;
        }
        if (!finished.compareAndSet(false, true)) {
            return true;
        }
        try {
            token = LaunchTokenParser.parse(url, request.passport(), request.hashSiteUrls());
        }
        catch (InvalidLaunchTokenException e) {
            LOG.warn("Rejected Moodle launch token: " + e.getMessage());
            error = e.getMessage();
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!isDisposed()) {
                close(OK_EXIT_CODE);
            }
        }, ModalityState.any());
        return true;
    }

    private void onMainFrameLoaded(@NotNull String url) {
        if (finished.get() || !request.isLaunchPage(url)) {
            return;
        }
        // Still on launch.php, i.e. it showed an error page instead of redirecting.
        if (sentToLogin.compareAndSet(false, true)) {
            // First visit: expected on sites that don't allow browser login. The passport is now stored in the
            // tool_mobile_launch cookie and Moodle returns to launch.php after a successful login.
            browser.loadURL(request.loginUrl());
        }
        else {
            ApplicationManager.getApplication().invokeLater(() -> status.setText(
                "Moodle odmítl vydat token (viz stránka níže). Zkuste přihlášení zopakovat, případně vložte token ručně."),
                ModalityState.any());
        }
    }

    private final class RequestHandler extends CefRequestHandlerAdapter {
        private final CefResourceRequestHandler resourceHandler = new CefResourceRequestHandlerAdapter() {
            @Override
            public void onResourceRedirect(CefBrowser browser, CefFrame frame, CefRequest request, CefResponse response,
                                           StringRef newUrl) {
                if (tryCapture(newUrl.get())) {
                    newUrl.set("about:blank");
                }
            }

            @Override
            public void onProtocolExecution(CefBrowser browser, CefFrame frame, CefRequest request, BoolRef allowOsExecution) {
                // Never let the OS open the URL (e.g. an installed Moodle desktop app).
                allowOsExecution.set(false);
                tryCapture(request.getURL());
            }
        };

        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean userGesture,
                                      boolean isRedirect) {
            return tryCapture(request.getURL());
        }

        @Override
        public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame, CefRequest request,
                                                                   boolean isNavigation, boolean isDownload,
                                                                   String requestInitiator, BoolRef disableDefaultHandling) {
            return resourceHandler;
        }
    }

    private final class LoadHandler extends CefLoadHandlerAdapter {
        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            if (frame != null && frame.isMain()) {
                String url = frame.getURL();
                if (!tryCapture(url) && url != null) {
                    onMainFrameLoaded(url);
                }
            }
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode, String errorText,
                                String failedUrl) {
            // Unknown schemes typically end up here (ERR_UNKNOWN_URL_SCHEME / ERR_ABORTED).
            tryCapture(failedUrl);
        }
    }
}
