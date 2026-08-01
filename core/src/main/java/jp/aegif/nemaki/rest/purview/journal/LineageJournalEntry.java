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
 * One journal row, in both of the forms its consumers need — and provably the same row in both.
 *
 * <h2>Why a pair and not one type</h2>
 *
 * <p>Display, routing and publication read the version-neutral {@link LineageRecord}; recovery
 * needs the original envelope, because a dead letter must be able to rebuild exactly what was
 * stored and the projection deliberately cannot (see {@link LineageRecord}'s javadoc). Slice 2c
 * carried the two as separate parameters so the envelope's remaining uses stayed visible; this
 * type exists for Slice 2d, where store reads start returning both together and a caller could
 * otherwise pair a projection with the wrong envelope.
 *
 * <h2>The envelope is a tagged union, not a common supertype</h2>
 *
 * <p>{@link LineageEvent} and {@link LineageEventV2} share no interface on purpose. A recovery
 * path must know which version it is holding — the two are stored under different document types
 * and rebuilt through different codecs — so the tag is the information, and a supertype would
 * only hide it until a cast.
 */
public record LineageJournalEntry(LineageRecord record, Envelope envelope) {

    /** The lossless original, tagged by version. */
    public sealed interface Envelope permits V1, V2 {
    }

    public record V1(LineageEvent event) implements Envelope {
        public V1 {
            if (event == null) {
                throw new IllegalArgumentException("v1 envelope must not be null");
            }
        }
    }

    public record V2(LineageEventV2 event) implements Envelope {
        public V2 {
            if (event == null) {
                throw new IllegalArgumentException("v2 envelope must not be null");
            }
        }
    }

    /**
     * The two halves have to describe the same row. The factories below cannot get this wrong,
     * but the canonical constructor is public (records allow nothing else), so the invariant is
     * enforced rather than assumed: a projection paired with a different version's envelope, or
     * with another row's, is exactly the mix-up recovery must never start from.
     */
    public LineageJournalEntry {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        String envelopeRecordId = switch (envelope) {
            case V1 v1 -> v1.event().eventId();
            case V2 v2 -> v2.event().deliveryId();
        };
        if (!envelopeRecordId.equals(record.recordId())) {
            throw new IllegalArgumentException("projection and envelope describe different rows:"
                    + " record.recordId=" + record.recordId() + ", envelope's id="
                    + envelopeRecordId);
        }
        int envelopeSchemaVersion = switch (envelope) {
            case V1 v1 -> v1.event().schemaVersion();
            case V2 v2 -> v2.event().schemaVersion();
        };
        if (envelopeSchemaVersion != record.schemaVersion()) {
            throw new IllegalArgumentException("projection and envelope disagree on schemaVersion:"
                    + " record=" + record.schemaVersion() + ", envelope=" + envelopeSchemaVersion);
        }
    }

    /** Projects and pairs in one step, so the halves cannot drift. */
    public static LineageJournalEntry ofV1(LineageEvent event) {
        return new LineageJournalEntry(LineageRecord.fromV1(event), new V1(event));
    }

    public static LineageJournalEntry ofV2(LineageEventV2 event) {
        return new LineageJournalEntry(LineageRecord.fromV2(event), new V2(event));
    }
}
