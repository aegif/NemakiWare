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
 * <p>Implementations MUST NOT throw for ordinary remote failure. An anchor that cannot be
 * reached is a fact to record — {@link AnchorReceipt#failed} — not an exception to propagate
 * into whatever operation triggered the anchoring. Anchoring is evidence gathering; it must
 * never be able to fail a CMIS write. Only programming errors (a null digest, a malformed one)
 * throw.
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
     */
    default AnchorReceipt upgrade(AnchorReceipt pending) {
        return pending;
    }
}
