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
package jp.aegif.nemaki.fixity;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A fixity pass reports what it found AND how much it looked at.
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md} §2.1. "MISMATCH: 0" over a truncated scan reads
 * as "nothing is corrupted" and means "nothing I looked at is corrupted" — the same collapse the
 * ACL-epoch migration's {@code COMPLETE} made, and the one an operator acts on.
 */
class FixityScanServiceTest {

    /** python: hashlib.sha256(b"hello world").hexdigest() */
    private static final String HELLO_SHA256 =
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";

    private final ContentService contentService = mock(ContentService.class);

    private FixityScanService service() {
        FixityScanService s = new FixityScanService();
        s.setContentService(contentService);
        return s;
    }

    private Document doc(String id, String digest, String attachmentId) {
        Document d = new Document();
        d.setId(id);
        d.setType("cmis:document");
        d.setAttachmentNodeId(attachmentId);
        List<Aspect> aspects = new ArrayList<>();
        if (digest != null) {
            Aspect integration = new Aspect();
            integration.setName(FixityVerifier.INTEGRATION_ASPECT);
            integration.setProperties(new ArrayList<>(List.of(
                    new Property(FixityVerifier.CONTENT_HASH_PROPERTY, digest))));
            aspects.add(integration);
        }
        d.setAspects(aspects);
        return d;
    }

    private void storedBytes(String attachmentId, String text) {
        AttachmentNode node = new AttachmentNode();
        node.setId(attachmentId);
        node.setInputStream(new java.io.ByteArrayInputStream(
                text.getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(anyString(), eq(attachmentId))).thenReturn(node);
    }

    @Test
    @DisplayName("a clean pass over everything is COMPLETE")
    void aFullCleanPassIsComplete() {
        storedBytes("att-1", "hello world");
        FixityScanReport report = service().scan("bedroom",
                List.of((Content) doc("doc-1", HELLO_SHA256, "att-1")), 100);

        assertEquals(FixityScanReport.Verdict.COMPLETE, report.verdict());
        assertEquals(1, report.match());
        assertEquals(0, report.mismatch());
    }

    @Test
    @DisplayName("a pass that stopped at its limit is PARTIAL, however clean the counts")
    void aTruncatedPassIsPartial() {
        // The one that matters. Rounding this to COMPLETE turns "nothing I looked at is
        // corrupted" into "nothing is corrupted" — and an operator files the second away as
        // done.
        storedBytes("att-1", "hello world");
        storedBytes("att-2", "hello world");
        List<Content> objects = List.of(doc("doc-1", HELLO_SHA256, "att-1"),
                doc("doc-2", HELLO_SHA256, "att-2"));

        FixityScanReport report = service().scan("bedroom", objects, 1);

        assertEquals(FixityScanReport.Verdict.PARTIAL, report.verdict(),
                "a scan that stopped at its limit reported as if it had seen everything");
        assertEquals(1, report.scanned());
        assertEquals(0, report.mismatch(), "control: what it did see was clean");
        assertTrue(report.note() != null && report.note().contains("sample"), report.note());
    }

    @Test
    @DisplayName("a mismatch is counted AND listed; a match is only counted")
    void mismatchesAreListedMatchesAreNot() {
        // A report that enumerates every intact object buries the handful that are not.
        storedBytes("att-1", "hello world");
        storedBytes("att-2", "tampered");
        List<Content> objects = List.of(doc("doc-1", HELLO_SHA256, "att-1"),
                doc("doc-2", HELLO_SHA256, "att-2"));

        FixityScanReport report = service().scan("bedroom", objects, 100);

        assertEquals(1, report.match());
        assertEquals(1, report.mismatch());
        assertEquals(1, report.findings().size(), "only the mismatch belongs in the list");
        assertEquals("doc-2", report.findings().get(0).objectId());
    }

    @Test
    @DisplayName("no recorded digest is counted apart, and costs no attachment read")
    void notRecordedIsItsOwnCountAndCostsNothing() {
        // Fetching bytes we have nothing to compare against is pure cost — and on a repository
        // of pre-digest documents it would be the entire pass.
        FixityScanReport report = service().scan("bedroom",
                List.of((Content) doc("doc-1", null, "att-1")), 100);

        assertEquals(1, report.notRecorded());
        assertEquals(0, report.unverifiable(),
                "'nothing recorded' was folded into 'could not verify', which buries the real "
                        + "unverifiable under every pre-digest document");
        org.mockito.Mockito.verify(contentService, org.mockito.Mockito.never())
                .getAttachment(anyString(), anyString());
    }

    @Test
    @DisplayName("a recorded digest with NO attachment is unverifiable, not 'nothing to check'")
    void recordedDigestWithoutContentIsUnverifiable() {
        // The digest says there SHOULD be bytes. Reporting that as NOT_RECORDED would hide a
        // document whose content has gone missing entirely.
        FixityScanReport report = service().scan("bedroom",
                List.of((Content) doc("doc-1", HELLO_SHA256, null)), 100);

        assertEquals(1, report.unverifiable());
        assertEquals(0, report.notRecorded());
    }

    @Test
    @DisplayName("a pass that dies is FAILED, not PARTIAL — its counts establish nothing")
    void aDeadPassIsFailed() {
        // PARTIAL means "we stopped on purpose". A pass that threw has counts describing
        // nothing in particular, including its zeros.
        Iterable<Content> exploding = () -> new java.util.Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public Content next() { throw new IllegalStateException("view died"); }
        };

        FixityScanReport report = service().scan("bedroom", exploding, 100);

        assertEquals(FixityScanReport.Verdict.FAILED, report.verdict());
    }

