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

import java.util.List;
import java.util.Objects;

/**
 * The walk from a covering checkpoint to the one an external anchor committed to.
 *
 * <p>Specified in {@code docs/design/evidence-profile-v1.md} §11. An inclusion proof shows an
 * entry is inside ONE checkpoint's tree; this shows that checkpoint is the one the anchor
 * fixed, or is reachable from it by links that each commit to the one before.
 *
 * <h2>What a linked chain does not buy</h2>
 *
 * <p><b>The anchor commits to {@code merkleRoot}, not to {@code checkpointHash}</b> (see
 * {@code AnchorService#anchor}). So the anchor by itself says nothing about
 * {@code prevCheckpointHash} — the link to earlier periods lives only in this chain, and this
 * chain travels inside the package, which is the thing under examination. A holder who can
 * rewrite the ledger can rewrite the chain to agree with it.
 *
 * <p>What survives that is: the SET of entries under the anchored root was fixed when the
 * anchor was made. Detecting a rewrite of an earlier period needs a checkpoint held OUTSIDE the
 * package — the verifier's {@code --expected-checkpoint}. This class does not pretend otherwise,
 * and callers must not report a linked chain as if it had been anchored end to end.
 */
public final class CheckpointChain {

    /** Two values on purpose: this walk either holds or does not. Absence is the caller's. */
    public enum Verdict { PASS, FAIL }

    /**
     * @param reason null when {@link Verdict#PASS}; otherwise why, in words an operator can act
     *        on. A FAIL with no reason is how "the chain is broken" becomes unactionable.
     */
    public record Result(Verdict verdict, String reason) {
        public Result {
            if (verdict == Verdict.FAIL && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("a broken chain must say what is broken");
            }
            if (verdict == Verdict.PASS && reason != null) {
                throw new IllegalArgumentException("a chain that holds has nothing to explain");
            }
        }
    }

    private CheckpointChain() {
    }

    private static final Result PASS = new Result(Verdict.PASS, null);

    /**
     * Whether {@code links} walks from its first element to its last without a gap.
     *
     * <p>{@code links.get(0)} is the covering checkpoint and the last is the anchor target; a
     * chain of length 1 means they are the same, which is legitimate.
     *
     * <p><b>An empty chain is FAIL, not PASS.</b> Nothing to check is the argument that lets a
     * package with no chain at all read as one whose chain held.
     */
    public static Result verify(List<EvidenceCheckpoint> links) {
        if (links == null || links.isEmpty()) {
            return new Result(Verdict.FAIL, "there is no chain: a package that carries no link "
                    + "from the covering checkpoint to the anchored one has not shown they are "
                    + "connected");
        }
        for (int i = 0; i < links.size(); i++) {
            EvidenceCheckpoint link = links.get(i);
            if (link == null) {
                return new Result(Verdict.FAIL, "link " + i + " is missing");
            }
            String recomputed = recompute(link);
            if (!recomputed.equals(link.checkpointHash())) {
                return new Result(Verdict.FAIL, "link " + i + " does not hash to the value it "
                        + "records (" + link.checkpointHash() + " recorded, " + recomputed
                        + " recomputed), so its fields and its hash disagree");
            }
        }
        for (int i = 1; i < links.size(); i++) {
            EvidenceCheckpoint prior = links.get(i - 1);
            EvidenceCheckpoint here = links.get(i);
            if (!Objects.equals(here.prevCheckpointHash(), prior.checkpointHash())) {
                return new Result(Verdict.FAIL, "link " + i + " does not commit to link "
                        + (i - 1) + ": it names " + here.prevCheckpointHash() + " and the one "
                        + "before it hashes to " + prior.checkpointHash());
            }
            // Strictly increasing, not merely non-decreasing. Two checkpoints ending at the same
            // sequence are two answers for one period; taking either is choosing which of two
            // ledgers to believe, which is not a verifier's decision to make.
            if (here.toSequence() <= prior.toSequence()) {
                return new Result(Verdict.FAIL, "link " + i + " ends at sequence "
                        + here.toSequence() + ", which does not come after link " + (i - 1)
                        + "'s " + prior.toSequence() + ". A chain that runs backwards or stands "
                        + "still is not a walk forward to the anchor");
            }
        }
        return PASS;
    }

    /**
     * The hash the link's own fields produce.
     *
     * <p>Computed through {@link EvidenceCheckpoint#computeHash} so there is one definition of
     * the checkpoint hash in the product. A private copy here would be a second one, and the two
     * would drift exactly like the three mappers did (R52).
     */
    private static String recompute(EvidenceCheckpoint link) {
        return EvidenceCheckpoint.computeHash(link.domain(), link.fromSequence(),
                link.toSequence(), link.merkleRoot(), link.prevCheckpointHash(),
                link.createdAt());
    }
}
