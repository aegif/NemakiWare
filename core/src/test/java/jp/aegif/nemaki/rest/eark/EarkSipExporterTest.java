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
import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                .export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        // The exporter now validates its own output, so this asserts what it FOUND rather
        // than re-running the validator beside it. A test that validates independently proves
        // the builder produces valid packages; it does not prove the product ever checks.
        assertTrue(exported.validation().ran(),
                "the exporter did not run the reference validator: "
                        + exported.validation().detail());
        assertTrue(exported.validation().valid(), exported.validation().detail());
        assertEquals(0, exported.validation().errors());

        // And independently, so this test still fails if the exporter's own check goes wrong.
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
    @DisplayName("a package the validator REJECTS is not returned")
    void anInvalidPackageIsRefused(@TempDir Path tmp) throws Exception {
        // A package is a disclosure to another organisation and cannot be un-sent, so a
        // rejected one has to stop here. The rejection is reached by validating something that
        // is not a CSIP package at all — the validator running and saying no, which is the
        // case that must refuse.
        EarkSipExporter exporter = new EarkSipExporter();
        java.lang.reflect.Method validate = EarkSipExporter.class.getDeclaredMethod("validate",
                Path.class);
        validate.setAccessible(true);
        Path notAPackage = Files.writeString(tmp.resolve("empty.zip"), "");

        EarkSipExporter.Validation verdict =
                (EarkSipExporter.Validation) validate.invoke(exporter, notAPackage);

        assertFalse(verdict.ran() && verdict.valid(),
                "an empty file was accepted as a valid CSIP package");
    }

    @Test
    @DisplayName("'could not check' is not 'valid' — and it travels with the package")
    void validationThatCouldNotRunSaysSo() {
        // The distinction the rest of this product keeps making. A local failure to run the
        // validator is a statement about the node, and turning it into a refusal would make an
        // unrelated local problem read as a defect in the record.
        EarkSipExporter.Validation notRun =
                EarkSipExporter.Validation.notRun("no temporary directory");

        assertFalse(notRun.ran());
        assertFalse(notRun.valid());
        assertTrue(notRun.limits().contains("NOT checked"), notRun.limits());
        assertTrue(notRun.limits().contains("statement about this node"), notRun.limits());
        assertTrue(notRun.limits().contains("nothing here says it is not"), notRun.limits());
    }

    @Test
    @DisplayName("a validated package does not claim the record is genuine")
    void validationDoesNotVouchForTheRecord() {
        // "Passed the reference validator" is read as a clean bill of health for what is
        // inside. It is a statement about structure and METS, and about nothing else.
        EarkSipExporter.Validation passed =
                new EarkSipExporter.Validation(true, true, 0, 0, "12 check(s) passed");

        assertTrue(passed.limits().contains("STRUCTURE and METS"), passed.limits());
        assertTrue(passed.limits().contains("says nothing about whether the record inside is"),
                passed.limits());
    }

    @Test
    @DisplayName("INTERNAL_ONLY properties do not leave the building, and the omission is said")
    void personalDataIsWithheldAndTheOmissionIsDeclared(@TempDir Path tmp) throws Exception {
        // The report has already withheld them — this pins that the exporter reads the report's
        // decision rather than making a second, laxer one of its own by going to the aspects.
        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 3),
                "the minutes".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

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
                .export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

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
                        EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp));

        assertTrue(refused.getMessage().contains("no "),
                "the refusal does not say what was missing: " + refused.getMessage());
    }

    // ---- the things the design document claims, now measured ----

    @Test
    @DisplayName("the caller's disclosure choice is the one the report is built with")
    void theDisclosureChoiceIsPassedThrough(@TempDir Path tmp) throws Exception {
        // The design document says "the default withholds; the caller has to opt in". Nothing
        // held it: the assembler was stubbed with anyBoolean(), so hard-coding `true` at the
        // call site — an exporter that ALWAYS asks for personal data — left every test green.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem", "acme"), 0));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);

        exporter.export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        org.mockito.ArgumentCaptor<Boolean> asked =
                org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(assembler).assemble(org.mockito.ArgumentMatchers.eq(REPO),
                org.mockito.ArgumentMatchers.eq(OBJECT), anyString(), asked.capture());
        assertFalse(asked.getValue(),
                "the default export asked the report for INTERNAL_ONLY properties, so personal "
                        + "data would be in a package that cannot be recalled");
    }

    @Test
    @DisplayName("opting in reaches the report AND the package says it carries personal data")
    void optingInIsPassedThroughAndDeclared(@TempDir Path tmp) throws Exception {
        // The other half of the guarantee. Without the note, "nothing was withheld" and
        // "everything was deliberately included" produce an identical result, and the one
        // carrying personal data is the one nobody is told about.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem", "acme"), 0));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);

        EarkSipExporter.Exported exported = exporter.export(REPO, OBJECT,
                new EarkSipExporter.Options(true, "Acme Ltd"), tmp);

        org.mockito.ArgumentCaptor<Boolean> asked =
                org.mockito.ArgumentCaptor.forClass(Boolean.class);
        org.mockito.Mockito.verify(assembler).assemble(anyString(), anyString(), anyString(),
                asked.capture());
        assertTrue(asked.getValue(), "opting in did not reach the report");
        assertTrue(exported.notes().stream().anyMatch(n -> n.contains("personal data")),
                "a package built with includeInternalOnly=true says nothing about it: "
                        + exported.notes());
    }

    @Test
    @DisplayName("the identity attributes actually reach the descriptive metadata")
    void identityAttributesReachTheDublinCore(@TempDir Path tmp) throws Exception {
        // The commit's central deliverable, and every earlier assertion about dc.xml was
        // NEGATIVE ("does not contain X"). Emptying disclosableIdentity, or deleting the
        // dc:relation loop, left all of them green while the package carried no identity at all.
        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme",
                        "nemaki:sourceObjectId", "SRC-42"), 0),
                "the minutes".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        String dc = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("dc.xml"))
                .map(Map.Entry::getValue).findFirst().orElse("");

        assertTrue(dc.contains("acme"),
                "the source system did not reach the descriptive metadata, so the package "
                        + "carries a payload and no statement of where it came from:\n" + dc);
        assertTrue(dc.contains("SRC-42"),
                "the source object id did not reach the descriptive metadata:\n" + dc);
        assertTrue(dc.contains("nemaki:sourceSystem="),
                "the attribute is not keyed by its property id, so a receiver cannot tell "
                        + "which attribute it is reading:\n" + dc);
    }

    @Test
    @DisplayName("the exporter reads the report's decision, not the object's aspects")
    void theExporterDoesNotGoBehindTheReport(@TempDir Path tmp) throws Exception {
        // The design document says the disclosure decision is made in ONE place. The earlier
        // test claimed to pin this and did not: its Document had no aspects at all, so an
        // implementation that read them directly would produce the same empty result.
        // Here the object carries an aspect the report does NOT report.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        jp.aegif.nemaki.model.Aspect aspect = new jp.aegif.nemaki.model.Aspect();
        aspect.setName("nemaki:chatContextMetadata");
        jp.aegif.nemaki.model.Property participants = new jp.aegif.nemaki.model.Property();
        participants.setKey("nemaki:chatParticipants");
        participants.setValue("alice@example.com,bob@example.com");
        aspect.setProperties(List.of(participants));
        document.setAspects(new java.util.ArrayList<>(List.of(aspect)));
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        // The report withheld it — one property, not reported.
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem", "acme"), 1));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);

        EarkSipExporter.Exported exported = exporter.export(REPO, OBJECT,
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        String dc = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("dc.xml"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        assertFalse(dc.contains("alice@example.com"),
                "a property the report withheld reached the package anyway, so the export "
                        + "applies a second and laxer disclosure rule of its own:\n" + dc);
        assertTrue(dc.contains("acme"), "the reported attribute did not travel:\n" + dc);
    }

    @Test
    @DisplayName("the package is validated against the version it was generated for")
    void theCsipVersionIsPinned(@TempDir Path tmp) throws Exception {
        // Using EarkSipExporter.CSIP_VERSION on BOTH sides is self-referential: setting it to
        // 2.1.0 keeps the exporter's tests green, because the validator accepts 2.0.4 and
        // 2.1.0 too. The literal is written out here so a change to the constant lands.
        assertEquals("2.2.0", EarkSipExporter.CSIP_VERSION,
                "the exporter no longer targets CSIP 2.2.0. That may be intended, but the "
                        + "roadmap and the design document both name 2.2.0, and a silent move "
                        + "to an older profile is not something a passing validator would show");

        EarkSipExporter.Exported exported = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "x".getBytes(StandardCharsets.UTF_8))
                .export(REPO, OBJECT, EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(new EARKSIPValidator(new ValidationReportOutputJson(exported.sip(), out),
                        "2.2.0").validate("2.2.0"),
                "the package does not pass the 2.2.0 profile:\n"
                        + out.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a Japanese file name survives; a hostile one is made safe")
    void fileNamesAreMadeSafeWithoutDestroyingThem(@TempDir Path tmp) throws Exception {
        // The first sanitiser kept only [A-Za-z0-9._-], which is safe and destroys every
        // Japanese file name in a Japanese ECM: 議事録.txt and 報告書.txt both became ___.txt.
        EarkSipExporter.Exported exported = exporterWithAttachmentNamed("議事録.txt", tmp);
        assertTrue(entriesOf(exported.sip()).keySet().stream()
                        .anyMatch(n -> n.endsWith("議事録.txt")),
                "a Japanese file name was destroyed: "
                        + entriesOf(exported.sip()).keySet());
    }

    @Test
    @DisplayName("a traversal attempt in a file name never becomes a path")
    void aTraversalFileNameIsFlattened(@TempDir Path tmp) throws Exception {
        EarkSipExporter.Exported exported =
                exporterWithAttachmentNamed("../../../etc/passwd", tmp);
        assertTrue(entriesOf(exported.sip()).keySet().stream().noneMatch(n -> n.contains("..")),
                "a payload path escaped its representation: "
                        + entriesOf(exported.sip()).keySet());
        assertTrue(entriesOf(exported.sip()).keySet().stream().anyMatch(n -> n.endsWith("passwd")),
                entriesOf(exported.sip()).keySet().toString());
    }

    @Test
    @DisplayName("a reserved Windows device name does not survive as itself")
    void aReservedDeviceNameIsRenamed(@TempDir Path tmp) throws Exception {
        // An archive unpacked onto Windows fails on the FILE, not the package, and the receiver
        // gets blamed for it.
        EarkSipExporter.Exported exported = exporterWithAttachmentNamed("CON.txt", tmp);
        assertTrue(entriesOf(exported.sip()).keySet().stream()
                        .noneMatch(n -> n.endsWith("/CON.txt")),
                "a reserved device name was packaged verbatim: "
                        + entriesOf(exported.sip()).keySet());
    }

    private static EarkSipExporter.Exported exporterWithAttachmentNamed(String name, Path tmp) {
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName(name);
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn(name);
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem", "acme"), 0));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);
        return exporter.export(REPO, OBJECT,
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);
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
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

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
    @DisplayName("a non-character in a title does not make the REAL package unparseable")
    void aNonCharacterDoesNotBreakTheWrittenXml(@TempDir Path tmp) throws Exception {
        // The helper test below checks control characters and was written where the injection
        // test had already been moved to the call site — so the same gap was left open twice.
        // U+FFFE and U+FFFF are not control characters and passed the `c >= 0x20` filter, and
        // both make dc.xml unparseable at the far end: the escaping producing the exact outcome
        // it exists to prevent. Driven through the real export, and PARSED.
        ContentService contentService = mock(ContentService.class);
        Document document = new Document();
        document.setId(OBJECT);
        document.setName("minutes\uFFFF\uFFFE.txt");
        document.setType("cmis:document");
        document.setAttachmentNodeId("att-1");
        when(contentService.getContent(REPO, OBJECT)).thenReturn(document);
        AttachmentNode attachment = mock(AttachmentNode.class);
        when(attachment.getName()).thenReturn("minutes.txt");
        when(attachment.getInputStream()).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, "att-1")).thenReturn(attachment);
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(reportWith(Map.of("nemaki:sourceSystem",
                        "acme\uFFFFsuffix"), 0));
        EarkSipExporter exporter = new EarkSipExporter();
        exporter.setContentService(contentService);
        exporter.setReportAssembler(assembler);

        EarkSipExporter.Exported exported = exporter.export(REPO, OBJECT,
                EarkSipExporter.Options.withoutInternalOnlyProperties(), tmp);

        String dc = entriesOf(exported.sip()).entrySet().stream()
                .filter(e -> e.getKey().endsWith("dc.xml"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                dc.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("an unpaired surrogate is dropped, not left to fail the whole export")
    void anUnpairedSurrogateIsDropped() {
        // A lone half cannot be encoded as UTF-8. Left in, it fails the file write with
        // "Input length = 1" and the whole export is refused over one bad character in a title.
        assertEquals("ab", EarkSipExporter.escapeXml("a\uD800b"));
        assertEquals("ab", EarkSipExporter.escapeXml("a\uDC00b"));
        // A well-formed pair is a legal supplementary character and survives whole.
        assertEquals("a\uD83D\uDE00b", EarkSipExporter.escapeXml("a\uD83D\uDE00b"));
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

    @Test
    @DisplayName("the capture entry is found through the capture rows, and proved")
    void theCaptureIsFoundThroughItsIntent() throws Exception {
        // The CAPTURE entry's subject is the capture intent id, not the object — so an
        // object-only lookup reported "nothing chained" for a record whose capture IS in the
        // chain. The object id has been on the intent row since completion, which is how the
        // authenticity report has been finding these all along; the exporter now follows the
        // same path instead of rewriting stored subjects, which would break every inclusion
        // proof already issued.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), org.mockito.ArgumentMatchers.eq(OBJECT),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(store.findBySubject(anyString(), org.mockito.ArgumentMatchers.eq("intent-7"),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of(REPO, 3,
                                EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED, "intent-7",
                                "mh1:capture", "2026-08-25T00:00:00Z", null)));
        jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore captureStore =
                mock(jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.class);
        when(captureStore.listCapturedForObject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(Map.of("intentId", "intent-7")));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);
        exporter.setCaptureMaintenanceStore(captureStore);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        @SuppressWarnings("unchecked")
        Map<String, Object> proof = (Map<String, Object>) evidence.get("inclusionProof");
        // What this test is about: the proof is over the CAPTURE, found through its intent.
        assertEquals("CAPTURE_COMPLETED", proof.get("provesEntry"), String.valueOf(proof));
        assertEquals("intent-7", proof.get("provesSubjectId"), String.valueOf(proof));
        // The outer status FOLLOWS the proof, and this fixture's ledger cannot build one -- so
        // it must NOT be "success". It asserted exactly that until 2026-08-28: inclusionProof
        // reports its refusals in its returned map, evidencePackage merged that map into a
        // nested key and left the outer status alone, and this assertion pinned the result.
        // The package is written into the SIP as nemaki-evidence.json, so "success" over a
        // proof that does not exist travels to the receiving organisation permanently.
        assertEquals(proof.get("status"), evidence.get("status"),
                "the package's status disagrees with the proof it carries: " + evidence);
        assertNotNull(evidence.get("inclusionProofFailed"),
                "the package carries no audit path and does not say so: " + evidence);
    }

    @Test
    @DisplayName("a capture lookup that FAILED is not reported as 'no capture entry'")
    void anUnreadableCaptureIsNotAnAbsentOne() throws Exception {
        // The lookup was added precisely to stop "we could not look" reading as "there is no
        // capture" — and the first version of it swallowed every exception into an empty list,
        // which reinstated the same substitution one layer down.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(EvidenceLedgerEntry.of(REPO, 9,
                        EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT, "mh1:fixity",
                        "2026-08-26T00:00:00Z", null)));
        jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore captureStore =
                mock(jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.class);
        when(captureStore.listCapturedForObject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("the capture database is down"));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);
        exporter.setCaptureMaintenanceStore(captureStore);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        String captureProof = String.valueOf(evidence.get("captureProof"));
        assertTrue(captureProof.contains("COULD NOT BE READ"), captureProof);
        assertTrue(captureProof.contains("NOT a statement that the record has no capture"),
                captureProof);
    }

    @Test
    @DisplayName("the inclusion proof says WHICH entry it is about")
    void theProofNamesItsSubject() throws Exception {
        // It used to prove entries.get(0) and call it "the capture". That list is the object's
        // OWN entries, so the label named a capture while the proof was over the oldest fixity
        // result, duplication or custody receipt. A proof whose subject is mislabelled is worse
        // than no proof: it is checkable, so it is believed.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(EvidenceLedgerEntry.of(REPO, 9,
                        EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT, "mh1:fixity",
                        "2026-08-26T00:00:00Z", null)));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        @SuppressWarnings("unchecked")
        Map<String, Object> proof = (Map<String, Object>) evidence.get("inclusionProof");
        assertEquals("FIXITY_RESULT", proof.get("provesEntry"),
                "the proof does not say which entry it is about: " + proof);
        assertNotNull(evidence.get("captureProof"),
                "there is no capture entry and the package does not say the proof is over "
                        + "something else: " + evidence);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("no factory claims to withhold personal data")
    void noFactoryClaimsToWithholdPersonalData() {
        // The flag selects METADATA PROPERTIES; writePayload adds the document body
        // unconditionally. The response header that used to say
        // X-Nemaki-Includes-Personal-Data: false is locked by an assertNull; the method that
        // used to be called withholdingPersonalData() was renamed and NOT locked, so the old
        // name could come back beside the new one and every test would stay green. A method
        // name is read by callers and concluded from, exactly like the header.
        for (java.lang.reflect.Method method : EarkSipExporter.Options.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            org.junit.jupiter.api.Assertions.assertFalse(
                    name.contains("personaldata") || name.contains("withhold"),
                    "EarkSipExporter.Options." + method.getName() + " claims to control personal "
                            + "data. includeInternalOnly does not: the document body is always "
                            + "written, and its content is not inspected.");
        }
    }

    @Test
    @DisplayName("ledger rows that could not be read are not 'no entry names this object'")
    void undecodableLedgerRowsAreNotAnUnchainedRecord() throws Exception {
        // This sentence does not stay in a response: it is written into nemaki-evidence.json
        // inside the package that leaves the organisation, where it cannot be corrected. The
        // read that THREW was already handled; the read that partly succeeded was not -- the
        // store drops rows it cannot decode, so an all-undecodable read looks like an empty
        // chain.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(store.unreadableCount()).thenReturn(2);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertEquals(2, evidence.get("undecodableEntries"), String.valueOf(evidence));
        assertNotEquals("not-chained", evidence.get("status"),
                "rows nobody could read were shipped as 'this record was never chained': "
                        + evidence);
        assertTrue(String.valueOf(evidence.get("message")).contains("NOT a statement"),
                "the package does not say what its silence is not: " + evidence);
    }

    @Test
    @DisplayName("a chain that really holds nothing still says not-chained — the control")
    void aGenuinelyUnchainedRecordStillSaysSo() throws Exception {
        // Without this, hedging every empty read would satisfy the test above and no package
        // could ever state the ordinary fact that a record predates the chain.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(store.unreadableCount()).thenReturn(0);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertEquals("not-chained", evidence.get("status"), String.valueOf(evidence));
        assertNull(evidence.get("undecodableEntries"), String.valueOf(evidence));
    }

    @Test
    @DisplayName("dropped rows are disclosed even when some entries WERE read")
    void droppedRowsAreDisclosedBesideTheEntriesThatWereRead() throws Exception {
        // The first version of this disclosure only fired when BOTH lists came back empty, so
        // ONE decodable row was enough to ship status:"success" with the dropped rows nowhere
        // in the package. "All of them were unreadable" and "some of them were" are the same
        // fact about what the list does not contain, and this map is written into
        // nemaki-evidence.json, which leaves the organisation.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of(REPO, 1,
                                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT,
                                "mh1:fixity", "2026-08-26T00:00:00Z", null)));
        when(store.unreadableCount()).thenReturn(4);
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        java.util.List<String> notes = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, notes);

        assertEquals(4, evidence.get("undecodableEntries"), String.valueOf(evidence));
        assertTrue(String.valueOf(evidence.get("undecodableEntriesNote")).contains("NOT a "
                        + "statement"),
                "the package does not say what its short list is not: " + evidence);
        assertTrue(notes.stream().anyMatch(n -> n.contains("incomplete")),
                "the notes that travel beside the package say nothing about the gap: " + notes);
    }

    @Test
    @DisplayName("a complete read carries no gap disclosure — the control")
    void aCompleteReadCarriesNoGapDisclosure() throws Exception {
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of(REPO, 1,
                                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT,
                                "mh1:fixity", "2026-08-26T00:00:00Z", null)));
        when(store.unreadableCount()).thenReturn(0);
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertNull(evidence.get("undecodableEntries"),
                "a complete read was reported as incomplete: " + evidence);
    }

    @Test
    @DisplayName("both failures are reported when both happened, not just the first")
    void twoFailuresAreNotAlternatives() throws Exception {
        // Undecodable ledger rows and a capture lookup that threw are INDEPENDENT — and a
        // repository with damaged rows is exactly the one whose views are struggling, so both
        // together is the likely case, not the exotic one. Written as `else if`, the
        // undecodableEntries key was left out of the package whenever the capture read had also
        // failed, and what shipped was "no ledger entry names this object" while N rows naming
        // it sat unread. This map becomes nemaki-evidence.json and leaves the organisation.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(store.unreadableCount()).thenReturn(2);
        jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore captureStore =
                mock(jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.class);
        when(captureStore.listCapturedForObject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("the capture view did not answer"));
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        EvidenceLedgerService twoFailuresService = new EvidenceLedgerService();
        twoFailuresService.setStore(store);
        exporter.setLedgerStore(store);
        exporter.setLedgerService(twoFailuresService);
        exporter.setCaptureMaintenanceStore(captureStore);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        List<String> notes = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, notes);

        assertEquals("error", evidence.get("status"), String.valueOf(evidence));
        assertEquals(2, evidence.get("undecodableEntries"),
                "the rows that could not be read vanished from the package because the capture "
                        + "read had ALSO failed: " + evidence);
        assertNotNull(evidence.get("captureLookupFailed"),
                "the capture failure is not in the package either: " + evidence);
        // And a note, because a caller streaming the zip to disk never sees the JSON. These are
        // the heaviest arms here — nothing at all was established — and they were the only ones
        // with no note, so the light failure was announced and the total one was not.
        assertEquals(2, notes.size(),
                "the two failures produced " + notes.size() + " note(s): " + notes);
    }

    @Test
    @DisplayName("a package with no audit path does not claim one proves anything")
    void aPackageWithoutAPathDoesNotClaimOne() throws Exception {
        // `limits` is written once near the top, so every arm has one — and that made "The
        // audit path proves that the entry named here was in the span its checkpoint sealed"
        // travel with packages that have no inclusionProof at all. The comment beside the
        // proof-failed arm had NAMED this defect; the correction there stopped at the status.
        //
        // Fixed by flipping the default rather than by patching arms: the weak sentence is what
        // every package starts with, and the strong one is earned where a proof succeeds.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        EvidenceLedgerService noPathService = new EvidenceLedgerService();
        noPathService.setStore(store);
        exporter.setLedgerStore(store);
        exporter.setLedgerService(noPathService);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertEquals("not-chained", evidence.get("status"),
                "fixture check: this package HAS a path, so it is not the case under test: "
                        + evidence);
        assertEquals(EarkSipExporter.EVIDENCE_PACKAGE_LIMITS_NO_PATH, evidence.get("limits"),
                "a package with no audit path tells the receiving organisation that its audit "
                        + "path proves something: " + evidence.get("limits"));
    }

    @Test
    @DisplayName("capture rows with no intent are counted, not skipped in silence")
    void captureRowsWithoutAnIntentAreCounted() throws Exception {
        // Skipped in silence, a set of capture rows that ALL lacked an intentId produced "no
        // capture entry was found for it either" — the confident negative, drawn from rows that
        // were right there in front of the loop. The accumulation ten lines below it was added
        // for exactly this and did not cover this arm.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore captureStore =
                mock(jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.class);
        when(captureStore.listCapturedForObject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new java.util.HashMap<String, Object>()));
        EvidenceLedgerService service = new EvidenceLedgerService();
        service.setStore(store);
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);
        exporter.setCaptureMaintenanceStore(captureStore);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertNotEquals("not-chained", evidence.get("status"),
                "a capture row this build could not use was dropped without a trace, so the "
                        + "package tells the receiving organisation no capture entry exists: "
                        + evidence);
        assertEquals(1, evidence.get("undecodableEntries"), String.valueOf(evidence));
    }

    @Test
    @DisplayName("a package that read SOME rows and lost others is not a 'success'")
    void aPartiallyReadPackageIsNotASuccess() throws Exception {
        // The both-empty arm was corrected a round earlier; the arm where something WAS read
        // kept the defect. The lost rows were disclosed in their own key while
        // evidence.put("status", "success") ran unconditionally underneath — so the word a
        // reader takes first said the opposite of the key beside it, in nemaki-evidence.json,
        // which leaves the organisation and cannot be corrected afterwards.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of(REPO, 1,
                                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT,
                                "mh1:fixity", "2026-08-25T00:00:00Z", null)));
        when(store.unreadableCount()).thenReturn(3);
        // The proof must SUCCEED. With the real service this fixture has no checkpoint, so the
        // proof fails and its status overwrites the outer one — which made the first version of
        // this test pass no matter what the arm under test did. The negative control caught it:
        // reverting the fix changed nothing and the test stayed green.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.inclusionProof(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("status", "success"));
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, new java.util.ArrayList<String>());

        assertEquals(3, evidence.get("undecodableEntries"),
                "fixture check: nothing was lost, so this is not the case under test: " + evidence);
        assertNotEquals("success", evidence.get("status"),
                "a package that could not read 3 of this record's chain rows calls itself a "
                        + "success: " + evidence);
    }

    @Test
    @DisplayName("a capture lookup that failed reaches the HEADER too, not only the JSON")
    void aFailedCaptureLookupIsAnnouncedInANote() throws Exception {
        // notes become X-Nemaki-Export-Note headers, and the controller chose that route
        // because "a caller streaming the zip to disk would never see a JSON note". When the
        // object HAD entries, the capture failure was kept in the JSON and announced nowhere.
        EvidenceLedgerStore store = mock(EvidenceLedgerStore.class);
        when(store.isActive()).thenReturn(true);
        when(store.findBySubject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                        EvidenceLedgerEntry.of(REPO, 1,
                                EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, OBJECT,
                                "mh1:fixity", "2026-08-25T00:00:00Z", null)));
        jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore captureStore =
                mock(jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.class);
        when(captureStore.listCapturedForObject(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("the capture view did not answer"));
        // A SUCCEEDING proof, so the status assertion below is about the capture failure and
        // not about a proof that would have overwritten it anyway.
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.inclusionProof(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("status", "success"));
        EarkSipExporter exporter = exporterOver(
                reportWith(Map.of("nemaki:sourceSystem", "acme"), 0),
                "bytes".getBytes(StandardCharsets.UTF_8));
        exporter.setLedgerStore(store);
        exporter.setLedgerService(service);
        exporter.setCaptureMaintenanceStore(captureStore);

        java.lang.reflect.Method evidencePackage = EarkSipExporter.class.getDeclaredMethod(
                "evidencePackage", String.class, String.class, List.class);
        evidencePackage.setAccessible(true);
        List<String> notes = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) evidencePackage.invoke(exporter,
                REPO, OBJECT, notes);

        assertNotEquals("success", evidence.get("status"),
                "a package whose capture evidence could not be read calls itself a success: "
                        + evidence);
        assertTrue(notes.stream().anyMatch(n -> n.contains("capture rows")),
                "the capture failure never reached the header, so a caller writing the zip "
                        + "straight to disk is not told: " + notes);
    }
}
