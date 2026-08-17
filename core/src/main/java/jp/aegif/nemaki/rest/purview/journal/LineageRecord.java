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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A journal record as its consumers need to see it, whichever envelope version it came from.
 *
 * <h2>What this is for, and what it must never be used for</h2>
 *
 * <p>For: display (the admin API), routing (the projector's claim and cursor) and catalog
 * publication (the three sinks). Those are the paths that can be moved off the concrete v1
 * envelope while v1 is still the only thing written, which is what makes the eventual write flip
 * a change of writer rather than a change of every consumer.
 *
 * <p><b>Not for: durable recovery.</b> A dead letter, a spool file and a replay payload must be
 * able to rebuild the original envelope exactly, and this type cannot — v2's identity depends on
 * {@code deliveryId}'s delivery union, {@code operationId}, the chunk coordinates and
 * {@code creationPayloadDigest}, none of which is here. The v1 dead-letter path is already lossy
 * in this way: {@link CouchLineageDeadLetterStore#reconstructEvent} hardcodes
 * {@code schemaVersion=1}, {@code sequenceNumber=0}, empty {@code runId} and
 * {@code correlationId}, and {@code version=1}. Extending that to v2 would produce a record whose
 * recomputed identity does not match the one it was stored under. Recovery keeps a version-tagged
 * lossless envelope; this projection stays out of it.
 *
 * <h2>One record with two adapters, not two subtypes</h2>
 *
 * <p>A sealed {@code V1}/{@code V2} pair would leave the envelope shapes visible and invite
 * {@code instanceof} in the consumers — which means the v2 branch of every consumer would be
 * written now and first executed at the flip. A single record populated by {@link #fromV1} and
 * {@link #fromV2} gives one code path that is exercised by v1 from the day it lands.
 *
 * <h2>Where the versions genuinely differ, they are named rather than flattened</h2>
 *
 * <table>
 *   <tr><th>this record</th><th>v1</th><th>v2</th></tr>
 *   <tr><td>{@code recordId}</td><td>{@code eventId}</td><td>{@code deliveryId}</td></tr>
 *   <tr><td>{@code processIdentity}</td><td>{@code eventKey}</td><td>{@code processKey}</td></tr>
 *   <tr><td>{@code idempotencyKeyVersion}</td><td>1</td><td>2</td></tr>
 *   <tr><td>{@code inputs}/{@code outputs}</td><td>{@link LineageAssetRef.LegacyName}</td>
 *       <td>{@link LineageAssetRef.Typed}</td></tr>
 *   <tr><td>{@code legacyEventAttributes}</td><td>{@code snapshotAttributes}</td><td>empty</td></tr>
 * </table>
 *
 * <p>{@code idempotencyKeyVersion} is carried so a consumer can tell which naming rule produced
 * {@code processIdentity}. The two produce different catalog Process names for the same business
 * operation, and the design's §3 accepts that: a v1 replay does not update the v1 Process, it
 * creates a v2 compensation event under a {@code processKey}-derived name, and both Processes then
 * stand as audit facts. That decision belongs to the replay and repair command paths. <b>This
 * projection does not implement it</b> — it reports which rule was used and nothing more.
 */
public record LineageRecord(
        int schemaVersion,
        int idempotencyKeyVersion,
        String recordId,
        String eventId,
        String processIdentity,
        String repositoryId,
        LineageProcessType processType,
        String occurredAt,
        long sequenceNumber,
        String correlationId,
        List<LineageAssetRef> inputs,
        List<LineageAssetRef> outputs,
        Map<String, LineagePublishStatus> publishStatusByTarget,
        Map<String, String> legacyEventAttributes
) {

    public LineageRecord {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be null or blank — it is what"
                    + " the projector claims and the cursor advances over");
        }
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be null or blank");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        publishStatusByTarget = publishStatusByTarget == null ? Map.of()
                : Map.copyOf(publishStatusByTarget);
        legacyEventAttributes = legacyEventAttributes == null ? Map.of()
                : Map.copyOf(legacyEventAttributes);
    }

    /**
     * Projects a v1 envelope.
     *
     * <p>The qualified names are carried through <b>verbatim</b> and wrapped as
     * {@link LineageAssetRef.LegacyName}. Nothing here inspects the string to guess a kind: v1
     * writes {@code objects/{id}} for documents and folders alike, so a guess would be wrong for
     * one of them, and it would be a second naming contract beside A-1's.
     *
     * <p>{@code snapshotAttributes} is carried as {@code legacyEventAttributes} rather than being
     * spread onto the assets. v1's Purview sink does spread it — the same event-level map onto
     * every input and output — and that is exactly the failure §2 replaced with endpoint-local
     * attributes. Reproducing it here would move the bug rather than leave it where it is.
     */
    public static LineageRecord fromV1(LineageEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return new LineageRecord(
                event.schemaVersion(),
                1,
                event.eventId(),
                event.eventId(),
                event.eventKey(),
                event.repositoryId(),
                event.processType(),
                event.occurredAt(),
                event.sequenceNumber(),
                event.correlationId(),
                legacyRefs(event.inputs()),
                legacyRefs(event.outputs()),
                event.publishStatusByTarget(),
                event.snapshotAttributes());
    }

    /**
     * Projects a v2 envelope.
     *
     * <p>Identities are copied, never recomputed. {@link LineageEventV2} has already recomputed
     * and verified them against A-1's frozen formulas at construction; doing it again here would
     * be a second implementation of the same contract, and a slower one.
     */
    public static LineageRecord fromV2(LineageEventV2 event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return new LineageRecord(
                event.schemaVersion(),
                event.idempotencyKeyVersion(),
                event.deliveryId(),
                event.eventId(),
                event.processKey(),
                event.repositoryId(),
                event.processType(),
                event.occurredAt(),
                event.sequenceNumber(),
                event.correlationId(),
                typedRefs(event.inputs()),
                typedRefs(event.outputs()),
                event.publishStatusByTarget(),
                Map.of());
    }

    /** True when {@code processIdentity} was produced by the v2 {@code processKey} rule. */
    public boolean isV2Identity() {
        return idempotencyKeyVersion == LineageIdentity.IDEMPOTENCY_KEY_VERSION;
    }

    /**
     * Every asset this record touches, inputs first. Direction is preserved by the two lists.
     *
     * <p>Sized from {@code inputs} rather than from a sum. A capacity hint computed as
     * {@code inputs.size() + outputs.size()} is arithmetic that no test can distinguish from
     * {@code -} on the shapes the process types actually produce, since none has more outputs than
     * inputs — so the expression would sit there untested and, on some future shape, throw for a
     * capacity hint.
     */
    public List<LineageAssetRef> allAssets() {
        List<LineageAssetRef> all = new ArrayList<>(inputs);
        all.addAll(outputs);
        return List.copyOf(all);
    }

    private static List<LineageAssetRef> legacyRefs(List<String> qualifiedNames) {
        List<LineageAssetRef> refs = new ArrayList<>(qualifiedNames.size());
        for (String qualifiedName : qualifiedNames) {
            refs.add(new LineageAssetRef.LegacyName(qualifiedName));
        }
        return refs;
    }

    private static List<LineageAssetRef> typedRefs(List<LineageEndpoint> endpoints) {
        List<LineageAssetRef> refs = new ArrayList<>(endpoints.size());
        for (LineageEndpoint endpoint : endpoints) {
            refs.add(new LineageAssetRef.Typed(endpoint));
        }
        return refs;
    }
}
