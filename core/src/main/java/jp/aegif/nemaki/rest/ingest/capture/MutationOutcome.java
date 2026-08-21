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
package jp.aegif.nemaki.rest.ingest.capture;

/**
 * What we KNOW about whether one tracked mutation succeeded.
 *
 * <p>This is knowledge, not fact. It is deliberately distinct from
 * {@code CapturedContent.ContentState}, which records what the repository holds — the two live
 * side by side and only this one can stop a capture from completing (design §6.8-2):
 *
 * <table>
 *   <caption>The two three-valued types and what each one does</caption>
 *   <tr><th></th><th>meaning</th><th>blocks {@code CAPTURED}?</th></tr>
 *   <tr><td>{@code MutationOutcome}</td>
 *       <td>our knowledge of whether the operation succeeded</td>
 *       <td>{@link #FAILED} and {@link #INDETERMINATE} do</td></tr>
 *   <tr><td>{@code ContentState}</td>
 *       <td>the fact of what is stored</td>
 *       <td>no — {@code UNKNOWN} is recorded, not refused</td></tr>
 * </table>
 *
 * <p>An operation that was never attempted is not recorded at all. "Did not run" and "ran, result
 * unknown" are different, and only the second one is {@link #INDETERMINATE}.
 */
public enum MutationOutcome {

    /** The operation was performed and we have positive evidence it took effect. */
    SUCCEEDED,

    /** The operation was performed and we have positive evidence it did not take effect. */
    FAILED,

    /**
     * The operation was performed and we cannot tell whether it took effect.
     *
     * <p>The ingest path produces this far more often than it looks: writes go through wrappers
     * that catch everything and return {@code null}, so a 500, an auth failure and an
     * oversized document all arrive as the same absent answer. Collapsing that into
     * {@link #SUCCEEDED} is how a capture claims evidence it does not have.
     */
    INDETERMINATE;

    /**
     * Whether this outcome permits a capture to be recorded as complete.
     *
     * <p>Only {@link #SUCCEEDED} does. The predicate for {@code CAPTURED} is "every tracked
     * mutation succeeded", not "every mutation was attempted" — the ingest path has real
     * mutations whose failure is reported as a warning rather than raised, so "attempted" would
     * manufacture false evidence (design §5).
     */
    public boolean permitsCapture() {
        return this == SUCCEEDED;
    }
}
