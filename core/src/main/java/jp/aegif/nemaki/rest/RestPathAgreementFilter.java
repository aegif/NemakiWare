/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses a {@code /rest/*} request whose path, as sent, names different segments from the path the container mapped it
 * by (R163).
 *
 * <p>The two disagree when the path carries a dot segment. The container normalises {@code /rest/repo/../group/list} to
 * {@code /rest/group/list} and applies the filters mapped onto that, and none of the authentication filter's
 * url-patterns covers it; Jersey routes on the path as sent and matches {@code /repo/{repositoryId}/group/list} with a
 * repository id of {@code ..}. Measured on nb33 before this filter: with no credentials, {@code ..}, {@code %2e%2e},
 * {@code %2E%2E}, {@code .%2e} and {@code repo;x=1/..} all reached the group resource, and
 * {@code repo/../importexport/export/x} got as far as reading the content.
 *
 * <p>NOT a list of bad shapes. It compares the two views segment by segment: the path as sent, with each segment's path
 * parameters cut and the rest percent-decoded as UTF-8, against the context path, servlet path and path info the
 * container hands the application. Empty segments are dropped on both sides, because the container collapses
 * {@code //} and that changes nothing about which filter applies. Any other difference — a dot segment in any spelling,
 * an encoded slash, a segment the container dropped or added — is a request the filters were chosen for by one path and
 * the resource by another, and it is refused before either runs.
 *
 * <p>A path that cannot be read — a malformed escape, bytes that are not UTF-8, no request URI at all — is refused too:
 * «could not compare» is not «they agree».
 */
public class RestPathAgreementFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RestPathAgreementFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse httpResponse
                && !theRoutedPathIsTheMappedPath(http)) {
            log.warn("Refused a REST request whose path as sent does not name the path it was mapped by (R163): {}",
                    String.valueOf(http.getRequestURI()).replaceAll("[\\r\\n]", "_"));
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "The request path contains segments the server normalises away (such as '..'); send the path "
                            + "without them.");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // no-op
    }

    /** Whether the path as sent and the path the container mapped name the same non-empty segments. */
    static boolean theRoutedPathIsTheMappedPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (requestUri == null || contextPath == null) {
            return false;
        }
        List<String> asSent = segmentsAsSent(requestUri);
        // The context path is not decoded by the container (Servlet specification), so it is read like the request URI.
        List<String> context = segmentsAsSent(contextPath);
        if (asSent == null || context == null) {
            return false;
        }
        List<String> mapped = new ArrayList<>(context);
        mapped.addAll(segments(request.getServletPath()));
        mapped.addAll(segments(request.getPathInfo()));
        return asSent.equals(mapped);
    }

    /** The non-empty segments of a decoded path. */
    private static List<String> segments(String decoded) {
        List<String> segments = new ArrayList<>();
        if (decoded == null) {
            return segments;
        }
        for (String segment : decoded.split("/", -1)) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /**
     * The non-empty segments of a path as sent: each segment's path parameters cut at its first literal {@code ;} (an
     * encoded {@code %3B} is data and stays), then percent-decoded as UTF-8. {@code null} when it cannot be decoded.
     */
    private static List<String> segmentsAsSent(String raw) {
        List<String> segments = new ArrayList<>();
        for (String segment : raw.split("/", -1)) {
            int parameters = segment.indexOf(';');
            String name = parameters >= 0 ? segment.substring(0, parameters) : segment;
            String decoded = percentDecode(name);
            if (decoded == null) {
                return null;
            }
            if (!decoded.isEmpty()) {
                segments.add(decoded);
            }
        }
        return segments;
    }

    /** Path decoding — {@code +} is a plus sign here, not a space. {@code null} on a malformed escape or non-UTF-8. */
    private static String percentDecode(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                if (i + 2 >= encoded.length()) {
                    return null;
                }
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes.write((high << 4) | low);
                i += 2;
            } else if (c <= 0xFF) {
                // Tomcat hands the request URI over as its bytes read one per char (ISO-8859-1), so an unescaped
                // non-ASCII byte arrives as that char; putting the byte back is what lets UTF-8 decode it.
                bytes.write(c);
            } else {
                byte[] literal = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(literal, 0, literal.length);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
