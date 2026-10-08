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
package jp.aegif.nemaki.rest.eark;

import jp.aegif.nemaki.rest.BuildInfoResource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SIP names the software that wrote it with the version of the build that wrote it.
 *
 * <p>The METS header names the creating software ({@code agent ROLE="CREATOR" TYPE="OTHER"
 * OTHERTYPE="SOFTWARE"}) and carries its version in a note ({@code csip:NOTETYPE="SOFTWARE
 * VERSION"}). The version was the manifest's {@code Implementation-Version}, which this WAR does
 * not set, so the packages 3.4.0 wrote said {@code unknown} — measured on a 3.4.0 build on
 * 2026-10-09. A receiving archive that records which software produced a package had nothing to
 * record.
 *
 * <p>Driven through {@code EarkSipExporter.export}, the method both controller endpoints call. The
 * version it is compared with is checked to be a filtered one first, so a fallback on both sides
 * cannot agree.
 */
class TheSipNamesTheBuildThatWroteItTest {

    private static String built() {
        String built = BuildInfoResource.getVersion();
        assertTrue(built.matches("\\d+\\.\\d+\\.\\d+.*"),
                "version.properties was not filtered on this classpath (got '" + built + "'), so "
                        + "this test cannot tell the build's version from a fallback");
        return built;
    }

    /** Each METS in the zip, by entry name. */
    private static List<String[]> metsOf(Path zip) throws Exception {
        List<String[]> mets = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                if (entry.getName().endsWith("METS.xml")) {
                    mets.add(new String[] {entry.getName(), out.toString(StandardCharsets.UTF_8)});
                }
            }
        }
        return mets;
    }

    private static String noteType(Element note) {
        for (int i = 0; i < note.getAttributes().getLength(); i++) {
            Node attribute = note.getAttributes().item(i);
            if ("NOTETYPE".equals(attribute.getLocalName())) {
                return attribute.getNodeValue();
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && localName.equals(child.getLocalName())) {
                found.add(child);
            }
        }
        return found;
    }

    @Test
    @DisplayName("the METS names NemakiWare as its creating software, with the build's version")
    void theCreatingSoftwareCarriesTheBuildVersion(@TempDir Path tmp) throws Exception {
        String built = built();
        Path sip = EarkSipExporterTest.buildOne(tmp);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);

        List<String> versions = new ArrayList<>();
        List<String[]> mets = metsOf(sip);
        assertFalse(mets.isEmpty(), "the package carries no METS at all");
        for (String[] file : mets) {
            NodeList agents = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(file[1].getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagNameNS("*", "agent");
            for (int i = 0; i < agents.getLength(); i++) {
                Element agent = (Element) agents.item(i);
                if (!"CREATOR".equals(agent.getAttribute("ROLE"))
                        || !"SOFTWARE".equals(agent.getAttribute("OTHERTYPE"))) {
                    continue;
                }
                List<Element> names = children(agent, "name");
                if (names.size() != 1 || !"NemakiWare".equals(names.get(0).getTextContent())) {
                    continue;
                }
                List<String> noted = new ArrayList<>();
                for (Element note : children(agent, "note")) {
                    if ("SOFTWARE VERSION".equals(noteType(note))) {
                        noted.add(note.getTextContent());
                    }
                }
                // Exactly one: an agent with no version note would make "every note says the
                // build" true of nothing.
                assertEquals(1, noted.size(), file[0] + " names NemakiWare as its creating "
                        + "software with " + noted.size() + " SOFTWARE VERSION notes: " + noted);
                versions.add(noted.get(0));
            }
        }

        assertFalse(versions.isEmpty(),
                "no METS in the package names NemakiWare as its creating software");
        for (String version : versions) {
            assertEquals(built, version,
                    "the package names its creating software's version as '" + version
                            + "', but it was written by " + built);
        }
    }
}
