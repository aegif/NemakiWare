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
import java.util.Optional;

/**
 * Compensation requests over the lineage database.
 *
 * <p>Same database and the same strict-IO rules as the obligations: one provisioning path, one
 * definition of what a 404 means. Every transition is a {@code _rev} CAS, and a lost CAS is
 * {@code false} rather than an exception — two workers finishing one compensation is ordinary.
 */
public class CouchLineageHistoricalCompensationStore
        implements LineageHistoricalCompensationStore {

    private final LineageStoreSupport support;

    public CouchLineageHistoricalCompensationStore(LineageStoreSupport support) {
        this.support = support;
    }

    @Override
    public LineageHistoricalCompensation createIfAbsent(
            LineageHistoricalCompensation compensation) {
        support.ensureDatabase();
        Map<String, Object> existing = support.readRawStrict(compensation.documentId());
        if (existing != null) {
            return fromRaw(existing);
        }
        Map<String, Object> raw = toRaw(compensation);
        raw.put("_id", compensation.documentId());
        try {
            support.client().create(raw);
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException raced) {
            Map<String, Object> now = support.readRawStrict(compensation.documentId());
            if (now == null) {
                throw new IllegalStateException(
                        "a compensation conflicted on create and then vanished");
            }
            return fromRaw(now);
        }
        return read(compensation.taskId()).orElseThrow(() -> new IllegalStateException(
                "a compensation is not readable after being created"));
    }

    @Override
    public Optional<LineageHistoricalCompensation> read(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(
                LineageHistoricalCompensation.DOCUMENT_ID_PREFIX + taskId);
        return raw == null ? Optional.empty() : Optional.of(fromRaw(raw));
    }

    @Override
    public List<LineageHistoricalCompensation> findByState(
            LineageHistoricalCompensation.State state, int limit) {
        support.ensureDatabase();
        List<LineageHistoricalCompensation> found = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", state.name());
        params.put("limit", Math.max(1, limit));
        params.put("include_docs", true);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                support.client().queryView(support.designDoc(), "historicalCompensationsByState",
                        params);
        if (result == null || result.getRows() == null) {
            return List.of();
        }
        for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
            com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
            if (doc == null) {
                continue;
            }
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
            found.add(fromRaw(raw));
        }
        return found;
    }

    @Override
    public boolean markResolved(LineageHistoricalCompensation compensation, String reason) {
        return mark(compensation, LineageHistoricalCompensation.State.RESOLVED, reason);
    }

    @Override
    public boolean markFailed(LineageHistoricalCompensation compensation, String reason) {
        return mark(compensation, LineageHistoricalCompensation.State.FAILED, reason);
    }

    private boolean mark(LineageHistoricalCompensation compensation,
            LineageHistoricalCompensation.State state, String reason) {
        if (compensation == null) {
            return false;
        }
        support.ensureDatabase();
        Map<String, Object> raw = support.readRawStrict(compensation.documentId());
        if (raw == null) {
            return false;
        }
        raw.put("state", state.name());
        raw.put("reason", reason);
        return support.updateStrictCas(raw);
    }

    static Map<String, Object> toRaw(LineageHistoricalCompensation compensation) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", LineageHistoricalCompensation.DOCUMENT_TYPE);
        raw.put("taskId", compensation.taskId());
        raw.put("target", compensation.target());
        raw.put("repositoryId", compensation.repositoryId());
        raw.put("endpointKind", compensation.endpointKind().name());
        raw.put("subjectDigest", compensation.subjectDigest());
        raw.put("operationDigest", compensation.operationDigest());
        raw.put("publishedEvidenceDigest", compensation.publishedEvidenceDigest());
        raw.put("observedEvidenceDigest", compensation.observedEvidenceDigest());
        raw.put("reason", compensation.reason().name());
        raw.put("createdAtMs", compensation.createdAtMs());
        raw.put("state", compensation.state().name());
        return raw;
    }

    static LineageHistoricalCompensation fromRaw(Map<String, Object> raw) {
        if (!LineageHistoricalCompensation.DOCUMENT_TYPE.equals(raw.get("type"))) {
            throw new IllegalStateException("document is not a historical compensation");
        }
        return new LineageHistoricalCompensation(
                asString(raw.get("_rev")), requireString(raw, "taskId"),
                requireString(raw, "target"), requireString(raw, "repositoryId"),
                EndpointKind.valueOf(requireString(raw, "endpointKind")),
                requireString(raw, "subjectDigest"), requireString(raw, "operationDigest"),
                asString(raw.get("publishedEvidenceDigest")),
                asString(raw.get("observedEvidenceDigest")),
                LineageHistoricalCompensation.Reason.valueOf(requireString(raw, "reason")),
                LineageStoreDecoding.exactLong(raw.getOrDefault("createdAtMs", 0L), "createdAtMs"),
                LineageHistoricalCompensation.State.valueOf(requireString(raw, "state")));
    }

    private static String requireString(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("a compensation has no usable '" + field + "'");
        }
        return s;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
