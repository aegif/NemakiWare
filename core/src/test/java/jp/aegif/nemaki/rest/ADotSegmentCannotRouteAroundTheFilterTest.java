/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code /rest/*} request cannot reach a resource by a path the authentication filter was not chosen for (R163).
 *
 * <p>Each case drives the filter's entry point with the request a Tomcat container hands over: the path as sent
 * ({@code getRequestURI}, undecoded) and the path it mapped by ({@code getServletPath} and {@code getPathInfo},
 * decoded and normalised). The refused cases are the ones measured on nb33 before the fix — every one of them reached
 * the group resource with no credentials. The passed cases are what a client legitimately sends and must not be
 * refused: refusing them is the same weight of defect as letting the others through.
 */
class ADotSegmentCannotRouteAroundTheFilterTest {

    /** What the filter answered: the status it sent, or that it passed the request on. */
    private record Answer(int status, boolean passedOn) { }

    private static Answer drive(String requestUri, String servletPath, String pathInfo)
            throws IOException, ServletException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(requestUri);
        when(request.getContextPath()).thenReturn("/core");
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicInteger status = new AtomicInteger(0);
        doAnswer(call -> {
            status.set(call.getArgument(0));
            return null;
        }).when(response).sendError(anyInt(), anyString());
        doAnswer(call -> {
            status.set(call.getArgument(0));
            return null;
        }).when(response).sendError(anyInt());
        AtomicBoolean passedOn = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedOn.set(true);
        new RestPathAgreementFilter().doFilter(request, response, chain);
        return new Answer(status.get(), passedOn.get());
    }

    private static void assertRefused(String requestUri, String servletPath, String pathInfo)
            throws IOException, ServletException {
        Answer answer = drive(requestUri, servletPath, pathInfo);
        assertFalse(answer.passedOn(), requestUri + " was mapped as " + servletPath + pathInfo + " and passed on — "
                + "Jersey routes it by the path as sent, around the filters chosen for the mapped one");
        assertEquals(400, answer.status(), requestUri + " was not passed on, but not refused with 400 either");
    }

    private static void assertPassedOn(String requestUri, String servletPath, String pathInfo)
            throws IOException, ServletException {
        Answer answer = drive(requestUri, servletPath, pathInfo);
        assertTrue(answer.passedOn(), requestUri + " names the same segments as " + servletPath + pathInfo
                + " and was refused with " + answer.status() + " — an over-refusal of a request a client sends");
        assertEquals(0, answer.status(), requestUri + " was passed on and also answered " + answer.status());
    }

    @Test
    @DisplayName("a dot segment, in every spelling measured on nb33, is refused before any resource")
    void aDotSegmentIsRefusedInEverySpelling() throws Exception {
        assertRefused("/core/rest/repo/../group/list", "/rest", "/group/list");
        assertRefused("/core/rest/repo/%2e%2e/group/list", "/rest", "/group/list");
        assertRefused("/core/rest/repo/%2E%2E/group/list", "/rest", "/group/list");
        assertRefused("/core/rest/repo/.%2e/group/list", "/rest", "/group/list");
        assertRefused("/core/rest/repo;x=1/../group/list", "/rest", "/group/list");
        assertRefused("/core/rest/./group/list", "/rest", "/group/list");
        assertRefused("/core/rest/repo/../importexport/export/x", "/rest", "/importexport/export/x");
    }

    @Test
    @DisplayName("a dot segment that normalises back under the same mapping is still refused")
    void aDotSegmentThatLandsInsideTheMappingIsRefusedToo() throws Exception {
        // /rest/repo/bedroom/../bedroom/type normalises to a path the authentication filter covers, so this one is
        // not a bypass — but the two views still disagree, and the rule is the disagreement, not a list of the
        // disagreements that happen to be bypasses today.
        assertRefused("/core/rest/repo/bedroom/../bedroom/type", "/rest", "/repo/bedroom/type");
    }

    @Test
    @DisplayName("an encoded slash that the container would split is refused")
    void anEncodedSlashIsRefused() throws Exception {
        assertRefused("/core/rest/repo/a%2Fb/group/list", "/rest", "/repo/a/b/group/list");
    }

    @Test
    @DisplayName("a path that cannot be decoded is refused: could not compare is not agreed")
    void aPathThatCannotBeReadIsRefused() throws Exception {
        assertRefused("/core/rest/repo/bedroom/node/%zz", "/rest", "/repo/bedroom/node/%zz");
        assertRefused("/core/rest/repo/bedroom/node/%E6%97", "/rest", "/repo/bedroom/node/x");
        assertRefused(null, "/rest", "/repo/bedroom/type");
    }

    @Test
    @DisplayName("what a client legitimately sends is passed on")
    void whatAClientSendsIsPassedOn() throws Exception {
        assertPassedOn("/core/rest/repo/bedroom/group/list", "/rest", "/repo/bedroom/group/list");
        assertPassedOn("/core/rest/repo/bedroom/node/abc%20def", "/rest", "/repo/bedroom/node/abc def");
        assertPassedOn("/core/rest/repo/bedroom/node/a+b", "/rest", "/repo/bedroom/node/a+b");
        assertPassedOn("/core/rest/repo/bedroom/node/%E6%97%A5%E6%9C%AC", "/rest", "/repo/bedroom/node/日本");
        assertPassedOn("/core/rest/repo/bedroom/node/a%3Bb", "/rest", "/repo/bedroom/node/a;b");
        assertPassedOn("/core/rest/all/repositories", "/rest/all/repositories", null);
    }

    @Test
    @DisplayName("path parameters and doubled slashes, which the container drops and Jersey ignores, are passed on")
    void whatTheContainerDropsIsPassedOn() throws Exception {
        assertPassedOn("/core/rest/repo/bedroom/type;jsessionid=ABC", "/rest", "/repo/bedroom/type");
        assertPassedOn("/core/rest//repo/bedroom/type", "/rest", "/repo/bedroom/type");
        assertPassedOn("/core/rest/repo/bedroom/type/", "/rest", "/repo/bedroom/type/");
    }

    @Test
    @DisplayName("an unescaped UTF-8 byte, handed over one char per byte, is read back as the character it was")
    void anUnescapedUtf8ByteIsReadAsItsCharacter() throws Exception {
        String sentRaw = new String("/core/rest/repo/bedroom/node/日本".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
        assertPassedOn(sentRaw, "/rest", "/repo/bedroom/node/日本");
    }

    @Test
    @DisplayName("an /odata/* request whose repository segment is undone by a dot segment is refused")
    void anOdataDotSegmentIsRefused() throws Exception {
        // Authenticated against the repository the normalised path names, served from the one the path as sent names.
        assertRefused("/core/odata/canopy/../bedroom/Documents", "/odata", "/bedroom/Documents");
        assertPassedOn("/core/odata/bedroom/Documents", "/odata", "/bedroom/Documents");
    }

    @Test
    @DisplayName("web.xml puts the filter on the whole of /odata/* as well")
    void theFilterIsOnTheWholeOfOdata() throws IOException {
        String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        assertTrue(Pattern.compile("<filter-mapping>\\s*<filter-name>restPathAgreementFilter</filter-name>\\s*"
                        + "<url-pattern>/odata/\\*</url-pattern>\\s*</filter-mapping>").matcher(webXml).find(),
                "the path agreement filter is not mapped onto /odata/* — the authentication filter there decides the "
                        + "repository from the normalised path and ODataServlet from the path as sent");
    }

    @Test
    @DisplayName("web.xml puts the filter on the whole of /rest/*")
    void theFilterIsOnTheWholeOfRest() throws IOException {
        String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        assertTrue(Pattern.compile("<filter>\\s*<filter-name>restPathAgreementFilter</filter-name>\\s*"
                        + "<filter-class>jp\\.aegif\\.nemaki\\.rest\\.RestPathAgreementFilter</filter-class>")
                        .matcher(webXml).find(),
                "web.xml no longer declares the path agreement filter, so nothing refuses a dot segment");
        assertTrue(Pattern.compile("<filter-mapping>\\s*<filter-name>restPathAgreementFilter</filter-name>\\s*"
                        + "<url-pattern>/rest/\\*</url-pattern>\\s*</filter-mapping>").matcher(webXml).find(),
                "the path agreement filter is not mapped onto /rest/* — a narrower mapping is the very thing a "
                        + "normalised path falls outside of");
    }
}
