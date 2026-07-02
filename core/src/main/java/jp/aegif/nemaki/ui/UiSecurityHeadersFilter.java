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
 * <p><b>Enforcing headers</b> (safe for a same-origin, standalone admin SPA):
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — stop MIME sniffing</li>
 *   <li>{@code X-Frame-Options: SAMEORIGIN} — clickjacking (the UI is not
 *       intended to be embedded in a third-party frame)</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin}</li>
 * </ul>
 *
 * <p><b>Content-Security-Policy is shipped in Report-Only mode.</b> The browser
 * reports violations (console / {@code report-uri}) but does NOT block, so it
 * cannot break the SPA. The baseline policy already allows what the
 * React + Ant Design + pdf.js stack needs: inline styles from Ant Design's
 * runtime CSS-in-JS, {@code data:}/{@code blob:} images, and {@code blob:}
 * web workers. Promote it to the enforcing {@code Content-Security-Policy}
 * header only after reviewing reported violations across every UI page; an
 * operator can add a {@code report-uri}/{@code report-to} directive to collect
 * them centrally.
 */
public class UiSecurityHeadersFilter implements Filter {

    /**
     * Report-Only CSP baseline. Kept permissive enough for the current SPA
     * (Ant Design inline styles, pdf.js worker/wasm, blob/data previews) so it
     * surfaces real violations for tuning rather than drowning in expected ones.
     */
    private static final String CSP_REPORT_ONLY =
            "default-src 'self'; "
            + "script-src 'self' 'wasm-unsafe-eval'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; "
            + "font-src 'self' data:; "
            + "worker-src 'self' blob:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'self'; "
            + "base-uri 'self'; "
            + "object-src 'none'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "SAMEORIGIN");
            http.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            http.setHeader("Content-Security-Policy-Report-Only", CSP_REPORT_ONLY);
        }
        chain.doFilter(request, response);
    }
}
