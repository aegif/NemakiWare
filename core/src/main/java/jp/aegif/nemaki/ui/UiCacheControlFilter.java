package jp.aegif.nemaki.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * How a browser may keep what it read under {@code /ui/*} (R348, 3.4.2).
 *
 * <p>Every entry of the WAR carries one fixed date (1980-02-01), so the {@code Last-Modified} Tomcat sends never changes
 * from one version to the next, and its default weak {@code ETag} (length and date) stays the same whenever a file keeps
 * its length — {@code index.html} did, 503 bytes in two versions. With no {@code Cache-Control} a browser that had read
 * the previous version kept its {@code index.html}, and with it the previous bundle, after an upgrade and a reload.
 * The default servlet answers a strong {@code ETag} computed from the content ({@code useStrongETags} in web.xml); this
 * filter decides the rest:
 * <ul>
 *   <li>{@code /ui/assets/*} — names that carry a hash of their content: {@code public, max-age=31536000, immutable}.
 *       An error is answered {@code no-cache, must-revalidate} instead, so a name a newer {@code index.html} asks of a
 *       server that does not have it yet is not kept as missing for a year.</li>
 *   <li>every other {@code /ui/*} — {@code index.html}, the SPA fallback, the sign-in callbacks, {@code auth-popup-entry.js},
 *       the pdf worker, logos and help images: {@code no-cache, must-revalidate}, and no {@code Last-Modified} is sent.
 *       The fixed date validates no request (R351): {@code If-Modified-Since} and {@code If-Unmodified-Since} are
 *       not shown to the servlet, and a {@code Range} is not shown either — the whole current content is sent, 200 —
 *       when the request also carries one of those two, or an {@code If-Range} that is not exactly one strong entity
 *       tag (a date, a weak tag, anything else, or more than one). A {@code Range} with one strong tag in
 *       {@code If-Range} keeps its meaning (the range when the tag is the current one, the whole content when it is
 *       not), and a {@code Range} with no condition is not touched. {@code If-Match} and {@code If-None-Match} are
 *       shown as they came, so a 304 or a 412 the strong tag decides stays one.</li>
 * </ul>
 * The path is the one the container mapped by (servlet path and path info: decoded and normalised), so it names the
 * resource that is served.
 */
public class UiCacheControlFilter implements Filter {

    /** For a name that carries a hash of its content: the same name never answers other bytes. */
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /** For everything else under /ui: kept, but asked again every time before it is used. */
    static final String REVALIDATE = "no-cache, must-revalidate";

    private static final String HASHED_ASSETS = "/ui/assets/";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse answer)) {
            chain.doFilter(request, response);
            return;
        }
        String pathInfo = http.getPathInfo();
        String path = http.getServletPath() + (pathInfo == null ? "" : pathInfo);
        if (path.startsWith(HASHED_ASSETS)) {
            chain.doFilter(http, new HashedAssetResponse(answer));
            return;
        }
        answer.setHeader("Cache-Control", REVALIDATE);
        chain.doFilter(new NoDateValidatorRequest(http), new NoLastModifiedResponse(answer));
    }

    /** Immutable on an answer, revalidated on an error. */
    static final class HashedAssetResponse extends HttpServletResponseWrapper {
        HashedAssetResponse(HttpServletResponse response) {
            super(response);
            response.setHeader("Cache-Control", IMMUTABLE);
        }

        private void erroneous(int status) {
            if (status >= 400) {
                super.setHeader("Cache-Control", REVALIDATE);
            }
        }

        @Override
        public void setStatus(int sc) {
            erroneous(sc);
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            erroneous(sc);
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            erroneous(sc);
            super.sendError(sc, msg);
        }
    }

    /** The request as the servlet sees it, without the validators that only the fixed date could answer. */
    static final class NoDateValidatorRequest extends HttpServletRequestWrapper {
        /** Range and If-Range are not shown either, so the whole current content is sent. */
        private final boolean wholeContent;

        NoDateValidatorRequest(HttpServletRequest request) {
            super(request);
            // Read from the request as it came, every value of each, before anything is hidden.
            wholeContent = present(request, "If-Modified-Since")
                    || present(request, "If-Unmodified-Since")
                    || (present(request, "If-Range") && !oneStrongTag(request.getHeaders("If-Range")));
        }

        private static boolean present(HttpServletRequest request, String name) {
            Enumeration<String> values = request.getHeaders(name);
            return (values != null && values.hasMoreElements()) || request.getHeader(name) != null;
        }

        /**
         * Whether the values are exactly one strong entity tag (RFC 9110 8.8.3: a quote, {@code etagc}, a quote) — not
         * a date, not a weak tag, not anything else, and not more than one. The empty tag {@code ""} is not one either:
         * no resource here carries it, and Tomcat refuses it with 400 where a tag that does not match sends the whole.
         */
        static boolean oneStrongTag(Enumeration<String> values) {
            if (values == null || !values.hasMoreElements()) {
                return false;
            }
            String value = values.nextElement().trim();
            if (values.hasMoreElements()) {
                return false;
            }
            if (value.length() < 3 || value.charAt(0) != '"') {
                return false;
            }
            if (value.charAt(value.length() - 1) != '"') {
                return false;
            }
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c != 0x21 && (c < 0x23 || c > 0x7E) && (c < 0x80 || c > 0xFF)) {
                    return false;
                }
            }
            return true;
        }

        private boolean hidden(String name) {
            if (name == null) {
                return false;
            }
            if ("If-Modified-Since".equalsIgnoreCase(name)) {
                return true;
            }
            if ("If-Unmodified-Since".equalsIgnoreCase(name)) {
                return true;
            }
            return wholeContent && ("If-Range".equalsIgnoreCase(name) || "Range".equalsIgnoreCase(name));
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return hidden(name) ? -1L : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return hidden(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            for (Enumeration<String> all = super.getHeaderNames(); all != null && all.hasMoreElements(); ) {
                String name = all.nextElement();
                if (!hidden(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }

    /** The answer without the fixed date, so no client keeps it as a validator. */
    static final class NoLastModifiedResponse extends HttpServletResponseWrapper {
        NoLastModifiedResponse(HttpServletResponse response) {
            super(response);
        }

        private static boolean lastModified(String name) {
            return "Last-Modified".equalsIgnoreCase(name);
        }

        @Override
        public void setDateHeader(String name, long date) {
            if (!lastModified(name)) {
                super.setDateHeader(name, date);
            }
        }

        @Override
        public void addDateHeader(String name, long date) {
            if (!lastModified(name)) {
                super.addDateHeader(name, date);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if (!lastModified(name)) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!lastModified(name)) {
                super.addHeader(name, value);
            }
        }
    }
}
