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

import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records that something was disposed of, BEFORE it is disposed of (P3-3).
 *
 * <h2>Why this one is fail-CLOSED, when capture is fail-open</h2>
 *
 * <p>{@link EvidenceLedgerRecorder} runs after a capture is already durable, so a ledger it
 * cannot reach must never fail the ingest: refusing would destroy the very thing the record was
 * about. Disposition is the mirror image and the rule inverts with it.
 *
 * <p>Here the record comes FIRST and the irreversible act comes second, for the same reason the
 * capture boundary writes its intent first: content and evidence live in different databases
 * with no transaction across them, so whichever is written second is the one that can go
 * missing. If the deletion were first, a crash in between would leave <b>content deleted that
 * nothing records disposing of</b> — and unlike a capture, there is no object left afterwards
 * for anyone to notice was unaccounted for.
 *
 * <p>So {@link #authoriseDisposition} answers whether the irreversible step may proceed, and a
 * ledger that cannot be written answers <b>no</b>. The cost of refusing is a delay: the content
 * stays, the job runs again next cycle, and an operator sees a warning. The cost of proceeding
 * is a permanent gap in the one record that says what happened to a record. Those are not
 * comparable.
 *
 * <h2>The entry commits to the rule, not just the object</h2>
 *
 * <p>"This was deleted" is not a disposition trail. B.2 wants what, when, and <b>under which
 * rule</b> — so the digest covers the configuration that authorised it. A deployment that
 * shortened its retention window and re-ran the job produces different entries for the same
 * object, which is the difference a reader needs and a bare object id cannot show.
 *
 * <p>Design: {@code docs/design/p3-3-disposition-trail.md}.
 */
@Component
public class DispositionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(DispositionRecorder.class);

    /**
     * The domain string for a disposition entry's payload digest.
     *
     * <p>Separate from every other digest in the product, so a value computed for one purpose
     * cannot be carried into another.
     */
    static final String DISPOSITION_DIGEST_DOMAIN = "LEDGER_DISPOSITION_V1";

    /** What was done to the content. Not free text: a reader must be able to compare them. */
    public enum Act {
        /** The local copy was deleted after the bytes were written to cold storage. */
        LOCAL_CONTENT_DELETED_AFTER_COLD_MOVE
    }

    private EvidenceLedgerService ledgerService;

    /**
     * Optional in shape only — {@link EvidenceLedgerService} is a component-scanned
     * {@code @Component}, so in a running deployment this is always satisfied. Kept for direct
     * construction in tests, and so a bean that leaves the scan degrades rather than failing
     * the context.
     */
    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Whether the irreversible step may proceed, and why not when it may not. */
    public record Authorisation(boolean mayProceed, String refusedReason) {

        static Authorisation granted() {
            return new Authorisation(true, null);
        }

        static Authorisation refused(String reason) {
            return new Authorisation(false, reason);
        }
    }

    /**
     * Writes the disposition entry and says whether the caller may go ahead.
     *
     * @param subjectId what is being disposed of — the id a reader would use to look for it
     * @param rule the configuration that authorised this, as key/value pairs. Goes into the
     *        digest, not into the entry: the ledger is not purged, and a rule map is small but
     *        it is still content, and §2 says entries carry digests
     * @return {@code mayProceed=false} when the entry could not be written. <b>The caller must
     *         not proceed.</b> Returning "granted" on a failed write would be the whole point
     *         of this class thrown away
     */
    public Authorisation authoriseDisposition(String repositoryId, Act act, String subjectId,
            Map<String, String> rule, String occurredAt) {
        if (ledgerService == null) {
            // Unreachable where the component scan runs. Still refuses: "we cannot record it"
            // and "there is nothing to record" are different, and only one of them permits an
            // irreversible act.
            logger.warn("No evidence ledger is wired; refusing to dispose of {} in {}",
                    subjectId, repositoryId);
            return Authorisation.refused("the evidence ledger is not wired on this node, so this "
                    + "disposition cannot be recorded and must not happen");
        }
        EvidenceLedgerService.AppendResult result;
        try {
            // Inside the guard: the digest reads a caller-supplied map, and a throw here would
            // become an exception in a scheduled job rather than a refusal it can report.
            String digest = dispositionDigest(repositoryId, act, subjectId, rule);
            result = ledgerService.append(repositoryId,
                    EvidenceLedgerEntry.SubjectKind.DISPOSITION, subjectId, digest, occurredAt);
        } catch (RuntimeException e) {
            logger.warn("Refusing to dispose of {} in {}: the ledger entry could not be written "
                    + "({})", subjectId, repositoryId, e.getMessage());
            return Authorisation.refused("this disposition could not be recorded ("
                    + e.getMessage() + "), so it did not happen. The content is untouched and "
                    + "the next run will try again.");
        }
        if (result.recorded()) {
            return Authorisation.granted();
        }
        logger.warn("Refusing to dispose of {} in {}: the ledger did not accept the entry ({})",
                subjectId, repositoryId, result.reason());
        return Authorisation.refused("this disposition was not recorded (" + result.reason()
                + "), so it did not happen. The content is untouched and the next run will try "
                + "again.");
    }

    /**
     * The canonical digest of a disposition.
     *
     * <p>Recomputable by anyone holding the disposition's facts, which is the point: the ledger
     * commits to WHICH object, under WHICH rule, by WHICH act — and a verifier can reproduce
     * that from the retention configuration and the object id without trusting this code.
     */
    static String dispositionDigest(String repositoryId, Act act, String subjectId,
            Map<String, String> rule) {
        // Sorted, because a Map's iteration order is not part of the fact being committed to
        // and a digest that changed with it would be unreproducible.
        Map<String, String> canonical = new java.util.TreeMap<>(
                rule == null ? Map.of() : rule);
        StringBuilder flattened = new StringBuilder();
        for (Map.Entry<String, String> entry : canonical.entrySet()) {
            // Length-prefixed, so {"ab":"c"} and {"a":"bc"} cannot flatten to the same string.
            flattened.append(entry.getKey().length()).append(':').append(entry.getKey())
                    .append('=').append(String.valueOf(entry.getValue()).length()).append(':')
                    .append(entry.getValue()).append(';');
        }
        return LineageCanonicalHash.hash(DISPOSITION_DIGEST_DOMAIN, repositoryId, act.name(),
                subjectId, flattened.toString());
    }

    /** The retention settings that authorised a cold move, as they were read for THIS run. */
    public static Map<String, String> coldMoveRule(String afterDays, boolean keepLocalCopy,
            String storageType, String schedule) {
        Map<String, String> rule = new LinkedHashMap<>();
        rule.put("retention.archive.cold.after.days", String.valueOf(afterDays));
        rule.put("retention.cold.keep.local.copy", String.valueOf(keepLocalCopy));
        rule.put("retention.longterm.storage.type", String.valueOf(storageType));
        rule.put("retention.schedule.archive.cold", String.valueOf(schedule));
        return rule;
    }
}
