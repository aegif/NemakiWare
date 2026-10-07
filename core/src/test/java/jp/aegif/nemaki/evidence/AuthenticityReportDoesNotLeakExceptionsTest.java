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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.AuthenticityReport.Section;
import jp.aegif.nemaki.evidence.AuthenticityReport.Verdict;
import jp.aegif.nemaki.fixity.FixityScanService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an authenticity report says when a store could not be read — and what it must never say.
 *
 * <p>Each section that fails to read its dependency answers UNAVAILABLE with a reason. The reason
 * used to be the exception's own message, which CodeQL flagged as java/error-message-exposure
 * #1410: the sink is {@code asHtml()} / {@code asMap()} through AuthenticityReportController,
 * and an infrastructure exception's text is whatever the failing layer wrote — a database URL, a
 * path, a class name — in a document handed to people who are not this deployment's operators.
 * Now the section carries a fixed sentence and an incident id, and the text goes to the log.
 *
 * <p>One test per site, so a control that puts the message back at ONE site fails ONE lock and
 * the runner attributes it. The whole report is checked each time, HTML and JSON, because the
 * same reason string reaches both renderings.
 */
class AuthenticityReportDoesNotLeakExceptionsTest {

    /** Looks like what a failing layer writes: a host, a database, a revision. */
    private static final String MARKER = "jdbc://db.internal:5984/nemaki_conf rev 3-abc SECRET";

    private static RuntimeException infrastructure() {
        return new IllegalStateException(MARKER);
    }

    private static void nothingLeaks(AuthenticityReport report) {
        assertFalse(report.asHtml().contains(MARKER),
                "the exception's text reached the HTML report: " + report.asHtml());
        assertFalse(report.asMap().toString().contains(MARKER),
                "the exception's text reached the JSON report: " + report.asMap());
    }

