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
package jp.aegif.nemaki.util.xml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CI gate "XML parser hardening" (.github/workflows/security-scan.yml, job xml-hardening),
 * restated as a unit lock so it is measured HERE — by the unit suite and by the negative
 * controls — and not only after a push.
 *
 * <p>The gate exists so the XXE hardening cannot drift: a new sink that copies the weaker
 * template, or drops a {@code setFeature}, is caught by the one rule "construct XML parsers only
 * in {@link SecureXml}". The E-ARK {@code SipVerifier} built its own factory for a year because
 * it needed one that admits an internal DOCTYPE; the gate found it at the first push of this
 * branch, and the factory moved into SecureXml. This lock keeps the next one from repeating that.
 */
class XmlParsersAreConstructedViaSecureXmlTest {

    /** The gate's pattern, verbatim (POSIX class widened to Java's). */
    private static final Pattern RAW_CONSTRUCTION = Pattern.compile(
            "DocumentBuilderFactory\\.newInstance|new\\s+SAXReader\\(|XMLInputFactory\\.newInstance"
                    + "|SAXParserFactory\\.newInstance");

    /** The gate's roots, relative to the core module (where the unit suite runs). */
    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"), Path.of("../common/src/main/java"));

    @Test
    @DisplayName("every XML parser in core and common main sources is constructed by SecureXml")
    void everyParserIsConstructedBySecureXml() throws IOException {
        List<String> hits = new ArrayList<>();
        int filesRead = 0;
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), "the gate's root " + root.toAbsolutePath()
                    + " is not a directory here, so nothing below is measured");
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!file.toString().endsWith(".java")) {
                        continue;
                    }
                    filesRead++;
                    if (file.toString().replace('\\', '/').endsWith("util/xml/SecureXml.java")) {
                        continue;
                    }
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (RAW_CONSTRUCTION.matcher(lines.get(i)).find()) {
                            hits.add(root.relativize(file) + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        // A walk that found almost nothing measured nothing: the roots above are where the
        // product lives (959 sources between them on 2026-09-28), so a count in the low
        // hundreds means a moved root, not a smaller product.
        assertTrue(filesRead > 500, "only " + filesRead + " .java files were read under " + ROOTS
                + "; the walk did not reach the product");
        assertEquals(List.of(), hits,
                "XML parsers are constructed outside jp.aegif.nemaki.util.xml.SecureXml. The CI "
                        + "gate (security-scan.yml, XML parser hardening) fails on exactly these "
                        + "lines; route them through SecureXml — the DOCTYPE-admitting variant "
                        + "exists for package XML");
    }
}
