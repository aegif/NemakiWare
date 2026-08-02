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

import java.util.Map;

/**
 * Decodes a raw v2 CouchDB document into the typed mutable envelope
 * {@link LineageJournalRowV2}, strictly.
 *
 * <p>The immutable half goes through {@link CouchLineageEventV2#fromMap} — the canonical
 * constructor path, which re-verifies identity and digest — and the mutable half
 * ({@code state}, {@code sequencerGeneration}, {@code sequencerLeaseToken}) is typed here,
 * with the state-dependent requirements enforced by the envelope record itself. A document
 * whose mutable fields contradict its state is malformed, loudly; it never becomes a value
 * the sequencer could act on.
 *
 * <p>Mutations are <b>field-preserving</b>: the store mutates the raw map it read (state and
 * fencing fields only) and writes it back under its {@code _rev} — fields this slice does not
 * know about (v1-side lifecycle maps, future replay metadata) ride along untouched.
 */
public final class CouchLineageJournalRowV2 {

    static final String FIELD_STATE = "state";
    static final String FIELD_GENERATION = "sequencerGeneration";
    static final String FIELD_LEASE_TOKEN = "sequencerLeaseToken";

    private CouchLineageJournalRowV2() {
    }

    /**
     * @throws IllegalArgumentException when the document is not a well-formed v2 row with a
     *                                  consistent mutable envelope
     */
    public static LineageJournalRowV2 fromRaw(Map<String, Object> doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        LineageEventV2 event = CouchLineageEventV2.fromMap(doc);
        String rev = doc.get("_rev") instanceof String r ? r : null;

        Object stateValue = doc.get(FIELD_STATE);
        if (!(stateValue instanceof String stateName) || stateName.isBlank()) {
            throw new IllegalArgumentException("v2 row '" + doc.get("_id") + "' has no state —"
                    + " appendV2 writes UNSEQUENCED, so absence is corruption, not legacy");
        }
        LineageJournalRowV2.SequencingState state;
        try {
            state = LineageJournalRowV2.SequencingState.valueOf(stateName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("v2 row '" + doc.get("_id")
                    + "' has unknown state '" + stateName + "' — this build cannot act on it");
        }

        Long generation = null;
        Object generationValue = doc.get(FIELD_GENERATION);
        if (generationValue != null) {
            if (!(generationValue instanceof Number n)) {
                throw new IllegalArgumentException("sequencerGeneration must be a number");
            }
            try {
                generation = new java.math.BigDecimal(n.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException("sequencerGeneration must be an exact"
                        + " integral value, got " + n);
            }
        }
        String token = null;
        Object tokenValue = doc.get(FIELD_LEASE_TOKEN);
        if (tokenValue != null) {
            if (!(tokenValue instanceof String t) || t.isBlank()) {
                throw new IllegalArgumentException("sequencerLeaseToken must be a non-blank"
                        + " string when present");
            }
            token = t;
        }

        return new LineageJournalRowV2(event, rev, state, generation, token);
    }

    /** Applies the claim/reclaim transition to the raw map: SEQUENCING + fencing coordinates. */
    static void applySequencing(Map<String, Object> raw, long generation, String leaseToken) {
        raw.put(FIELD_STATE, LineageJournalRowV2.SequencingState.SEQUENCING.name());
        raw.put(FIELD_GENERATION, generation);
        raw.put(FIELD_LEASE_TOKEN, leaseToken);
    }

    /** Applies finalize: SEQUENCED and the sequence in the same write (one CAS). */
    static void applyFinalize(Map<String, Object> raw, long sequence) {
        raw.put(FIELD_STATE, LineageJournalRowV2.SequencingState.SEQUENCED.name());
        raw.put("sequenceNumber", sequence);
    }
}