    private static Section section(AuthenticityReport report, String name) {
        return report.sections().stream().filter(s -> name.equals(s.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no section " + name + " in " + report.asMap()));
    }

    private static void unavailableWithIncident(Section section) {
        assertEquals(Verdict.UNAVAILABLE, section.verdict(),
                section.name() + " must still say it could not be read: " + section.asMap());
        assertTrue(String.valueOf(section.content().get("incidentId")).length() >= 8,
                section.name() + " carries no incident id, so the operator cannot find the logged "
                        + "message: " + section.asMap());
    }

    @Test
    @DisplayName("the content section names no exception text when the fixity check throws")
    void theContentSectionNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString())).thenReturn(new Content());
        FixityScanService fixity = mock(FixityScanService.class);
        when(fixity.verifyOne(anyString(), any())).thenThrow(infrastructure());
        assembler.setContentService(contentService);
        assembler.setFixityScanService(fixity);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        unavailableWithIncident(section(report, "content"));
    }

    @Test
    @DisplayName("the custody section names no exception text when the capture store throws")
    void theCustodySectionNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listCapturedForObject(anyString(), anyString(), anyInt()))
                .thenThrow(infrastructure());
        assembler.setMaintenanceStore(store);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        unavailableWithIncident(section(report, "custody"));
    }

    @Test
    @DisplayName("the ledger section names no exception text when the ledger store throws")
    void theLedgerSectionNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        EvidenceLedgerStore ledger = mock(EvidenceLedgerStore.class);
        when(ledger.highestSequence(anyString())).thenThrow(infrastructure());
        assembler.setLedgerStore(ledger);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        unavailableWithIncident(section(report, "ledger"));
    }

    @Test
    @DisplayName("the duplications section names no exception text when the subject lookup throws")
    void theDuplicationsSectionNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        EvidenceLedgerStore ledger = mock(EvidenceLedgerStore.class);
        when(ledger.highestSequence(anyString())).thenReturn(-1L);
        when(ledger.findBySubject(anyString(), anyString(), anyInt())).thenThrow(infrastructure());
        assembler.setLedgerStore(ledger);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        unavailableWithIncident(section(report, "duplications"));
    }

    @Test
    @DisplayName("the renditions-now text names no exception text when the renditions cannot be read")
    void theRenditionsTextNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString())).thenReturn(null);
        when(contentService.getRenditions(anyString(), anyString())).thenThrow(infrastructure());
        // The duplications section only asks for the copies this object carries NOW once the
        // chain records at least one FORMAT_DUPLICATION for it; with none it answers ABSENT
        // before the renditions are read. One recorded duplication reaches the site under test.
        EvidenceLedgerStore ledger = mock(EvidenceLedgerStore.class);
        when(ledger.highestSequence(anyString())).thenReturn(-1L);
        when(ledger.findBySubject(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of(
                EvidenceLedgerEntry.of("bedroom", 1, EvidenceLedgerEntry.SubjectKind.FORMAT_DUPLICATION,
                        "doc-1", "a".repeat(64), "2026-09-28T00:00:00Z", "b".repeat(64))));
        assembler.setContentService(contentService);
        assembler.setLedgerStore(ledger);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        Map<String, Object> duplications = section(report, "duplications").content();
        String text = String.valueOf(duplications.get("renditionsNowUnavailable"));
        assertTrue(text.contains("could not") && text.contains("incident "),
                "the renditions-now text must still say it could not be read, with an incident "
                        + "id: " + duplications);
    }

    @Test
    @DisplayName("the environment section names no exception text when the digest throws")
    void theEnvironmentSectionNamesNoExceptionText() {
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
        when(digest.digest()).thenThrow(infrastructure());
        assembler.setBinaryDigest(digest);

        AuthenticityReport report = assembler.assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        Section environment = section(report, "environment");
        unavailableWithIncident(environment);
        assertTrue(environment.limits().contains("incident "),
                "the environment limits must carry the incident id: " + environment.limits());
    }

    // ---- the production failure path: the fixity check itself catches and REPORTS ----

    /** A document with a recorded digest and an attachment, as the ingest leaves it. */
    private static jp.aegif.nemaki.model.Document documentWithDigestAndAttachment() {
        jp.aegif.nemaki.model.Document doc = new jp.aegif.nemaki.model.Document();
        doc.setId("doc-1");
        doc.setAttachmentNodeId("att-1");
        jp.aegif.nemaki.model.Aspect integration = new jp.aegif.nemaki.model.Aspect();
        integration.setName(jp.aegif.nemaki.fixity.FixityVerifier.INTEGRATION_ASPECT);
        integration.setProperties(new java.util.ArrayList<>(java.util.List.of(
                new jp.aegif.nemaki.model.Property(
                        jp.aegif.nemaki.fixity.FixityVerifier.CONTENT_HASH_PROPERTY, "a".repeat(64)))));
        doc.setAspects(new java.util.ArrayList<>(java.util.List.of(integration)));
        return doc;
    }

    private static AuthenticityReportAssembler assemblerWithRealFixity(ContentService contentService) {
        FixityScanService fixity = new FixityScanService();
        fixity.setContentService(contentService);
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        assembler.setContentService(contentService);
        assembler.setFixityScanService(fixity);
        return assembler;
    }

    @Test
    @DisplayName("the content reason names no exception text when the attachment store fails inside the fixity check")
    void theContentReasonNamesNoExceptionTextWhenTheAttachmentCannotBeRead() {
        // FixityScanService.verifyOne CATCHES the store failure and answers UNVERIFIABLE with a
        // reason — it does not throw. The reason used to carry e.getMessage(), and the assembler
        // copies the reason into the section as written (Codex, c37, P1). This lock follows that
        // path with the real FixityScanService, not a mock that throws.
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString())).thenReturn(documentWithDigestAndAttachment());
        when(contentService.getAttachment(anyString(), anyString())).thenThrow(infrastructure());

        AuthenticityReport report = assemblerWithRealFixity(contentService)
                .assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        Section content = section(report, "content");
        assertEquals(Verdict.UNAVAILABLE, content.verdict(), "the check could not be carried out: " + content.asMap());
        String reason = String.valueOf(content.content().get("reason"));
        assertTrue(reason.contains("could not be read") && reason.contains("incident "),
                "the reason must still say the attachment could not be read, with an incident id: " + reason);
    }

    @Test
    @DisplayName("the content reason names no exception text when the stored bytes cannot be hashed")
    void theContentReasonNamesNoExceptionTextWhenTheBytesCannotBeHashed() throws Exception {
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString())).thenReturn(documentWithDigestAndAttachment());
        jp.aegif.nemaki.model.AttachmentNode attachment = mock(jp.aegif.nemaki.model.AttachmentNode.class);
        java.io.InputStream failing = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException(MARKER);
            }
        };
        when(attachment.getInputStream()).thenReturn(failing);
        when(contentService.getAttachment(anyString(), anyString())).thenReturn(attachment);

        AuthenticityReport report = assemblerWithRealFixity(contentService)
                .assemble("bedroom", "doc-1", "t", false);

        nothingLeaks(report);
        Section content = section(report, "content");
        assertEquals(Verdict.UNAVAILABLE, content.verdict(), "the check could not be carried out: " + content.asMap());
        String reason = String.valueOf(content.content().get("reason"));
        assertTrue(reason.contains("could not be hashed") && reason.contains("incident "),
                "the reason must still say the bytes could not be hashed, with an incident id: " + reason);
    }
}