    @Test
    @DisplayName("every response carries what a mismatch does and does not establish")
    void theLimitsTravelWithTheReport() {
        // A fixity report is the kind of artefact that gets forwarded and quoted. "MISMATCH"
        // reads as "tampered with" unless the response says otherwise, and it cannot: the
        // digest is an ordinary stored property.
        Map<String, Object> body = service()
                .scan("bedroom", List.of(), 100).asMap();

        String limits = String.valueOf(body.get("limits"));
        assertTrue(limits.contains("not that they were tampered with"), limits);
        assertTrue(limits.contains("NOT_RECORDED"), limits);
        assertEquals("SHA-256", body.get("algorithm"));
        assertEquals("stored-reverified", body.get("subject"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a capped findings list says it was capped")
    void aCappedFindingsListSaysSo() {
        // The cap was silent. Every other truncation in this layer says so out loud, and a
        // reader could only infer this one by comparing findings.size() with
        // mismatch + unverifiable -- which nobody does. (The chain was never affected:
        // FixityLedgerRecorder commits to the COUNTS, not to the list.)
        //
        // Both sides, because a flag that is always true and one that is always false are
        // equally useless and the fix has no other lock.
        java.util.List<FixityScanReport.Finding> many = new java.util.ArrayList<>();
        for (int i = 0; i < FixityScanService.MAX_FINDINGS; i++) {
            many.add(new FixityScanReport.Finding("doc-" + i, FixityOutcome.MISMATCH,
                    "a".repeat(64), "b".repeat(64), "differs"));
        }
        java.util.Map<String, Object> capped = new FixityScanReport(
                FixityScanReport.Verdict.COMPLETE, "bedroom", 999, 0, 999, 0, 0, many, null)
                .asMap();
        java.util.Map<String, Object> small = new FixityScanReport(
                FixityScanReport.Verdict.COMPLETE, "bedroom", 1, 0, 1, 0, 0,
                java.util.List.of(new FixityScanReport.Finding("doc-1", FixityOutcome.MISMATCH,
                        "a".repeat(64), "b".repeat(64), "differs")),
                null).asMap();

        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,
                capped.get("findingsTruncated"),
                "a list capped at " + FixityScanService.MAX_FINDINGS + " does not say it was "
                        + "capped, so it reads as every finding there is");
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,
                small.get("findingsTruncated"),
                "a short list claims it was capped: " + small);
    }
}
