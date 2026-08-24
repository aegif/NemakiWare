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
import jp.aegif.nemaki.fixity.FixityOutcome;
import jp.aegif.nemaki.fixity.FixityScanService;
import jp.aegif.nemaki.fixity.FixityVerifier;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The evidence report says what it does not establish (P1-4).
 *
 * <h2>What these tests are protecting</h2>
 *
 * <p>Design: {@code docs/design/p1-4-authenticity-report.md}. The roadmap calls this artefact
 * the marketing headline, and that is exactly the hazard: a page reading
 * {@code contentHash: abc…} beside {@code custody: 5 events} is READ as "verified" by whoever
 * is handed it, and neither line is a verification. So the tests below assert the presence of
 * limits and the survival of distinctions, not the presence of content — content is the part
 * nobody will forget to add.
 */
class AuthenticityReportTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "doc-1";

    // ---- AC 1 / AC 2: a section without limits cannot exist, and neither can a report ----

    @Test
    @DisplayName("AC1: a section cannot be built without its limits")
    void aSectionWithoutLimitsIsRefused() {
        // Not "is empty" and not "is defaulted": REFUSED. A default would be a sentence nobody
        // wrote, standing where the honest one should be.
        for (String blank : new String[] { null, "", "   " }) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new Section("content", Verdict.VERIFIED, Map.of("a", "b"), blank),
                    "a section was built with limits=" + (blank == null ? "null" : "'" + blank
                            + "'") + "; the reader will assume it establishes everything");
            assertTrue(e.getMessage().contains("limits"), e.getMessage());
        }
    }

    @Test
    @DisplayName("AC2: the report-level limits are in the JSON, and first")
    void theReportSaysWhatItDoesNotEstablish() {
        AuthenticityReport report = new AuthenticityReport(REPO, OBJECT, "2026-08-24T00:00:00Z",
                List.of(new Section("x", Verdict.REPORTED, Map.of(), "nothing at all")));
        Map<String, Object> body = report.asMap();

        assertEquals(AuthenticityReport.REPORT_LIMITS, body.get("whatThisDoesNotEstablish"),
                "the report-level limits are missing; this is the paragraph the whole design "
                        + "is built around");
        // Ordering matters as much as presence. A reader who stops at the first screen must
        // already have met it, so it cannot sit under the sections.
        List<String> keys = new ArrayList<>(body.keySet());
        assertTrue(keys.indexOf("whatThisDoesNotEstablish") < keys.indexOf("sections"),
                "the limits come AFTER the sections (" + keys + "); a reader who stops early "
                        + "takes away the numbers alone");
    }

    @Test
    @DisplayName("AC2: the HTML carries the same limits, above the numbers")
    void theHtmlCarriesTheLimitsToo() {
        AuthenticityReport report = new AuthenticityReport(REPO, OBJECT, "2026-08-24T00:00:00Z",
                List.of(new Section("content", Verdict.VERIFIED, Map.of("digest", "abc"),
                        "a match is not proof of never having been altered")));
        String html = report.asHtml();

        assertTrue(html.contains("does not establish"),
                "the HTML has no report-level limits; the printed page is the one that gets "
                        + "handed to somebody else");
        assertTrue(html.indexOf("does not establish") < html.indexOf("abc"),
                "the limits appear BELOW the digest in the HTML");
        assertTrue(html.contains("a match is not proof of never having been altered"),
                "a section's own limits are missing from the HTML");
    }

    // ---- AC 3: the four fixity values survive ----

    @Test
    @DisplayName("AC3: NOT_RECORDED is not collapsed into UNVERIFIABLE")
    void notRecordedIsNotUnverifiable() {
        Section notRecorded = contentSectionFor(FixityVerifier.Result.notRecorded());
        Section unverifiable = contentSectionFor(
                FixityVerifier.Result.unverifiable("abc", "the attachment could not be read"));

        // "no digest was ever recorded" and "a digest exists and could not be checked" are
        // different facts about the deployment: the first is a capture gap, the second an
        // operational failure. Collapsing them hides which one an operator has to fix.
        assertEquals(Verdict.ABSENT, notRecorded.verdict(),
                "NOT_RECORDED reported as " + notRecorded.verdict());
        assertEquals(Verdict.UNAVAILABLE, unverifiable.verdict(),
                "UNVERIFIABLE reported as " + unverifiable.verdict());
        assertEquals(FixityOutcome.NOT_RECORDED.name(), notRecorded.content().get("outcome"));
        assertEquals(FixityOutcome.UNVERIFIABLE.name(), unverifiable.content().get("outcome"));
    }

    @Test
    @DisplayName("AC3: a MATCH says it is not proof of never having been altered")
    void aMatchIsNotProof() {
        Section section = contentSectionFor(new FixityVerifier.Result(FixityOutcome.MATCH,
                "abc", "abc", null));

        assertEquals(Verdict.VERIFIED, section.verdict());
        String limits = section.limits().toLowerCase();
        assertTrue(limits.contains("not") && limits.contains("altered"),
                "a MATCH's limits do not say it is not proof against alteration: "
                        + section.limits());
        assertTrue(limits.contains("database"),
                "a MATCH's limits do not name the thing that defeats it — direct database "
                        + "access changing bytes and digest together: " + section.limits());
    }

    // ---- AC 4 / AC 5: custody and environment name their own weakness ----

    @Test
    @DisplayName("AC4: custody says a missing row looks like a thing that did not happen")
    void custodyAdmitsItsSilence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("connector", "slack");
        row.put("state", "CAPTURED");
        row.put("chatEvidenceHash", "deadbeef");
        Section section = sectionNamed(assembleWith(rows(row), null), "custody");

        assertEquals(Verdict.REPORTED, section.verdict());
        String limits = section.limits().toLowerCase();
        assertTrue(limits.contains("recorded"), section.limits());
        assertTrue(limits.contains("absence") || limits.contains("not having happened"),
                "the custody limits do not say that an unrecorded operation is invisible here; "
                        + "a five-event chain then reads as 'and nothing else was done': "
                        + section.limits());
    }

    @Test
    @DisplayName("AC5: environment says the value is self-reported")
    void environmentAdmitsItIsCircular() {
        LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
        when(digest.digest()).thenReturn("cafebabe");
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        assembler.setContentService(contentServiceReturning(new Document()));
        assembler.setBinaryDigest(digest);

        Section section = sectionNamed(assembler.assemble(REPO, OBJECT, "t", false),
                "environment");

        assertEquals(Verdict.REPORTED, section.verdict());
        assertEquals("cafebabe", section.content().get("binaryDigest"),
                "the digest is not reported at all, so the limits test below would pass "
                        + "vacuously");
        String limits = section.limits().toLowerCase();
        assertTrue(limits.contains("circular"),
                "the environment section does not say the digest is reported by the very "
                        + "deployment it describes: " + section.limits());
    }

    @Test
    @DisplayName("AC5: an unwired digest still says the value would be self-reported")
    void environmentSaysSoEvenWhenItCannotMeasure() {
        // The path a locally-constructed LineageBinaryDigest used to take FOR EVER: its
        // LineageConfig and ServletContext arrive by injection, so a `new` one can only throw.
        // The section must still be UNAVAILABLE-with-limits rather than silently empty.
        Section section = sectionNamed(assembleWith(List.of(), null), "environment");

        assertEquals(Verdict.UNAVAILABLE, section.verdict());
        assertTrue(section.limits().toLowerCase().contains("circular"), section.limits());
    }

    @Test
    @DisplayName("AC4: a truncated custody list says it is truncated")
    void truncationIsNotSilent() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < AuthenticityReportAssembler.CUSTODY_ROW_LIMIT; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("connector", "slack");
            many.add(row);
        }
        Section section = sectionNamed(assembleWith(many, null), "custody");

        assertEquals(Boolean.TRUE, section.content().get("truncated"),
                "a full page of custody rows was reported without saying it was cut off; a "
                        + "truncated list that does not say so is read as complete");
        assertTrue(section.limits().toLowerCase().contains("truncated"),
                "the truncation is in the content but not in the limits: " + section.limits());
    }

    // ---- AC 6: unreadable is UNAVAILABLE, never empty ----

    @Test
    @DisplayName("AC6: an object that cannot be read gives UNAVAILABLE, not an empty section")
    void unreadableIsNotEmpty() {
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        assembler.setContentService(contentService);

        Section section = sectionNamed(
                assembler.assemble(REPO, OBJECT, "2026-08-24T00:00:00Z", false), "identity");

        // ABSENT would say "this object has no identity attributes", which is a claim about
        // the object made on the strength of a database outage.
        assertEquals(Verdict.UNAVAILABLE, section.verdict(),
                "a read failure was reported as " + section.verdict());
        assertTrue(section.limits().contains("NOT"),
                "the section does not distinguish 'could not read' from 'there is nothing': "
                        + section.limits());
    }

    @Test
    @DisplayName("AC6: the audit trail is UNAVAILABLE, not ABSENT")
    void theAuditTrailIsNotClaimedToBeEmpty() {
        Section section = sectionNamed(assembleWith(List.of(), null), "access");

        // The audit trail goes to an SLF4J logger and leaves this process. Reporting ABSENT
        // would tell a reader nothing was audited, which is a much stronger — and false —
        // statement than "this API cannot read it back".
        assertEquals(Verdict.UNAVAILABLE, section.verdict(),
                "the access section claims " + section.verdict() + "; this deployment writes "
                        + "an audit trail, it just cannot be read back through this API");
        assertTrue(section.limits().contains("NOT a statement that nothing was audited"),
                section.limits());
    }

    // ---- AC 7: personal data is not in it by default ----

    @Test
    @DisplayName("AC7: internal-only properties are withheld unless asked for")
    void personalDataIsNotIncludedByDefault() {
        String personal = CaptureEvidenceField.internalOnlyCmisPropertyIds().iterator().next();
        Document document = documentWith(personal, "alice@example.com");

        Section withheld = sectionNamed(assembleWith(List.of(), document, false), "identity");
        Section asked = sectionNamed(assembleWith(List.of(), document, true), "identity");

        assertFalse(withheld.content().containsKey(personal),
                "'" + personal + "' is in the report by default; a report gets forwarded and "
                        + "printed, and nobody asking 'is this record intact?' meant to put "
                        + "personal data into circulation");
        assertEquals(1, withheld.content().get("withheldInternalOnlyCount"),
                "the withholding is silent; a reader cannot tell the section is partial");
        assertTrue(asked.content().containsKey(personal),
                "an explicit request did not get the property, so the flag does nothing");
        assertEquals(Boolean.TRUE, asked.content().get("includesPersonalData"),
                "a report carrying personal data does not say so");
    }

    @Test
    @DisplayName("AC7 control: a NON-internal evidence property is still reported")
    void ordinaryEvidenceIsStillIncluded() {
        // Without this, withholding everything would pass the test above for the wrong reason.
        String ordinary = "nemaki:sourceSystem";
        assertFalse(CaptureEvidenceField.internalOnlyCmisPropertyIds().contains(ordinary),
                "the control property is itself internal-only; pick another");
        Document document = documentWith(ordinary, "slack");

        Section section = sectionNamed(assembleWith(List.of(), document, false), "identity");

        assertEquals("slack", section.content().get(ordinary),
                "an ordinary evidence property was withheld too; the report would be empty "
                        + "and the withholding test would pass vacuously");
    }

    // ---- ledger ----

    @Test
    @DisplayName("the ledger section says it is the chain checked against itself")
    void theLedgerIsNotIndependent() {
        Section section = sectionNamed(assembleWith(List.of(), null), "ledger");
        String limits = section.limits().toLowerCase();

        assertTrue(limits.contains("anchored") || limits.contains("independent"),
                "the ledger section does not say it is only independent once anchored "
                        + "outside this database: " + section.limits());
    }

    // ---- helpers ----

    private static List<Map<String, Object>> rows(Map<String, Object> row) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(row);
        return list;
    }

    private static Document documentWith(String propertyId, String value) {
        Document document = new Document();
        document.setId(OBJECT);
        Aspect aspect = new Aspect();
        aspect.setName("nemaki:chatContextMetadata");
        List<Property> properties = new ArrayList<>();
        properties.add(new Property(propertyId, value));
        aspect.setProperties(properties);
        List<Aspect> aspects = new ArrayList<>();
        aspects.add(aspect);
        document.setAspects(aspects);
        return document;
    }

    private static Section contentSectionFor(FixityVerifier.Result result) {
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.verifyOne(anyString(), any())).thenReturn(result);
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        assembler.setContentService(contentServiceReturning(new Document()));
        assembler.setFixityScanService(scanService);
        return sectionNamed(assembler.assemble(REPO, OBJECT, "t", false), "content");
    }

    private static ContentService contentServiceReturning(jp.aegif.nemaki.model.Content content) {
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(anyString(), anyString())).thenReturn(content);
        return contentService;
    }

    private static AuthenticityReport assembleWith(List<Map<String, Object>> custodyRows,
            Document document) {
        return assembleWith(custodyRows, document, false);
    }

    private static AuthenticityReport assembleWith(List<Map<String, Object>> custodyRows,
            Document document, boolean includeInternalOnly) {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listCapturedForObject(anyString(), anyString(), anyInt()))
                .thenReturn(custodyRows);
        AuthenticityReportAssembler assembler = new AuthenticityReportAssembler();
        assembler.setContentService(
                contentServiceReturning(document == null ? new Document() : document));
        assembler.setMaintenanceStore(store);
        return assembler.assemble(REPO, OBJECT, "2026-08-24T00:00:00Z", includeInternalOnly);
    }

    private static Section sectionNamed(AuthenticityReport report, String name) {
        for (Section section : report.sections()) {
            if (name.equals(section.name())) {
                return section;
            }
        }
        assertNotNull(null, "the report has no '" + name + "' section; it has "
                + report.sections().stream().map(Section::name).toList());
        return null;
    }
}
