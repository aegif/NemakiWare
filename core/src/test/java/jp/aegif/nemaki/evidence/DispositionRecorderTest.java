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

import jp.aegif.nemaki.evidence.DispositionRecorder.Act;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A disposition that cannot be recorded does not happen.
 *
 * <h2>The rule that inverts</h2>
 *
 * <p>{@code EvidenceLedgerRecorder} must never fail an ingest, because the capture it could not
 * chain is already durable and failing would destroy it. This class must always refuse, because
 * the act it could not record has not happened yet and refusing costs a delay.
 *
 * <p>The two live one package apart and say opposite things, so both directions are pinned here:
 * a green suite in which a broken ledger silently permits deletions would be the worst outcome
 * this file can have.
 */
class DispositionRecorderTest {

    private static final String REPO = "bedroom";

    private static Map<String, String> rule() {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("retention.archive.cold.after.days", "365");
        rule.put("retention.cold.keep.local.copy", "false");
        return rule;
    }

    private static DispositionRecorder recorderOver(EvidenceLedgerService service) {
        DispositionRecorder recorder = new DispositionRecorder();
        recorder.setLedgerService(service);
        return recorder;
    }

    private static EvidenceLedgerService ledgerAnswering(
            EvidenceLedgerService.AppendOutcome outcome, String reason) {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(new EvidenceLedgerService.AppendResult(outcome, 1, "hash", reason));
        return service;
    }

    @Test
    @DisplayName("a recorded disposition is authorised, and the entry names what and under what")
    void aRecordedDispositionIsAuthorised() {
        EvidenceLedgerService service =
                ledgerAnswering(EvidenceLedgerService.AppendOutcome.APPENDED, null);

        DispositionRecorder.Authorisation authorisation = recorderOver(service)
                .authoriseDisposition(REPO, Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE,
                        "obj-1", rule(), "2026-08-26T00:00:00Z");

        assertTrue(authorisation.mayProceed(), authorisation.refusedReason());
        assertNull(authorisation.refusedReason());

        org.mockito.ArgumentCaptor<String> subject =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> digest =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<EvidenceLedgerEntry.SubjectKind> kind =
                org.mockito.ArgumentCaptor.forClass(EvidenceLedgerEntry.SubjectKind.class);
        org.mockito.Mockito.verify(service).append(org.mockito.ArgumentMatchers.eq(REPO),
                kind.capture(), subject.capture(), digest.capture(),
                org.mockito.ArgumentMatchers.eq("2026-08-26T00:00:00Z"));

        assertEquals(EvidenceLedgerEntry.SubjectKind.DISPOSITION, kind.getValue());
        assertEquals("obj-1", subject.getValue(),
                "the entry does not name the object it is about, so a reader holding the id "
                        + "cannot find what happened to it");
        assertEquals(DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-1", rule()),
                digest.getValue(),
                "the chain committed to something other than this disposition");
        assertFalse(digest.getValue().isBlank(),
                "the chain committed to an empty digest, which is to say to nothing");
    }

    @Test
    @DisplayName("a ledger that refuses the entry REFUSES the disposition")
    void anUnrecordedDispositionIsRefused() {
        // The whole point. Answering "go ahead" here deletes content under a trail that has a
        // hole exactly where the deletion is, and nothing afterwards can tell.
        DispositionRecorder.Authorisation authorisation = recorderOver(
                ledgerAnswering(EvidenceLedgerService.AppendOutcome.UNAVAILABLE,
                        "the evidence ledger is not available"))
                .authoriseDisposition(REPO, Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE,
                        "obj-2", rule(), "2026-08-26T00:00:00Z");

        assertFalse(authorisation.mayProceed(),
                "an unrecordable disposition was authorised; the content would be deleted with "
                        + "no record that it was, and no object left to notice");
        assertNotNull(authorisation.refusedReason());
        assertTrue(authorisation.refusedReason().contains("untouched"),
                "the refusal does not say the content is still there, so an operator cannot "
                        + "tell a refusal from a failure: " + authorisation.refusedReason());
    }

