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

import jp.aegif.nemaki.fixity.FixityScanReport;
import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Puts a fixity pass into the evidence chain (P1-2 / P1-3 §2).
 *
 * <h2>One entry per PASS, not per object</h2>
 *
 * <p>A full scan over a hundred thousand objects would otherwise write a hundred thousand
 * entries, and the ledger is not purged. What a reader needs is not "object 47 was intact on
 * Tuesday" but "on Tuesday a pass covered this scope, and this is what it found" — the pass is
 * the fact, and the counts and findings are its content.
 *
 * <p>The digest therefore commits to the verdict, the scope, every count, and the identity of
 * every finding. Two passes with the same counts but different mismatching objects produce
 * different entries, which is the difference that matters.
 *
 * <h2>What an entry does and does not establish</h2>
 *
 * <p>It establishes that a pass with these results was recorded at this position in the chain,
 * and that the record has not been altered since a later checkpoint. It does <b>not</b>
 * establish that the pass was run honestly, that the digests it compared against were right, or
 * that anything happened to objects outside its scope. A {@code PARTIAL} verdict travels into
 * the digest for exactly that reason: a chain entry that let a sample read as a full sweep would
 * be the strongest possible version of the weakest fact.
 *
 * <h2>Fail-open, like capture and unlike disposition</h2>
 *
 * <p>The pass has already happened when this runs, and its results are already in the response.
 * Refusing here would throw away a completed scan to protect a record of it. So a gap is
 * reported, not raised — the same shape as {@link EvidenceLedgerRecorder}, and the opposite of
 * {@link DispositionRecorder}, where the act has not happened yet.
 */
@Component
public class FixityLedgerRecorder {

    private static final Logger logger = LoggerFactory.getLogger(FixityLedgerRecorder.class);

    /** Domain-separated from every other digest in the product. */
    static final String FIXITY_DIGEST_DOMAIN = "LEDGER_FIXITY_RESULT_V1";

    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    public void setLedgerService(EvidenceLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Whether the pass reached the chain, and what to tell the caller if it did not. */
    public record Recorded(boolean inChain, String warning) {

        public Recorded {
            if (inChain && warning != null) {
                throw new IllegalArgumentException(
                        "a pass that reached the chain cannot also carry a gap warning");
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
     * Records one completed pass.
     *
     * @param scope what the pass covered, in the caller's own words — "repository", a folder id.
     *        Part of the digest: the same counts over a different scope are a different fact
     * @return whether it reached the chain. Never throws: the pass is already done
     */
    public Recorded recordPass(String repositoryId, String scope, FixityScanReport report,
            String occurredAt) {
        if (report == null) {
            return Recorded.gap(null);
        }
        if (report.verdict() == FixityScanReport.Verdict.NOT_RUN
                || report.verdict() == FixityScanReport.Verdict.RUNNING) {
            // Nothing happened yet. An entry here would put "a pass" in the chain for something
            // that has not produced a result.
            return Recorded.gap(null);
        }
        if (ledgerService == null) {
            logger.debug("No evidence ledger is wired; the fixity pass over {} is not chained",
                    repositoryId);
            return Recorded.gap(null);
        }
        String subjectId = subjectIdFor(repositoryId, scope, occurredAt);
        EvidenceLedgerService.AppendResult result;
        try {
            String digest = passDigest(repositoryId, scope, report);
            result = ledgerService.append(repositoryId,
                    EvidenceLedgerEntry.SubjectKind.FIXITY_RESULT, subjectId, digest, occurredAt);
        } catch (RuntimeException e) {
            logger.warn("Fixity pass over {} completed but could not be chained: {}",
                    repositoryId, e.getMessage());
            return Recorded.gap("This fixity pass ran, but its result could not be added to the "
                    + "evidence chain (" + e.getMessage() + "). The result below stands; the "
                    + "chain is missing this entry and will not be back-filled.");
        }
        if (result.recorded()) {
            return Recorded.chained();
        }
        logger.warn("Fixity pass over {} completed but was not chained: {}", repositoryId,
                result.reason());
        return Recorded.gap("This fixity pass ran, but its result was not added to the evidence "
                + "chain (" + result.reason() + "). The result below stands; the chain is "
                + "missing this entry and will not be back-filled.");
    }

    /**
     * The id under which this pass can be found again.
     *
     * <p>Scope plus time, because a repository has many passes and "the fixity result for
     * bedroom" is not a thing a reader can ask for.
     */
    static String subjectIdFor(String repositoryId, String scope, String occurredAt) {
        return "fixity:" + repositoryId + ":" + (scope == null ? "repository" : scope) + ":"
                + occurredAt;
    }

    /**
     * The canonical digest of a pass.
     *
     * <p>Every count, the verdict, the scope, and the identity of every finding. Recomputable
     * from the report a caller was shown, which is the point: a reader holding the response can
     * check that the chain committed to the same pass they were told about.
     */
    static String passDigest(String repositoryId, String scope, FixityScanReport report) {
        StringBuilder findings = new StringBuilder();
        // The record does not stop a null list reaching it, and a digest that threw here would
        // take down the pass it exists to record.
        List<FixityScanReport.Finding> found =
                report.findings() == null ? List.of() : report.findings();
        for (FixityScanReport.Finding finding : found) {
            // Length-prefixed for the same reason the disposition rule is: without it, two
            // different finding lists can flatten to one string.
            String objectId = String.valueOf(finding.objectId());
            findings.append(objectId.length()).append(':').append(objectId).append('=')
                    .append(finding.outcome() == null ? "-" : finding.outcome().name())
                    .append(';');
        }
        return LineageCanonicalHash.hash(FIXITY_DIGEST_DOMAIN, repositoryId,
                scope == null ? "repository" : scope,
                report.verdict().name(),
                String.valueOf(report.scanned()),
                String.valueOf(report.match()),
                String.valueOf(report.mismatch()),
                String.valueOf(report.unverifiable()),
                String.valueOf(report.notRecorded()),
                // The finding COUNT as well as the flattened list: the list is capped at
                // MAX_FINDINGS, so a pass with 600 mismatches and one with 500 would otherwise
                // commit to the same value once truncated.
                String.valueOf(found.size()),
                findings.toString());
    }
}
