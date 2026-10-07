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
 * An upgrade that did not ask its rung, could not ask it, or could not use the answer (c46).
 *
 * <p>{@link AnchorTarget#upgrade} used to hand the pending receipt back unchanged for each of
 * these, and the upgrade pass read the unchanged receipt as "asked; nothing had settled yet — not
 * a failure, do not re-anchor". A sidecar that was down and a calendar that had answered "not yet"
 * were the same value.
 *
 * <p>The message is written by the rung and names no destination: it reaches the upgrade answer,
 * the scheduler's record and the screen.
 */
public class AnchorUpgradeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnchorUpgradeException(String reason) {
        super(reason);
    }
}
