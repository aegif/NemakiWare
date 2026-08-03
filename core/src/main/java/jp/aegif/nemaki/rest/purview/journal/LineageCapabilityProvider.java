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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * v2.3.18 ⑥: the single immutable aggregate capability provider.
 *
 * <p>The wired set is a CONSTANT of this binary — parts do not self-register (a part
 * advertising itself mid-initialization would broadcast a half-built slice). All four
 * capabilities became true together when the D-rest program completed:
 * {@code sequencer:event-first} (fenced sequencer + safe v2 projection routing, D-rest-1/2),
 * {@code cursor:cas} (the production v2 walk advances only through the monotonic CAS,
 * D-rest-2), {@code replay:generation-cas} (request machine + crash recovery, D-rest-3),
 * {@code spool:v2} (convergent materializer + verified bound ACK, D-rest-4).
 *
 * <p>{@code active} is the runtime gate ({@link LineageDrestReadiness}), surfaced beside the
 * wired set on the admin status route. EXPOSURE of these capabilities into the §6-a rollout
 * ACK document is deliberately not here — that is 4a's flip machinery.
 */
@Component
public class LineageCapabilityProvider {

    private static final Set<String> WIRED = Set.of(
            "sequencer:event-first", "cursor:cas", "replay:generation-cas", "spool:v2");

    /** The immutable wired-capability set of this binary. */
    public Set<String> wiredCapabilities() {
        return WIRED;
    }
}
