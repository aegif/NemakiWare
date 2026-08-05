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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which events are waiting on an obligation, read from the journal.
 *
 * <h2>What "skip" must never mean here</h2>
 *
 * <p>This reader feeds the only decision that can write a permanent entity into a catalog. A row
 * it drops is a snapshot the resolver never sees, and the resolver's answers are shaped by what
 * it is given: fewer candidates look like a smaller, cleaner population rather than an
 * incomplete one. So the rules are asymmetric on purpose.
 *
 * <ul>
 *   <li>A row that <em>does not belong</em> — wrong type, wrong schema version, a target that
 *       does not wait on this task — is filtered. It is not this task's business.</li>
 *   <li>A row that <em>belongs but cannot be read</em> — malformed, no task keys, a snapshot
 *       whose subject disagrees, an origin nobody can name — is surfaced as an unusable
 *       candidate rather than dropped, so the resolver classifies it CORRUPT instead of
 *       reporting a clean smaller set.</li>
 *   <li>A query that failed is not an empty result. {@code requireViewResult} turns a missing
 *       view into an exception, which the resolver reports as INDETERMINATE.</li>
 * </ul>
 *
 * <h2>Everything comes from one document</h2>
 *
 * <p>The order, the provenance and the snapshot are built from the same row. Reading any of them
 * from somewhere else would let a snapshot be paired with another delivery's observation
 * sequence, and observation order is what decides whether a purge or a restore wins.
 */
public final class CouchLineageWaitingEventSource
        implements LineageWaitingSnapshotResolver.WaitingEventSource {

    private static final Logger logger =
            LoggerFactory.getLogger(CouchLineageWaitingEventSource.class);

    /** Bounded: one obligation with an unbounded waiting set must not read a whole database. */
    static final int MAX_CANDIDATES = 1_000;

    private final LineageStoreSupport support;

    public CouchLineageWaitingEventSource(LineageStoreSupport support) {
        this.support = support;
    }

    @Override
    public List<LineageWaitingSnapshotResolver.Candidate> candidatesFor(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return List.of();
        }
        support.ensureDatabase();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", taskKey);
        params.put("limit", MAX_CANDIDATES);
        // reduce=false is REQUIRED: the view carries a _count reduce for the status routes, and
        // CouchDB rejects include_docs on a reduce query outright.
        params.put("reduce", false);
        params.put("include_docs", true);
        // A missing view must never read as "nobody is waiting" — that is the answer that lets
        // an obligation be settled against a population nobody enumerated.
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                LineageStoreDecoding.requireViewResult(
                        support.client().queryView(support.designDoc(),
                                "v2_waiting_by_task_key", params), "v2_waiting_by_task_key");
        if (result.getRows() == null) {
            return List.of();
        }

        List<LineageWaitingSnapshotResolver.Candidate> candidates = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
            com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
            if (doc == null) {
                // include_docs asked for the document and the row has none. Not a filter: the
                // row exists, so something is waiting and this cannot read it.
                throw new CorruptWaitingEventException(
                        CorruptWaitingEventException.Reason.ROW_WITHOUT_DOCUMENT);
            }
            String target = row.getValue() instanceof String s ? s : null;
            Map<String, Object> raw = new LinkedHashMap<>();
            if (doc.getId() != null) {
                raw.put("_id", doc.getId());
            }
            if (doc.getRev() != null) {
                raw.put("_rev", doc.getRev());
            }
            if (doc.getProperties() != null) {
                raw.putAll(doc.getProperties());
            }
            LineageWaitingSnapshotResolver.Candidate candidate =
                    candidateFrom(raw, taskKey, target);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * One row as a candidate, or null when the row is not this task's business.
     *
     * <p>Null only for the filters — wrong type, wrong schema, a target that is not waiting on
     * this key. Anything that belongs and cannot be read throws, because a resolver that saw a
     * shorter list would report a clean result for an incomplete population.
     */
    private LineageWaitingSnapshotResolver.Candidate candidateFrom(Map<String, Object> raw,
            String taskKey, String target) {
        // Re-verified rather than trusted: the view's selector is JavaScript in a design
        // document that a different binary may have deployed.
        if (!"lineage_event_v2".equals(raw.get("type"))) {
            return null;
        }
        if (!(raw.get("schemaVersion") instanceof Number version) || version.intValue() != 2) {
            return null;
        }
        if (target == null || target.isBlank() || !waitsOnTask(raw, target, taskKey)) {
            // Either the row emitted a target this build cannot read, or that target is not
            // waiting on this task. Both mean the row is not this obligation's business.
            return null;
        }

        LineageJournalRowV2 decoded;
        try {
            decoded = support.decodeV2Strict(raw);
        } catch (RuntimeException malformed) {
            // Class name only: a decode message quotes the document, and a v2 document carries
            // endpoint attributes and qualified names.
            logger.warn("A waiting v2 row could not be decoded: {}",
                    malformed.getClass().getSimpleName());
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.UNDECODABLE_ROW);
        }
        return LineageWaitingCandidates.from(decoded, target, taskKey);
    }

    /** Whether this target's waiting set really contains the task the view emitted it for. */
    @SuppressWarnings("unchecked")
    private static boolean waitsOnTask(Map<String, Object> raw, String target, String taskKey) {
        Object waits = raw.get(CouchLineageJournalRowV2.FIELD_WAITING_BY_TARGET);
        if (!(waits instanceof Map<?, ?> byTarget)) {
            return false;
        }
        Object wait = byTarget.get(target);
        if (!(wait instanceof Map<?, ?> forTarget)) {
            return false;
        }
        Object keys = forTarget.get(CouchLineageJournalRowV2.WAIT_TASK_KEYS);
        if (!(keys instanceof List<?> list) || list.isEmpty()) {
            // An empty waiting set on a row the view emitted is a contradiction, and the view
            // is written not to emit one. Refused rather than silently dropped.
            throw new CorruptWaitingEventException(
                    CorruptWaitingEventException.Reason.EMPTY_TASK_KEYS);
        }
        return list.contains(taskKey);
    }
}
