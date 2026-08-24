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
package jp.aegif.nemaki.evidence.anchor;

/**
 * One place a checkpoint's Merkle root can be put where we cannot rewrite it (P2-0).
 *
 * <h2>The state that does not exist here</h2>
 *
 * <p>There is no boolean "anchored". An OpenTimestamps submission is PENDING until a Bitcoin
 * block confirms it, which takes hours, and during those hours the deployment would otherwise be
 * able to say "anchored" while nothing had yet been proved. {@link AnchorState} keeps
 * {@code SUBMITTED} and {@code CONFIRMED} apart for the same reason P1-2 keeps
 * {@code NOT_RECORDED} and {@code UNVERIFIABLE} apart: collapsing them always collapses toward
 * the stronger claim.
 *
 * <h2>Every tier carries its own claim</h2>
 *
 * <p>Tier 1 (a catalog) is neither a time proof nor independent; tier 2 says a COMMITMENT existed
 * by a block time; tier 3 binds a message imprint to a {@code genTime}. These are different
 * statements, and an implementation that returned a bare "confirmed" would let a deployment with
 * only tier 1 borrow tier 3's sentence. So {@link #claimLimits()} is part of the interface, and
 * {@link AnchorReceipt} refuses to be built without it.
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}.
 */
public interface AnchorTarget {

    /** Stable id used in configuration and in reports: {@code catalog}, {@code opentimestamps},
     *  {@code rfc3161}. */
    String tierId();

    /** Whether this deployment has switched this tier on. A tier that is off yields
     *  {@link AnchorState#NOT_ATTEMPTED} — never {@code FAILED}, which would read as an outage
     *  somebody should investigate. */
    boolean isEnabled();

    /**
     * What a confirmed anchor at this tier does NOT establish.
     *
     * <p>Never null and never blank. This sentence travels with the receipt into the evidence
     * report, and it is the only thing standing between "anchored at tier 1" and a reader's
     * assumption that a third party attested the time.
     */
    String claimLimits();

    /**
     * Submits the root. Must not throw for an ordinary failure — return
     * {@link AnchorState#FAILED} with a reason, so one tier being down cannot stop the others.
     */
    AnchorReceipt submit(String domain, long fromSequence, long toSequence, String merkleRoot,
            String createdAt);

    /**
     * Re-checks a {@link AnchorState#SUBMITTED} receipt, returning a confirmed one when the
     * external side has settled.
     *
     * <p>The default keeps the receipt as it is: a tier whose submission is immediately final has
     * nothing to upgrade, and one that has not implemented upgrading must NOT silently promote a
     * pending receipt to confirmed.
     */
    default AnchorReceipt refresh(AnchorReceipt receipt) {
        return receipt;
    }
}
