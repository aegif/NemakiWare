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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one tier did with one checkpoint root (P2-0).
 *
 * @param claimLimits what a CONFIRMED receipt at this tier still does not establish. Never blank
 *                    — the compact constructor refuses, exactly as {@code
 *                    AuthenticityReport.Section} does, and for the same reason: this sentence is
 *                    the difference between "the catalog also has this value" and "a third party
 *                    attested the time".
 * @param proof       the tier's evidence (an {@code .ots} blob's id, a TSA token's id, a catalog
 *                    entity id). Null while pending — and null here is NOT a reason to treat the
 *                    receipt as confirmed.
 */
public record AnchorReceipt(String tierId, AnchorState state, String domain, long fromSequence,
                            long toSequence, String merkleRoot, String submittedAt,
                            String confirmedAt, String proof, String reason, String claimLimits) {

    public AnchorReceipt {
        if (tierId == null || tierId.isBlank()) {
            throw new IllegalArgumentException("an anchor receipt must name its tier");
        }
        if (state == null) {
            throw new IllegalArgumentException("an anchor receipt without a state would have to "
                    + "be read as a boolean, which is the distinction this type exists to keep");
        }
        if (claimLimits == null || claimLimits.isBlank()) {
            throw new IllegalArgumentException("tier '" + tierId + "' produced a receipt with no "
                    + "claimLimits; without it a reader takes 'anchored' to mean whatever the "
                    + "strongest tier would mean");
        }
        if (state == AnchorState.CONFIRMED && (confirmedAt == null || confirmedAt.isBlank())) {
            // A confirmation with no time is not a confirmation anybody can use, and it is the
            // shape a copy-paste from the submitted branch produces.
            throw new IllegalArgumentException("tier '" + tierId + "' reported CONFIRMED without "
                    + "a confirmedAt");
        }
    }

    /** Whether this receipt may be counted as an anchor. {@code SUBMITTED} may not. */
    public boolean counts() {
        return state == AnchorState.CONFIRMED;
    }

    public static AnchorReceipt notAttempted(String tierId, String claimLimits) {
        return new AnchorReceipt(tierId, AnchorState.NOT_ATTEMPTED, null, -1, -1, null, null,
                null, null, "this tier is not enabled in this deployment", claimLimits);
    }

    public static AnchorReceipt failed(String tierId, String domain, String merkleRoot,
            String reason, String claimLimits) {
        return new AnchorReceipt(tierId, AnchorState.FAILED, domain, -1, -1, merkleRoot, null,
                null, null, reason, claimLimits);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", tierId);
        m.put("state", state.name());
        // Immediately after the state, so a reader cannot take the state alone.
        m.put("claimLimits", claimLimits);
        m.put("domain", domain);
        m.put("fromSequence", fromSequence);
        m.put("toSequence", toSequence);
        m.put("merkleRoot", merkleRoot);
        m.put("submittedAt", submittedAt);
        m.put("confirmedAt", confirmedAt);
        m.put("proof", proof);
        m.put("reason", reason);
        return m;
    }
}
