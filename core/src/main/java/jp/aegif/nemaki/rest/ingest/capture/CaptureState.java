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
 * The three states an intent row can be in. There are deliberately only three.
 *
 * <p>{@code ABANDONED} and {@code COMMIT_OBSERVED} were both considered and both rejected: each
 * requires deciding, after the fact, whether a document that exists came from this attempt — and
 * that decision needs a stamp the ingest path does not yet write (design §3.3, §5).
 */
public enum CaptureState {

    /** "We are about to ingest this." Written by the ingest before its first mutation. */
    CAPTURE_INTENT,

    /**
     * The business operation ran to the end and every tracked mutation succeeded.
     *
     * <p>Terminal. Written only by the ingest, never by the sweeper — the sweeper cannot know
     * that the mutations succeeded, only that time has passed.
     */
    CAPTURED,

    /**
     * Neither could be established. An intent that outlived its deadline lands here.
     *
     * <p>Written by the sweeper. A late-but-complete ingest may still move it to
     * {@link #CAPTURED}; nothing else may move it anywhere.
     */
    UNRESOLVED
}
