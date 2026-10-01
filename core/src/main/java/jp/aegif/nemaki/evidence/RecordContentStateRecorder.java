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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Records what one version's bytes were, and leaves a durable gap when it cannot (E1).
 *
 * <p>The order is fixed and is the whole design:
 *
 * <ol>
 * <li>{@link #openBeforeWriting} — a durable row saying a write is about to happen;
 * <li>the caller writes the bytes;
 * <li>{@link #recordAndClose} — the statement goes into the ledger, then the row is closed.
 * </ol>
 *
 * <p>Every step can fail, and each failure leaves a state a reader can tell apart:
 *
 * <table>
 * <caption>What survives a crash at each point</caption>
 * <tr><th>Crash</th><th>What is left</th></tr>
 * <tr><td>before 1</td><td>nothing — no bytes, no row. Correct.</td></tr>
 * <tr><td>between 1 and 2</td><td>an open row and no bytes. Reported as unresolved; a
 *     verifier sees no statement, which is the honest answer.</td></tr>
 * <tr><td>between 2 and 3</td><td><b>the case this exists for</b>: bytes with no statement,
 *     and an open row that says so.</td></tr>
 * <tr><td>after the append, before the close</td><td>the statement IS in the ledger and the row
 *     is still open. A retry finds the entry and closes the row rather than appending a second
 *     one — see {@link ContentWriteJournal.CloseOutcome#ALREADY_CLOSED}.</td></tr>
 * </table>
 *
 * <p><b>None of these fail the caller's operation.</b> An evidence outage that refused check-ins
 * would convert a recording problem into a business failure, which the plan forbids. What it
 * must never do instead is report success: every method here returns what actually happened.
 */
@Component
public class RecordContentStateRecorder {

    private static final Logger logger = LoggerFactory.getLogger(RecordContentStateRecorder.class);

    /** The ledger domain E1's entries live in. */
    public static final String DOMAIN = "record-content";

    private EvidenceLedgerService ledgerService;
    private ContentWriteJournal journal;

    private final AtomicLong journalOutages = new AtomicLong();
    private final AtomicLong unrecordedWrites = new AtomicLong();

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Autowired(required = false)
    public void setJournal(ContentWriteJournal journal) {
        this.journal = journal;
    }

    /**
     * What a caller holds between the two halves.
     *
     * @param intentId null when the journal could not be written. Not an error the caller
     *        reacts to — but not nothing either: {@link #recordAndClose} still records the
     *        statement, and the fact that this particular gap could not be LISTED is counted.
     */
    public record Pending(String intentId, String repositoryId, String objectId,
                          String versionObjectId, ContentWriteJournal.WriteKind kind) {
    }

    /**
     * Opens the durable row. Never throws: a journal outage must not stop a check-in.
     */
    public Pending openBeforeWriting(String repositoryId, String objectId, String versionObjectId,
            ContentWriteJournal.WriteKind kind, String openedAt) {
        if (journal == null || !journal.isActive()) {
            journalOutages.incrementAndGet();
            return new Pending(null, repositoryId, objectId, versionObjectId, kind);
        }
        try {
            String intentId = journal.open(repositoryId, objectId, versionObjectId, kind, openedAt);
            return new Pending(intentId, repositoryId, objectId, versionObjectId, kind);
        } catch (ContentWriteJournal.ContentWriteJournalUnavailable e) {
            journalOutages.incrementAndGet();
            logger.warn("The content-write journal could not record that a {} write on {} is "
                    + "about to happen. The write proceeds; the gap it may leave will not be "
                    + "listable, and no package for this version can satisfy RECORD_LEDGER_V1.",
                    kind, objectId, e);
            return new Pending(null, repositoryId, objectId, versionObjectId, kind);
        }
    }

    /** What {@link #recordAndClose} actually achieved. */
    public enum Outcome {
        /** The statement is in the chain and the row is closed. */
        CHAINED,
        /**
         * The statement is in the chain and the row could not be closed.
         *
         * <p>Not a failure of the evidence — the entry is there — but the row stays open and
         * will be listed as unresolved until a later pass closes it. Reported separately so an
         * operator is not told to hunt for a missing statement that exists.
         */
        CHAINED_ROW_STILL_OPEN,
        /**
         * Nothing was recorded, and the open row says so.
         *
         * <p>The durable gap the design is for.
         */
        UNRECORDED_GAP_OPEN,
        /**
         * Nothing was recorded and there is no row either.
         *
         * <p>The journal was down when the write started. The gap is real and is NOT listable;
         * what a verifier sees is a version with no statement, which it must report as
         * {@code NOT_PRESENT} rather than passing.
         */
        UNRECORDED_GAP_UNLISTABLE,
        /**
         * The row names a different version, so this statement was not attached to it.
         *
         * <p>The document moved on between opening and closing. Attaching would tie these bytes
         * to the previous version's row.
         */
        REFUSED_WRONG_VERSION
    }

    public record Result(Outcome outcome, String statementDigest, long sequence, String detail) {
        public boolean inChain() {
            return outcome == Outcome.CHAINED || outcome == Outcome.CHAINED_ROW_STILL_OPEN;
        }
    }

    /**
     * Records the statement and closes the row.
     *
     * @param statement what the bytes were. Built by the caller, because only the caller knows
     *        the version key and the digest it just wrote.
     */
    public Result recordAndClose(Pending pending, RecordStatement statement) {
        if (statement == null) {
            throw new IllegalArgumentException("there is no statement to record");
        }
        // A null version on the row means the write is a CREATE: the document did not exist
        // when the row was opened, so there is no version key for it to be about — and no
        // previous version for a stale intent to be confused with either, which is the case
        // this check exists for.
        if (pending != null && pending.versionObjectId() != null
                && !statement.versionObjectId().equals(pending.versionObjectId())) {
            // Caught here rather than at the store, because at the store it would be one of
            // several reasons a close failed. The caller built both, and a mismatch means the
            // two halves are about different versions.
            return new Result(Outcome.REFUSED_WRONG_VERSION, null, -1,
                    "the write was opened for version " + pending.versionObjectId()
                            + " and the statement is about " + statement.versionObjectId());
        }
        String digest = statement.digest();

        EvidenceLedgerService.AppendResult appended = ledgerService == null
                ? new EvidenceLedgerService.AppendResult(
                        EvidenceLedgerService.AppendOutcome.UNAVAILABLE, -1, null,
                        "the evidence ledger is not wired")
                : ledgerService.append(DOMAIN, statement.subjectKind(),
                        statement.versionObjectId(), digest, statement.recordedAt());

        if (!appended.recorded()) {
            unrecordedWrites.incrementAndGet();
            Outcome outcome = pending != null && pending.intentId() != null
                    ? Outcome.UNRECORDED_GAP_OPEN
                    : Outcome.UNRECORDED_GAP_UNLISTABLE;
            logger.warn("The content statement for {} was not chained ({}: {}). {}",
                    statement.versionObjectId(), appended.outcome(), appended.reason(),
                    outcome == Outcome.UNRECORDED_GAP_OPEN
                            ? "The open journal row records the gap."
                            : "There is no journal row either, so the gap is not listable.");
            return new Result(outcome, digest, -1, appended.reason());
        }

        if (pending == null || pending.intentId() == null || journal == null) {
            // Recorded, and there was never a row to close. Reported as still-open rather than
            // CHAINED so the two are not merged: CHAINED asserts a closed row.
            return new Result(Outcome.CHAINED_ROW_STILL_OPEN, digest, appended.sequence(),
                    "there was no journal row to close");
        }
        ContentWriteJournal.CloseOutcome closed;
        try {
            closed = journal.close(pending.intentId(), statement.versionObjectId(), digest,
                    statement.toDocument(), appended.sequence());
        } catch (RuntimeException e) {
            logger.warn("The journal row {} could not be closed after its statement was chained "
                    + "at sequence {}. It stays open and will be listed as unresolved.",
                    pending.intentId(), appended.sequence(), e);
            return new Result(Outcome.CHAINED_ROW_STILL_OPEN, digest, appended.sequence(),
                    e.getMessage());
        }
        return switch (closed) {
            case CLOSED, ALREADY_CLOSED ->
                    new Result(Outcome.CHAINED, digest, appended.sequence(), null);
            case WRONG_VERSION -> new Result(Outcome.REFUSED_WRONG_VERSION, digest,
                    appended.sequence(), "the journal row is for another version");
            case UNAVAILABLE -> new Result(Outcome.CHAINED_ROW_STILL_OPEN, digest,
                    appended.sequence(), "the journal could not be asked to close the row");
        };
    }

    /**
     * The digest of a version's bytes as the ledger last recorded it, and the entry that
     * recorded it. Null when the ledger holds none, or when the journal could not be asked —
     * "not known", which the transition statement writes as null and never as a digest.
     */
    public record Prior(String contentDigest, long entrySequence) {
    }

    /**
     * Copies, never computes (design §1.2). A state statement gives its own digest; a
     * transition after it carries the digest forward, because a copy to the archive or to cold
     * storage does not change the bytes — so a second transition still names the last state
     * that was actually recorded, through the first.
     */
    public Prior priorFor(String repositoryId, String versionObjectId) {
        if (journal == null || !journal.isActive()) {
            return null;
        }
        ContentWriteJournal.Recorded latest;
        try {
            latest = journal.latestRecorded(repositoryId, versionObjectId);
        } catch (RuntimeException e) {
            logger.warn("The prior statement for {} could not be read; the transition will say "
                    + "it is not known.", versionObjectId, e);
            return null;
        }
        if (latest == null || latest.statement() == null) {
            return null;
        }
        Object own = latest.statement().get("contentDigest");
        if (own instanceof String digest && !digest.isBlank()) {
            return new Prior(digest, latest.entrySequence());
        }
        Object carried = latest.statement().get("priorContentDigest");
        Object from = latest.statement().get("priorStatementEntrySequence");
        if (carried instanceof String digest && !digest.isBlank() && from instanceof Number at) {
            return new Prior(digest, at.longValue());
        }
        return null;
    }

    /**
     * Records that {@code transition} happened to the version {@code pending} was opened for,
     * with the prior digest copied from the ledger, and closes the row.
     *
     * <p>Never throws: a transition that cannot be recorded is logged and reported, not a
     * reason to fail the archive, restore or destroy it belongs to (the plan's rule for E1).
     */
    public Result recordTransition(Pending pending, RecordContentTransitionV1.Transition transition,
            RecordContentTransitionV1.BytesNow bytesNow) {
        if (pending == null) {
            throw new IllegalArgumentException("a transition needs the row it was opened for");
        }
        try {
            Prior prior = priorFor(pending.repositoryId(), pending.versionObjectId());
            RecordContentTransitionV1 statement = new RecordContentTransitionV1(
                    pending.repositoryId(), pending.objectId(), pending.versionObjectId(),
                    transition, bytesNow,
                    prior == null ? null : prior.contentDigest(),
                    prior == null ? null : prior.entrySequence(),
                    java.time.Instant.now().toString());
            Result result = recordAndClose(pending, statement);
            if (!result.inChain()) {
                logger.warn("The {} transition for {} is not in the chain ({}: {}).", transition,
                        pending.versionObjectId(), result.outcome(), result.detail());
            }
            return result;
        } catch (RuntimeException e) {
            logger.warn("No transition statement was recorded for {} ({}).",
                    pending.versionObjectId(), transition, e);
            return new Result(Outcome.UNRECORDED_GAP_OPEN, null, -1, e.getMessage());
        }
    }

    /**
     * Closes {@code pending}'s row with no statement, because the write it announced
     * verifiably did not happen (see {@link ContentWriteJournal#abandon}).
     *
     * @return whether the row is now closed. False leaves it open and listed — the right side
     *         when the journal could not be asked
     */
    public boolean abandon(Pending pending, String reason) {
        if (pending == null || pending.intentId() == null || journal == null) {
            return false;
        }
        try {
            boolean closed = journal.abandon(pending.intentId(), reason);
            if (!closed) {
                logger.warn("The journal row {} for {} could not be closed as abandoned ({}); "
                        + "it stays open and will be listed as unresolved.", pending.intentId(),
                        pending.versionObjectId(), reason);
            }
            return closed;
        } catch (RuntimeException e) {
            logger.warn("The journal row {} for {} could not be abandoned.", pending.intentId(),
                    pending.versionObjectId(), e);
            return false;
        }
    }

    /**
     * Journal outages since startup.
     *
     * <p>In memory, and therefore NOT the durable gap record — that is the journal's open rows.
     * This counts the cases where even the row could not be written, which is the one kind of
     * gap nothing durable knows about. Zero here does not mean there are no gaps.
     */
    public long journalOutagesSinceStartup() {
        return journalOutages.get();
    }

    /** Writes whose statement did not reach the chain, since startup. Same caveat. */
    public long unrecordedWritesSinceStartup() {
        return unrecordedWrites.get();
    }
}
