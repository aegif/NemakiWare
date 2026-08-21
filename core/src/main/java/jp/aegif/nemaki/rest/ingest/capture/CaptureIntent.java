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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What "we are about to ingest this" records, written BEFORE the first mutation.
 *
 * <p>This row is the only evidence that survives a crash between the content commit and the
 * evidence commit, and it is the entire content of the unresolved listing an operator reads. An
 * intent id and a timestamp would not tell them what happened, so every field that identifies
 * the attempt is written up front — {@code outputs} cannot be, because no object exists yet
 * (design §5.2).
 *
 * @param documentId       the CouchDB {@code _id}; see {@link #documentIdFor}
 * @param intentId         unique per attempt, NOT derived from the source item — the same source
 *                         may be ingested into two repositories, and a retry must not collide
 * @param intentOpenedAtMs when the intent was opened; the sort key of the unresolved listing
 * @param repositoryId     which repository, for the view key and the listing filter
 * @param connectorId      which configured connector performed the attempt
 * @param sourceSystem     the external system the item came from
 * @param sourceObjectType what kind of thing was being ingested
 * @param sourceObjectId   the item's identity in that system
 * @param requestId        so the row can be matched against the caller's request
 * @param processType      from the archetype
 * @param executedBy       the principal the server acted as
 * @param onBehalfOf       the principal the work was done for, when they differ
 */
public record CaptureIntent(
        String documentId,
        String intentId,
        long intentOpenedAtMs,
        String repositoryId,
        String connectorId,
        String sourceSystem,
        String sourceObjectType,
        String sourceObjectId,
        String requestId,
        String processType,
        String executedBy,
        String onBehalfOf) {

    /** The document type. Deliberately its own; it is never turned into a lineage event. */
    public static final String TYPE = "lineage_capture_intent";

    /**
     * The {@code _id} prefix.
     *
     * <p>Deliberately NOT the journal's {@code "lineage:"}. Four existing paths address journal
     * rows by {@code _id} built as {@code "lineage:" + recordId} — {@code findByRecordId} and
     * {@code getRetryCount} with no type check at all, {@code updatePublishStatus} and
     * {@code discardEvent} excluding only v2 — so an intent row under that prefix would be
     * readable as an ordinary event and writable by the admin console's retry and discard
     * buttons, which are transitions the state machine does not have. A separate prefix puts it
     * structurally out of their reach instead of relying on each of them to remember a guard
     * (design §5.3).
     */
    public static final String ID_PREFIX = "lineage_capture:";

    public static String documentIdFor(String intentId) {
        return ID_PREFIX + intentId;
    }

    /** The stored form. {@code captureState} because {@code state} is taken by v2 sequencing. */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", TYPE);
        doc.put("captureState", CaptureState.CAPTURE_INTENT.name());
        doc.put("intentId", intentId);
        doc.put("intentOpenedAtMs", intentOpenedAtMs);
        doc.put("repositoryId", repositoryId);
        doc.put("connectorId", connectorId);
        doc.put("sourceSystem", sourceSystem);
        doc.put("sourceObjectType", sourceObjectType);
        doc.put("sourceObjectId", sourceObjectId);
        doc.put("requestId", requestId);
        doc.put("processType", processType);
        doc.put("executedBy", executedBy);
        doc.put("onBehalfOf", onBehalfOf);
        return doc;
    }
}
