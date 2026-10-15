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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every REST resource is behind the authentication filter but the ones that answer before anyone has logged in (3.4.1,
 * zero-trust P0 — batch 15 review round 1: both reviewers, P2).
 *
 * <p>web.xml maps the filter onto chosen {@code /rest/*} paths, not all of them, and a resource none of them covers is
 * reached with no CallContext at all: {@code /rest/all/audit/metrics} and {@code /rest/all/search-engine} refused an
 * administrator's credentials as "only administrators" — measured on nb33 — and anyone's request reached them. The
 * filter's list of public endpoints is the filter's; which paths the filter sees at all is web.xml's, and this reads it
 * against the class-level {@code @Path} of every resource the REST application scans ({@code jp.aegif.nemaki.rest}).
 */
class RestResourcesAreBehindTheFilterTest {

    /**
     * Outside the filter by design: the load balancer's probe has no credentials ({@code ReadinessProbeWiringTest}), and
     * the login page reads the repository list and the SSO settings before anyone has logged in.
     */
    private static final Set<String> OUTSIDE = Set.of("/rest/all/readiness", "/rest/all/repositories", "/rest/auth/config");

    private static final Pattern MAPPING = Pattern.compile(
            "<filter-mapping>\\s*<filter-name>restAuthenticationFilter</filter-name>\\s*<url-pattern>([^<]+)</url-pattern>");
    private static final Pattern CLASS_PATH = Pattern.compile("(?m)^@Path\\(\"([^\"]*)\"\\)\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*public\\s+(?:final\\s+)?class\\s");

    @Test
    @DisplayName("every REST resource is behind the authentication filter but the probe and the login page's two")
    void everyRestResourceIsBehindTheFilterButThePublicOnes() throws IOException {
        String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"), StandardCharsets.UTF_8);
        String mapped = webXml.replaceAll("(?s)<!--.*?-->", "");
        List<String> patterns = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(mapped);
        while (mapping.find()) {
            patterns.add(mapping.group(1).trim());
        }
        assertTrue(patterns.size() > 10, "the filter's mappings were not read: " + patterns);

        Set<String> resources = new TreeSet<>();
        Set<String> outside = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java/jp/aegif/nemaki/rest"))) {
            for (Path source : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher path = CLASS_PATH.matcher(Files.readString(source, StandardCharsets.UTF_8));
                if (!path.find()) {
                    continue;
                }
                String full = ("/rest/" + path.group(1)).replaceAll("/+", "/").replaceAll("\\{[^}]+\\}", "x")
                        .replaceAll("/$", "");
                resources.add(full);
                if (!covered(full, patterns)) {
                    outside.add(full);
                }
            }
        }
        assertTrue(resources.size() > 20, "the REST resources were not read: " + resources);
        assertEquals(OUTSIDE, outside, "REST resources outside the authentication filter — a resource here gets no "
                + "CallContext, refuses administrators, and is reached by anyone's request");
    }

    /** Whether a url-pattern of the servlet specification covers the path: an exact match, or a "/…/*" prefix. */
    private static boolean covered(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
            } else if (pattern.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
