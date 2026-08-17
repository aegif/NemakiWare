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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A probe that cannot reach the endpoint, or reaches the wrong one, is worse than no probe.
 *
 * <h2>Why this is checked in a test and not left to review</h2>
 *
 * <p>The readiness endpoint only does its job if two pieces of configuration agree with it, and
 * both live outside the Java source where a compiler cannot notice them drifting: the
 * authentication filter has to let an unauthenticated probe through, and each compose file's
 * {@code core} health check has to point at it. Get the first wrong and every probe is a 401 —
 * the orchestrator sees "unhealthy" forever. Get the second wrong and the check passes the
 * instant Tomcat serves anything, which is the behaviour that put cold replicas into rotation
 * in the first place (the first CMIS call still waits ~10s; sixteen concurrent ones queued 8.3s).
 *
 * <p>Both failures look like a working deployment right up until a rolling restart.
 */
class ReadinessProbeWiringTest {

    private static final String PATH = "/rest/all/readiness";
    private static final Path DOCKER = Path.of("../docker");

    /**
     * What actually decides whether the probe needs credentials is web.xml, not the filter body.
     *
     * <p>The first version of this test asserted that {@code AuthenticationFilter.java} contained
     * the readiness path — which passed against a build where that string sat in a bypass branch
     * the filter never reached, because web.xml does not map the filter to this path at all. A
     * source-contains assertion on the wrong file proves nothing; this checks the mapping.
     */
    @Test
    @DisplayName("readiness が認証フィルタの url-pattern に含まれていない (LB は資格情報を持たない)")
    void theFilterIsNotMappedOntoTheProbe() throws IOException {
        String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"),
                StandardCharsets.UTF_8);
        assertFalse(webXml.contains(PATH),
                "web.xml must not map restAuthenticationFilter onto " + PATH + " — the probe has no"
                        + " credentials to offer. If a mapping is added, the filter needs an"
                        + " explicit bypass and this test needs to check that instead.");
        // /rest/all/* is not blanket-mapped: the filter is attached to specific sub-paths
        // (/rest/all/log/*, /rest/all/build-info). A blanket /rest/all/* would swallow readiness.
        assertFalse(webXml.contains("<url-pattern>/rest/all/*</url-pattern>"),
                "a blanket /rest/all/* mapping would put the probe behind authentication");
    }

    @Test
    @DisplayName("core を定義する compose は readiness を叩く (200 を返す何かではなく)")
    void everyCoreServiceProbesReadiness() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(DOCKER)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith("docker-compose")
                    && f.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                String test = coreHealthcheckTest(Files.readString(p, StandardCharsets.UTF_8));
                if (test == null) {
                    continue; // no core service, or no health check declared for it
                }
                checked++;
                if (!test.contains(PATH)) {
                    offenders.add(p.getFileName() + " → " + test);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "these probe something that answers before the instance can serve — point them at "
                        + PATH);
        assertTrue(checked >= 3,
                "expected several compose files to declare a core health check, saw " + checked
                        + " — the scan is probably not finding them any more");
    }

    /** The {@code test:} line of the {@code core} service's health check, or null if absent. */
    private static String coreHealthcheckTest(String yaml) {
        String service = null;
        boolean inCoreHealthcheck = false;
        Pattern serviceLine = Pattern.compile("^  ([\\w-]+):\\s*$");
        for (String line : yaml.split("\n")) {
            Matcher m = serviceLine.matcher(line);
            if (m.matches()) {
                service = m.group(1);
                inCoreHealthcheck = false;
            }
            if ("core".equals(service) && line.trim().equals("healthcheck:")) {
                inCoreHealthcheck = true;
            }
            if (inCoreHealthcheck && line.trim().startsWith("test:")) {
                return line.trim();
            }
        }
        return null;
    }
}
