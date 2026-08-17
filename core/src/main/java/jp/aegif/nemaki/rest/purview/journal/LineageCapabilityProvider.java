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
 * {@code spool:v2} (convergent materializer + verified bound ACK, D-rest-4). {@code read:v2}
 * (decode a v2 event and hand it to the projector) has been true since A-2 Slice 2's final
 * commit and belongs in the same set — §6-a's capability table lists it as required, and a
 * provider that omits it cannot satisfy a barrier that demands it (v2.3.23).
 *
 * <p><b>Wired is not ready.</b> This set is a STATIC fact about the binary; whether the
 * machinery actually runs on this node is {@link LineageDrestReadiness}. §6-a's barrier
 * requires BOTH, so that an ACTIVE flip cannot open v2 writes while the sequencer and
 * projector sit dormant behind a red gate.
 *
 * <p>{@code active} is the runtime gate ({@link LineageDrestReadiness}), surfaced beside the
 * wired set on the admin status route. EXPOSURE of these capabilities into the §6-a rollout
 * ACK document is deliberately not here — that is 4a's flip machinery.
 */
@Component
public class LineageCapabilityProvider {

    private static final Set<String> WIRED = Set.of(
            "read:v2", "sequencer:event-first", "cursor:cas", "replay:generation-cas",
            "spool:v2",
            // §2's catalog obligations (v2.3.37). In the required set because 4b is a flag
            // flip: a node whose binary cannot create, claim, resolve or reclaim an obligation
            // would, the moment v2 writes open, meet an endpoint whose catalog entity is not
            // ready and have nowhere to park it. Requiring it here is what makes an ACK from
            // such a binary fail condition 8 instead of the gap being found after the flip.
            "catalog:obligations");

    /** The immutable wired-capability set of this binary. */
    public Set<String> wiredCapabilities() {
        return WIRED;
    }
}
