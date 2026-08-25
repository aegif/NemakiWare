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

    /**
     * How many gaps go by between WARN lines once the ledger has started failing.
     *
     * <p>Only the WARN channel is thinned. Every gap is still logged — the ones in between go
     * out at INFO, which every shipped logback configuration enables. The first version dropped
     * them to DEBUG, and all three shipped configurations set {@code jp.aegif.nemaki} to INFO
     * or WARN, so 99 gaps in every 100 were <b>lost entirely</b>: the fetch orchestrators
     * discard the returned warning, so the log was the only place left and it was empty. A
     * throttle that silences the thing it is throttling is not a throttle.
     */
    private static final int GAP_LOG_EVERY = 100;

    /** Gaps since startup. Only ever grows; this is the "how big is the hole" number. */
    private final java.util.concurrent.atomic.AtomicLong gapCount =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Gaps in the CURRENT run of them, reset by any capture that reaches the chain.
     *
     * <p>The WARN decision is made on this, not on the lifetime total. Keyed on the total, an
     * outage in the morning that left 250 gaps meant a single unrelated gap that afternoon
     * landed on count 251 — neither the first nor a multiple of a hundred — and never reached
     * WARN at all. "The first one is always logged" has to mean the first of THIS trouble.
     */
    private final java.util.concurrent.atomic.AtomicLong gapStreak =
            new java.util.concurrent.atomic.AtomicLong();

    private EvidenceLedgerService ledgerService;

    /**
     * Optional in shape only.
     *
     * <p>{@link EvidenceLedgerService} is a {@code @Component} and
     * {@code jp.aegif.nemaki.evidence} is component-scanned by {@code serviceContext.xml}, so in
     * a running deployment this is ALWAYS satisfied and the null branch below is unreachable.
     * It is kept for the tests that drive this class directly, and because a bean that
     * disappears from the scan should degrade rather than fail the context.
     *
     * <p>The consequence to be honest about: <b>there is no switch</b>. The ledger provisions
     * its own database on first use, so it is on wherever the ingest path is.
     */
    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * What happened, and what the caller has to be told.
     *
     * <p>The two are not independent, and the constructor says so. Nothing downstream read the
     * flag — {@code CaptureScope.chain} branched on the warning alone — so "flag + warning"
     * described an API that was really warning-only, and the day something returned a chained
     * result with an advisory note attached, the caller would have been told the chain had a
     * hole. Now the flag is what is read, and a combination that would make the two disagree
     * cannot be built.
     */
    public record Recorded(boolean inChain, String warning) {

        public Recorded {
            if (inChain && warning != null) {
                throw new IllegalArgumentException(
                        "a capture that reached the chain cannot also carry a gap warning; the "
                                + "caller reads one of these and would be told the opposite of "
                                + "the other");
            }
        }

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
            // Unreachable where the component scan runs (see the setter). Debug, not warn: this
            // is a wiring state, not an operational failure, and it does not reach the caller.
            logger.debug("No evidence ledger is wired; capture {} is not chained",
                    intent.intentId());
            return Recorded.gap(null);
        }
        EvidenceLedgerService.AppendResult result;
        // The digest is computed INSIDE the guard on purpose. It reads a caller-supplied map and
        // hashes it; if that throws, the exception would leave this method and fail an ingest
        // whose capture row is already durable — the exact outcome the rule above forbids.
        try {
            String digest = captureDigest(repositoryId, intent, evidence);
            result = ledgerService.append(repositoryId,
                    EvidenceLedgerEntry.SubjectKind.CAPTURE_COMPLETED, intent.intentId(), digest,
                    occurredAt);
        } catch (RuntimeException e) {
            // The capture is already durable. Losing it because the ledger is down would be a
            // far worse outcome than a gap in the chain — but the gap is still reported.
            logGap(intent.intentId(), e.getMessage());
            return Recorded.gap("This ingest was captured, but its record could not be added to "
                    + "the evidence chain (" + e.getMessage() + "). The capture itself stands; "
                    + "the chain is missing this entry and will not be back-filled.");
        }
        if (result.recorded()) {
            gapStreak.set(0);
            return Recorded.chained();
        }
        logGap(intent.intentId(), result.reason());
        return Recorded.gap("This ingest was captured, but its record was not added to the "
                + "evidence chain (" + result.reason() + "). The capture itself stands; the "
                + "chain is missing this entry and will not be back-filled.");
    }

    /**
     * Says a gap happened, without letting a long outage bury the line that says it started.
     *
     * <p>A CouchDB outage makes EVERY ingest fail to chain. Logging each one at warn turns the
     * one useful line — the first — into one of ten thousand identical ones, and teaches an
     * operator to filter the string out. The running count is what an operator actually needs
     * after the fact: how many entries the chain is missing.
     *
     * <p>The caller still gets its warning every time. That one is about its own ingest.
     */
    private void logGap(String intentId, String reason) {
        long total = gapCount.incrementAndGet();
        long streak = gapStreak.incrementAndGet();
        if (streak == 1 || streak % GAP_LOG_EVERY == 0) {
            logger.warn("Capture {} completed but was not chained: {} (gaps in this run: {}; "
                    + "since startup: {})", intentId, reason, streak, total);
        } else {
            // INFO, not DEBUG. The orchestrators that drive scheduled fetches discard the
            // returned warning, so if this line is not emitted the gap leaves no trace at all.
            logger.info("Capture {} completed but was not chained: {}", intentId, reason);
        }
    }

    /** How many completed captures have failed to reach the chain since startup. */
    public long gapsSinceStartup() {
        return gapCount.get();
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
