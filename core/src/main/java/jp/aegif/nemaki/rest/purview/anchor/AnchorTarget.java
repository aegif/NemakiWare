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
package jp.aegif.nemaki.rest.purview.anchor;

/**
 * One rung's destination for a digest.
 *
 * <p>Implementations take a hex digest and nothing else. That is a deliberate constraint rather
 * than a convenience: no content, no metadata and no identifiers leave the deployment, so the
 * privacy story of external anchoring is a property of this interface instead of a rule each
 * implementation has to remember. (OpenTimestamps additionally blinds the digest itself before
 * it reaches a calendar; see that implementation.)
 *
 * <p>{@link #anchor} MUST NOT throw for ordinary remote failure. An anchor that cannot be
 * reached is a fact to record — {@link AnchorReceipt#failed} — not an exception to propagate
 * into whatever operation triggered the anchoring. Anchoring is evidence gathering; it must
 * never be able to fail a CMIS write. Only programming errors (a null digest, a malformed one)
 * throw. {@link #upgrade} is the exception to the rule, and says why.
 */
public interface AnchorTarget {

    AnchorKind kind();

    /**
     * Whether this deployment has configured the target. False means the operator chose not to
     * climb this rung, which callers report as {@link AnchorStatus#NOT_CONFIGURED} rather than
     * as an error.
     */
    boolean isConfigured();

    /**
     * Anchor one digest.
     *
     * @param hexDigest lowercase hex SHA-256 (64 chars)
     * @return a receipt, never null; failure is reported in the receipt, not thrown
     * @throws IllegalArgumentException if the digest is absent or not 64 hex characters — a
     *         caller passing garbage is a bug here, not a remote failure to be recorded
     */
    AnchorReceipt anchor(String hexDigest);

    /**
     * Move a {@link AnchorStatus#PENDING} receipt forward if it can be moved.
     *
     * <p>Only OpenTimestamps genuinely needs this: its commitments become verifiable hours after
     * they are made, and until then the proof is incomplete. Targets that confirm synchronously
     * return the receipt unchanged, so a scheduler can call this over every pending receipt
     * without knowing which kinds care.
     *
     * <p>A receipt comes back only as an ANSWER: the rung asked and this is what it learned
     * (the same receipt when nothing changed), or the receipt is not this rung's to move. A rung
     * that did not ask, could not ask, or could not use the answer throws
     * {@link AnchorUpgradeException} instead (c46): its caller is the upgrade pass, not a CMIS
     * write, and an unchanged receipt handed back for a sidecar that was down read as "asked;
     * nothing had settled yet — do not re-anchor".
     */
    default AnchorReceipt upgrade(AnchorReceipt pending) {
        return pending;
    }

    /**
     * Why this rung is refused without being asked — a configuration it will not send with — or
     * null when it would be asked. A caller that would otherwise say the rung was contacted
     * again, or that nothing had settled yet, says this instead (c44, P1: an upgrade refused for
     * an {@code @} in the URL was answered "nothing had settled yet … not a failure", and a retry
     * "the rungs that held nothing were contacted again").
     */
    default String refusal() {
        return null;
    }
}
