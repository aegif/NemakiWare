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

/**
 * The typed <em>mutable envelope</em> of one stored v2 journal row — §8-a v2, frozen as
 * v2.3.18 ②.
 *
 * <p>{@link LineageEventV2} is the immutable business event: its fields are covered by
 * {@code creationPayloadDigest} and never change after {@code appendV2}. Everything the
 * fenced sequencer (and later the projector and replay machinery) mutates lives here,
 * <b>outside</b> the digest: the sequencing lifecycle state, the fencing coordinates
 * ({@code sequencerGeneration}, {@code sequencerLeaseToken}), and the CouchDB {@code _rev}
 * that makes every transition a CAS.
 *
 * <p>State-dependent requirements are enforced at construction, so a decoded row that claims
 * {@code SEQUENCING} without fencing coordinates — or {@code UNSEQUENCED} with a sequence —
 * cannot exist as a value:
 *
 * <ul>
 *   <li>{@code UNSEQUENCED}: no generation, no token, {@code event.sequenceNumber() == 0};</li>
 *   <li>{@code SEQUENCING}: generation and token present, sequence still 0;</li>
 *   <li>{@code SEQUENCED}: generation and token present (audit), sequence &gt; 0.</li>
 * </ul>
 */
public record LineageJournalRowV2(
        LineageEventV2 event,
        String rev,
        SequencingState state,
        Long sequencerGeneration,
        String sequencerLeaseToken
) {

    /** §8-a's sequencing lifecycle. Terminal for this machine is {@code SEQUENCED}. */
    public enum SequencingState { UNSEQUENCED, SEQUENCING, SEQUENCED }

    public LineageJournalRowV2 {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (rev == null || rev.isBlank()) {
            throw new IllegalArgumentException("rev must not be blank — every transition on"
                    + " this envelope is a CAS, and a row without its _rev cannot CAS");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        boolean hasGeneration = sequencerGeneration != null;
        boolean hasToken = sequencerLeaseToken != null && !sequencerLeaseToken.isBlank();
        switch (state) {
            case UNSEQUENCED -> {
                if (hasGeneration || hasToken) {
                    throw new IllegalArgumentException("an UNSEQUENCED row must carry no"
                            + " fencing coordinates");
                }
                if (event.sequenceNumber() != 0) {
                    throw new IllegalArgumentException("an UNSEQUENCED row must have"
                            + " sequenceNumber 0, got " + event.sequenceNumber());
                }
            }
            case SEQUENCING -> {
                if (!hasGeneration || !hasToken) {
                    throw new IllegalArgumentException("a SEQUENCING row must carry"
                            + " sequencerGeneration and sequencerLeaseToken");
                }
                if (event.sequenceNumber() != 0) {
                    throw new IllegalArgumentException("a SEQUENCING row must still have"
                            + " sequenceNumber 0 — finalize sets it with SEQUENCED in one CAS");
                }
            }
            case SEQUENCED -> {
                if (!hasGeneration || !hasToken) {
                    throw new IllegalArgumentException("a SEQUENCED row keeps its fencing"
                            + " coordinates for audit");
                }
                if (event.sequenceNumber() <= 0) {
                    throw new IllegalArgumentException("a SEQUENCED row must have a positive"
                            + " sequence, got " + event.sequenceNumber());
                }
            }
        }
    }

    /** The CouchDB document id this row lives under. */
    public String documentId() {
        return CouchLineageEventV2.documentId(event.deliveryId());
    }
}
