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

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Puts facts INTO the evidence ledger (P1-3 §2).
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The ledger had a store, checkpoints, an anchoring ladder and a verifier — and nothing that
 * wrote to it outside tests. Every review of the reporting layer ended at the same place: the
 * ledger section reads ABSENT because the chain is empty, and a checkpoint over an empty span
 * commits to nothing. A chain nobody feeds is scaffolding, not evidence.
 *
 * <h2>Two rules that shape everything here</h2>
 *
 * <ul>
 *   <li><b>Appending must not fail the operation.</b> This runs after a capture is already
 *       durable. Failing the ingest because a SECOND record could not be written would destroy
 *       the thing the record was about.</li>
 *   <li><b>...and must not be silent either.</b> That is the whole fail-open lesson. A gap in
 *       the chain that nobody is told about is worse than no chain, because the chain is
 *       believed. So the outcome is RETURNED, and the capture path turns it into a warning the
 *       caller receives.</li>
 * </ul>
 *
 * <h2>Only forwards</h2>
 *
 * <p>There is no backfill and there will not be one. {@code p1-3-evidence-ledger.md} §8:
 * evidence made after the fact is not evidence. Entries that predate this class simply are not
 * in the chain, and the report says the chain is not a complete record of everything that
 * happened.
 */
@Component
public class EvidenceLedgerRecorder {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceLedgerRecorder.class);

    /**
     * The domain string for a capture-completed entry's payload digest.
     *
     * <p>Separate from every other digest in the product. Without it, a digest computed over the
     * same fields for a different purpose would collide with this one and a value could be moved
     * between contexts.
     */
    static final String CAPTURE_DIGEST_DOMAIN = "LEDGER_CAPTURE_COMPLETED_V1";

    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** What happened, and what the caller has to be told. */
    public record Recorded(boolean inChain, String warning) {

        static Recorded chained() {
            return new Recorded(true, null);
        }

        static Recorded gap(String warning) {
            return new Recorded(false, warning);
        }
    }

    /**
     * Records that a capture completed.
     *
     * <p>The entry carries a DIGEST of the completed capture, never its body: the ledger is not
     * purged, and putting participants or addressees into it would fix personal data somewhere
     * it cannot be removed from (design §2).
     *
     * @param evidence the facts attached at completion. Read for the applied metadata hashes
     *                 only; nothing from it is stored.
     */
    public Recorded recordCaptureCompleted(String repositoryId, CaptureIntent intent,
            Map<String, Object> evidence, String occurredAt) {
        if (ledgerService == null) {
            // Not wired. Said once per call at debug, not warn: a deployment that has not
            // enabled the ledger is not in an error state, and a warning per ingest would
            // train an operator to ignore the log.
            logger.debug("No evidence ledger is wired; capture {} is not chained",
                    intent.intentId());
            return Recorded.gap(null);
        }
        String digest = captureDigest(repositoryId, intent, evidence);
        EvidenceLedgerService.AppendResult result;
        try {
            result = ledgerService.append(repositoryId,
                    EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED, intent.intentId(), digest,
                    occurredAt);
        } catch (RuntimeException e) {
            // The capture is already durable. Losing it because the ledger is down would be a
            // far worse outcome than a gap in the chain — but the gap is still reported.
            logger.warn("Capture {} completed but could not be chained: {}", intent.intentId(),
                    e.getMessage());
            return Recorded.gap("This ingest was captured, but its record could not be added to "
                    + "the evidence chain (" + e.getMessage() + "). The capture itself stands; "
                    + "the chain is missing this entry and will not be back-filled.");
        }
        if (result.recorded()) {
            return Recorded.chained();
        }
        logger.warn("Capture {} completed but was not chained: {}", intent.intentId(),
                result.reason());
        return Recorded.gap("This ingest was captured, but its record was not added to the "
                + "evidence chain (" + result.reason() + "). The capture itself stands; the "
                + "chain is missing this entry and will not be back-filled.");
    }

    /**
     * The canonical digest of a completed capture.
     *
     * <p>Recomputable from the stored row, which is the point: a verifier holding the capture
     * row can reproduce this value and compare it with what the chain committed to. The fields
     * are the ones that identify WHICH capture this was and WHAT it established — not the free
     * text a wrapper happened to attach, which varies between passes and would make the digest
     * unreproducible.
     */
    static String captureDigest(String repositoryId, CaptureIntent intent,
            Map<String, Object> evidence) {
        Object[] parts = new Object[5 + CaptureIntent.APPLIED_HASH_FIELDS.size()];
        parts[0] = CAPTURE_DIGEST_DOMAIN;
        parts[1] = repositoryId;
        parts[2] = intent.intentId();
        parts[3] = intent.connectorId();
        parts[4] = intent.sourceObjectId();
        int i = 5;
        for (String field : CaptureIntent.APPLIED_HASH_FIELDS) {
            parts[i++] = evidence == null ? null : evidence.get(field);
        }
        return LineageCanonicalHash.hash(parts);
    }
}
