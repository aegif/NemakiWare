package jp.aegif.nemaki.ui;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds security response headers to the React SPA served under {@code /ui/*}.
 *
 * <p><b>Enforcing headers</b> (always on; safe for a same-origin, standalone
 * admin SPA):
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code X-Frame-Options: SAMEORIGIN} (clickjacking; the UI is not
 *       intended to be embedded in a third-party frame)</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 * </ul>
 *
 * <h3>Content-Security-Policy</h3>
 * The CSP was tuned by walking the running app: the core SPA (login, document
 * list, Ant Design, pdf.js) is entirely same-origin — pdf.js loads its worker
 * from {@code /core/ui/pdf-worker/...} ({@code 'self'}), Ant Design injects
 * inline styles at runtime ({@code style-src 'unsafe-inline'}). The only
 * cross-origin traffic is the optional Google Drive / Microsoft (MSAL) /
 * Purview integrations, whose browser-side {@code fetch}/token calls need their
 * service origins on {@code connect-src} (and MSAL's silent-renew iframe on
 * {@code frame-src}). Those origins are included by default so the shipped
 * cloud features work.
 *
 * <p><b>Mode</b> ({@code -Dnemakiware.ui.csp.mode}):
 * <ul>
 *   <li>{@code report-only} (default): ship {@code Content-Security-Policy-
 *       Report-Only} — the browser reports violations but does not block, so it
 *       cannot break the app. Upgrade-safe. Review reported violations for your
 *       deployment (esp. custom OIDC/SAML/cloud origins), then switch to
 *       {@code enforce}.</li>
 *   <li>{@code enforce}: ship the enforcing {@code Content-Security-Policy}.</li>
 *   <li>{@code off}: emit no CSP (the three headers above still apply).</li>
 * </ul>
 *
 * <p><b>Extra origins</b> ({@code -Dnemakiware.ui.csp.extraOrigins}): a
 * space-separated list of additional origins appended to {@code connect-src}
 * and {@code frame-src}, for a custom OIDC/SAML IdP, a non-global Azure cloud
 * (e.g. {@code https://login.microsoftonline.us}), or any other browser-side
 * integration a deployment adds.
 */
public class UiSecurityHeadersFilter implements Filter {

    /** Browser-side origins the shipped UI talks to (Google Drive / MSAL / Graph / Purview). */
    private static final String CLOUD_CONNECT_ORIGINS =
            "https://www.googleapis.com https://accounts.google.com "
            + "https://graph.microsoft.com https://login.microsoftonline.com "
            + "https://*.purview.azure.com";

    /** Origins that may be framed (MSAL silent-renew iframe, Google). */
    private static final String CLOUD_FRAME_ORIGINS =
            "https://accounts.google.com https://login.microsoftonline.com";

    private String cspHeaderName;   // null when mode=off
    private String cspHeaderValue;  // null when mode=off

    @Override
    public void init(jakarta.servlet.FilterConfig filterConfig) {
        String mode = System.getProperty("nemakiware.ui.csp.mode", "report-only").trim().toLowerCase();
        String extra = System.getProperty("nemakiware.ui.csp.extraOrigins", "").trim();
        String extraSuffix = extra.isEmpty() ? "" : " " + extra;

        if ("off".equals(mode)) {
            cspHeaderName = null;
            cspHeaderValue = null;
            return;
        }
        cspHeaderName = "enforce".equals(mode)
                ? "Content-Security-Policy"
                : "Content-Security-Policy-Report-Only";
        cspHeaderValue =
                "default-src 'self'; "
                + "script-src 'self' 'wasm-unsafe-eval'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; "
                + "font-src 'self' data:; "
                + "worker-src 'self' blob:; "
                + "connect-src 'self' " + CLOUD_CONNECT_ORIGINS + extraSuffix + "; "
                + "frame-src 'self' " + CLOUD_FRAME_ORIGINS + extraSuffix + "; "
                + "frame-ancestors 'self'; "
                + "base-uri 'self'; "
                + "object-src 'none'";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "SAMEORIGIN");
            http.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            if (cspHeaderName != null) {
                http.setHeader(cspHeaderName, cspHeaderValue);
            }
        }
        chain.doFilter(request, response);
    }
}
