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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.evidence.AuthenticityReport.Section;
import jp.aegif.nemaki.evidence.AuthenticityReport.Verdict;
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.roda_project.commons_ip2.validator.EARKSIPValidator;
import org.roda_project.commons_ip2.validator.reporter.ValidationReportOutputJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stored record leaves as an E-ARK SIP, carrying its caveats and not its personal data.
 *
 * <h2>The two things this holds</h2>
 *
 * <p>Passing the validator is the easy half and {@code EarkSipSpikeTest} already has it. The half
 * that matters here is what goes INTO the package: an export is a disclosure to another
 * organisation, and unlike a report it cannot be un-sent. So the INTERNAL_ONLY boundary has to
 * hold, and the package has to carry what its own metadata does not establish — a SIP of bare
 * identity attributes reads as a set of facts this repository is standing behind.
 */
class EarkSipExporterTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    /** The identity section as the assembler would render it, minus the withheld ones. */
    private static AuthenticityReport reportWith(Map<String, Object> identity, int withheld) {
        Map<String, Object> body = new LinkedHashMap<>(identity);
        body.put("withheldInternalOnlyCount", withheld);
        return new AuthenticityReport(REPO, OBJECT, "2026-08-25T00:00:00Z",
                List.of(new Section("identity", Verdict.REPORTED, body,
                        "These are the attributes AS STORED NOW; nothing here checks that the "
                                + "source told the truth.")));
    }

    private static EarkSipExporter exporterOver(AuthenticityReport report, byte[] bytes) {
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);

        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);

        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(report);

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);
        return exporter;
    }

    private static Map<String, String> entriesOf(Path zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    @DisplayName("a real record is packaged and the reference validator accepts it")
    void aRecordIsPackagedAndValid(@TempDir Path tmp) throws Exception {
        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "the minutes".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withholdingPersonalData(), tmp);

        ByteArrayOutputStream reportOut = new ByteArrayOutputStream();
        EARKSIPValidator validator = new EARKSIPValidator(
                new ValidationReportOutputJson(exported.sip(), reportOut),
                EarkSipExporter.CSIP_VERSION);

        assertTrue(validator.validate(EarkSipExporter.CSIP_VERSION),
                "the reference validator rejected a package built from a real record:\n"
                        + reportOut.toString(StandardCharsets.UTF_8));

        Map<String, String> entries = entriesOf(exported.sip());
        assertTrue(entries.keySet().stream().anyMatch(n -> n.endsWith("minutes.txt")),
                "the document's bytes are not in the package: " + entries.keySet());
        assertTrue(entries.entrySet().stream()
                        .anyMatch(e -> e.getKey().endsWith("minutes.txt")
                                && e.getValue().equals("the minutes")),
                "the packaged file is not the document's content");
    }

    @Test
    @DisplayName("INTERNAL_ONLY properties do not leave the building, and the omission is said")
    void personalDataIsWithheldAndTheOmissionIsDeclared(@TempDir Path tmp) throws Exception {
        // The report has already withheld them — this pins that the exporter reads the report's
        // decision rather than making a second, laxer one of its own by going to the aspects.
        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 3),
                "the minutes".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withholdingPersonalData(), tmp);

        assertEquals(3, exported.withheldPropertyCount(),
                "the package does not know what it is missing");
        assertTrue(exported.notes().stream().anyMatch(n -> n.contains("INTERNAL_ONLY")),
                "nothing warns the caller that this package is not a complete record of what "
                        + "was captured: " + exported.notes());

        String dc = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("dc.xml"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        assertFalse(dc.isEmpty(), "no Dublin Core metadata was written");
        assertFalse(dc.contains("withheldInternalOnlyCount"),
                "the bookkeeping field was published as if it were a record attribute: " + dc);
    }

    @Test
    @DisplayName("the package carries what its own metadata does not establish")
    void thePackageCarriesItsLimits(@TempDir Path tmp) throws Exception {
        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "the minutes".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withholdingPersonalData(), tmp);

        String report = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("nemaki-authenticity-report.json"))
                .map(Map.Entry::getValue).findFirst().orElse("");

        assertFalse(report.isEmpty(),
                "the authenticity report is not in the package, so the receiving archive gets "
                        + "identity attributes with no statement of what they establish — which "
                        + "is how a source system's claim becomes this repository's assertion");
        assertTrue(report.contains("whatThisDoesNotEstablish"),
                "the report in the package carries no limits: " + report);
        assertTrue(report.contains("nothing here checks that the source told the truth"),
                "the identity section's own limits did not travel: " + report);
    }

    @Test
    @DisplayName("a document with no attachment is refused, not shipped empty")
    void aDocumentWithNoContentIsRefused(@TempDir Path tmp) {
        // A SIP with an empty representation validates perfectly well and reads as "this
        // record's content was preserved". Producing one would be the worst outcome available.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);

        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);

        EarkSipExporter.ExportRefusedException refused = assertThrows(
                EarkSipExporter.ExportRefusedException.class,
                () -> exporter.export(REPO, OBJECT,
                        EarkSipExporter.Options.withholdingPersonalData(), tmp));

        assertTrue(refused.getMessage().contains("no "),
                "the refusal does not say what was missing: " + refused.getMessage());
    }

    @Test
    @DisplayName("a hostile document name cannot break out of the REAL package's XML")
    void aHostileNameCannotBreakTheWrittenXml(@TempDir Path tmp) throws Exception {
        // The helper test below measures escapeXml. It does NOT measure that anything calls
        // it: deleting the escapeXml(...) call from element() left every test green, which is
        // the shape this project keeps rediscovering — fix the helper, leave the caller bare.
        // So this one drives the real export and reads the XML that was actually written.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("</dc:title><dc:rights>PUBLIC DOMAIN</dc:rights><dc:title>x");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem",
                        "acme</dc:relation><dc:creator>somebody else</dc:creator><dc:relation>y"),
                        0));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);

        EarkSipExporter.Exported exported = exporter.export(REPO, OBJECT,
                EarkSipExporter.Options.withholdingPersonalData(), tmp);

        String dc = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("dc.xml"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        assertFalse(dc.contains("<dc:rights>"),
                "a source-supplied name wrote its own element into the descriptive metadata, so "
                        + "the receiving archive would read a rights statement this repository "
                        + "never made:\n" + dc);
        assertFalse(dc.contains("<dc:creator>"),
                "a source-supplied attribute value wrote its own element:\n" + dc);
        // And it still parses: escaping that produced malformed XML would fail differently but
        // just as badly, and the receiving end is the one who finds out.
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                dc.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a title from a source system cannot break out of the XML")
    void aHostileTitleIsEscaped() {
        String escaped = EarkSipExporter.escapeXml("</dc:title><dc:title>injected");

        assertFalse(escaped.contains("<"),
                "a source-supplied value can close an element: " + escaped);
        assertTrue(escaped.contains("&lt;/dc:title&gt;"), escaped);
    }

    @Test
    @DisplayName("a control character in a title does not make the package unparseable")
    void aControlCharacterIsDropped() {
        // XML 1.0 forbids most control characters outright. A source system that put one in a
        // title must not be able to make the whole package unreadable at the far end.
        String escaped = EarkSipExporter.escapeXml("before\u0000after\u0007\tkept");

        assertFalse(escaped.contains("\u0000"), "a NUL survived into the XML");
        assertFalse(escaped.contains("\u0007"), "a BEL survived into the XML");
        assertTrue(escaped.contains("\t"), "a legal tab was dropped: " + escaped);
        assertEquals("beforeafter\tkept", escaped);
    }
}
