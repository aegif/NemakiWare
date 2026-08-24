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
package jp.aegif.nemaki.fixity;

/**
 * What checking one object's stored bytes against its recorded digest established.
 *
 * <h2>Four values, not three</h2>
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md} §2. The one that earns its place is
 * {@link #NOT_RECORDED}: an object with no recorded digest is not a FAILURE to verify, it is
 * outside what can be verified at all. Folding it into {@link #UNVERIFIABLE} would bury the real
 * unverifiable — the object whose bytes could not be read — under every pre-digest document in
 * the repository, and a value that is permanently present is a value operators learn to skip.
 *
 * <p>That is the same judgement the capture verifier made when it split {@code ABSENT} out of
 * {@code UNVERIFIABLE}, for the same reason.
 */
public enum FixityOutcome {

    /** The stored bytes hash to the recorded digest. */
    MATCH,

    /**
     * They do not.
     *
     * <p><b>This is not a proof of tampering.</b> The formula is public SHA-256 and
     * {@code nemaki:contentHash} is an ordinary stored property, so anything with direct
     * database access can rewrite BOTH and keep them agreeing. What a mismatch establishes is
     * narrower and still worth having: the bytes are not what this repository recorded, so
     * something changed them without going through the path that maintains the digest.
     */
    MISMATCH,

    /**
     * A digest was recorded and the check could not be carried out — the bytes could not be
     * read, or the object lives in a tier this pass cannot reach.
     *
     * <p>Deliberately not {@link #MISMATCH}: "we could not look" is not "we looked and it was
     * wrong", and reporting an unreadable attachment as a mismatch would send an operator
     * hunting for corruption that may not exist.
     */
    UNVERIFIABLE,

    /**
     * No digest was recorded for this object, so there is nothing to check it against.
     *
     * <p>Not a failure and not a gap in this pass — a gap in what was captured, which P1-1 owns.
     */
    NOT_RECORDED
}