    @Test
    @DisplayName("a ledger that throws refuses too — it does not escape into the job")
    void aThrowingLedgerRefusesRatherThanPropagates() {
        EvidenceLedgerService service = mock(EvidenceLedgerService.class);
        when(service.append(anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("couchdb is down"));

        DispositionRecorder.Authorisation authorisation = recorderOver(service)
                .authoriseDisposition(REPO, Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE,
                        "obj-3", rule(), "2026-08-26T00:00:00Z");

        assertFalse(authorisation.mayProceed());
        assertTrue(authorisation.refusedReason().contains("couchdb is down"),
                "the cause was swallowed: " + authorisation.refusedReason());
    }

    @Test
    @DisplayName("no ledger wired refuses — it does not fall through to 'nothing to record'")
    void anUnwiredLedgerRefuses() {
        // The sibling recorder is SILENT here and that is right for it: a capture that cannot
        // be chained still happened. A disposition that cannot be recorded has not happened
        // yet, and the two must not be given the same answer.
        DispositionRecorder.Authorisation authorisation = recorderOver(null)
                .authoriseDisposition(REPO, Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE,
                        "obj-4", rule(), "2026-08-26T00:00:00Z");

        assertFalse(authorisation.mayProceed(),
                "an unwired ledger permitted an irreversible act");
        assertNotNull(authorisation.refusedReason());
    }

    // ---- the digest ----

    @Test
    @DisplayName("the digest changes when the RULE changes, not only the object")
    void theDigestCommitsToTheRule() {
        // "This was deleted" is not a disposition trail. A deployment that shortened its
        // retention window and re-ran the job must not produce the same entry for the same
        // object, or the record cannot show the difference a reader is looking for.
        String base = DispositionRecorder.dispositionDigest(REPO,
                Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-5", rule());

        Map<String, String> shorter = rule();
        shorter.put("retention.archive.cold.after.days", "30");
        assertNotEquals(base, DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-5", shorter),
                "the retention window does not affect the digest, so an entry cannot show "
                        + "WHICH rule authorised the disposal");
        assertNotEquals(base, DispositionRecorder.dispositionDigest("canopy",
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-5", rule()),
                "the repository does not affect the digest");
        assertNotEquals(base, DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-6", rule()),
                "the object does not affect the digest");
    }

    @Test
    @DisplayName("the rule's map order does not change the digest")
    void theDigestIsIndependentOfMapOrder() {
        // A digest that moved with HashMap iteration order would be unreproducible, which
        // defeats the only thing it is for.
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("retention.cold.keep.local.copy", "false");
        reversed.put("retention.archive.cold.after.days", "365");

        assertEquals(
                DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-7", rule()),
                DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-7", reversed));
    }

    @Test
    @DisplayName("a value containing the separators cannot forge a second rule entry")
    void theRuleFlatteningIsInjective() {
        // The first version of this test used {"ab":"c"} vs {"a":"bc"} and did NOT measure
        // anything: with `=` and `;` already between the parts, those two do not collide even
        // with the length prefixes removed, so deleting them left all eight tests green. A
        // reviewer named the surviving edit.
        //
        // What the prefixes actually prevent is a VALUE that contains the separators. Without
        // them both of these flatten to "a=b;c=d;", so a deployment could be shown a rule it
        // never had — one setting pretending to be two.
        Map<String, String> oneSettingWithSeparators = new LinkedHashMap<>();
        oneSettingWithSeparators.put("a", "b;c=d");
        Map<String, String> twoSettings = new LinkedHashMap<>();
        twoSettings.put("a", "b");
        twoSettings.put("c", "d");

        assertNotEquals(
                DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-8",
                        oneSettingWithSeparators),
                DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-8", twoSettings),
                "one setting whose value contains the separators digests the same as two "
                        + "separate settings, so the entry can be read as authorising a rule "
                        + "the deployment never had");
    }

    @Test
    @DisplayName("the rule keys are the product's real settings, not invented labels")
    void theRuleKeysAreRealSettings() {
        // The digest exists so an outside verifier can recompute it from the configuration
        // file. A key that names no setting makes that impossible while looking fine: the first
        // version wrote `retention.longterm.storage.type`, which this product does not have.
        // The value was right and the label pointed at nothing.
        Map<String, String> rule = DispositionRecorder.coldMoveRule("365", false, "s3", "0 0 3 * * ?");

        assertTrue(rule.containsKey("retention.archive.cold.after.days"), rule.toString());
        assertTrue(rule.containsKey("retention.cold.keep.local.copy"), rule.toString());
        assertTrue(rule.containsKey("longterm.storage.type"),
                "the storage-type key is not the one this product reads, so a verifier looking "
                        + "it up in nemakiware.properties finds nothing: " + rule.keySet());
        assertTrue(rule.containsKey("retention.schedule.archive.cold"), rule.toString());
    }

    @Test
    @DisplayName("the disposition digest is domain-separated from every other in the product")
    void theDigestIsDomainSeparated() {
        // The literal is written out here on purpose: production reads the constant, and a
        // fixture that read it too could never fail when the constant changed.
        Map<String, String> empty = Map.of();
        String expected = jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash.hash(
                "LEDGER_DISPOSITION_V1", REPO,
                Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE.name(), "obj-9", "");

        assertEquals(expected, DispositionRecorder.dispositionDigest(REPO,
                        Act.LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE, "obj-9", empty),
                "the disposition digest is no longer H(LEDGER_DISPOSITION_V1, repositoryId, "
                        + "act, subjectId, flattened rule). Emptying or removing the domain "
                        + "lands here, and so does reordering or dropping a field");
    }
}
