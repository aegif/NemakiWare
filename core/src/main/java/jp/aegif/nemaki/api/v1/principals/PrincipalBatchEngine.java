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
package jp.aegif.nemaki.api.v1.principals;

import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Kind;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Operation;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Row;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.RowVerdict;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.util.PasswordPolicyService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The public face of the package for the resource: parse, plan, apply. Everything else in this
 * package is package-private so the ONLY caller of the canonical write methods is
 * {@link PrincipalBatchApplier}, reached through here.
 */
public final class PrincipalBatchEngine {

    /** Re-exported so the resource can name them without reaching into package-private classes. */
    public record Guards(String adminUserId, String solrUserId, String mcpServiceUserId, String actorUserId,
                         Set<String> groupPrefixes, String userPrefix) { }

    public record Planned(List<RowVerdict> verdicts, String snapshotHash, boolean passwordPresent,
                          int adminGrants) {
        public long count(PrincipalBatch.Verdict verdict) {
            return verdicts.stream().filter(v -> v.verdict() == verdict).count();
        }
    }

    public record RowOutcome(int line, String id, PrincipalBatch.Outcome outcome, String reason) {
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("line", line);
            out.put("id", id);
            out.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
            if (reason != null) {
                out.put("reason", reason);
            }
            return out;
        }
    }

    public record Applied(List<RowOutcome> outcomes, Integer stoppedAtLine, String incidentId) {
        public long count(PrincipalBatch.Outcome outcome) {
            return outcomes.stream().filter(o -> o.outcome() == outcome).count();
        }

        public boolean stopped() {
            return stoppedAtLine != null;
        }
    }

    private final ContentService contentService;
    private final PasswordPolicyService passwordPolicy;
    private final String repositoryId;
    private final Guards guards;

    public PrincipalBatchEngine(ContentService contentService, PasswordPolicyService passwordPolicy,
            String repositoryId, Guards guards) {
        this.contentService = contentService;
        this.passwordPolicy = passwordPolicy;
        this.repositoryId = repositoryId;
        this.guards = guards;
    }

    /** Decides without writing. */
    public Planned plan(Kind kind, Operation operation, List<Row> rows) {
        PrincipalBatchPlanner planner = new PrincipalBatchPlanner(new PrincipalWorld(contentService, repositoryId),
                passwordPolicy, new PrincipalBatchPlanner.Guards(guards.adminUserId(), guards.solrUserId(),
                        guards.mcpServiceUserId(), guards.actorUserId(), guards.groupPrefixes(), guards.userPrefix()),
                repositoryId);
        PrincipalBatchPlanner.Planned planned = planner.plan(kind, operation, rows);
        return new Planned(planned.verdicts(), planned.snapshotHash(), planned.passwordPresent(), planned.adminGrants());
    }

    /** Writes the expected rows through the canonical methods; skips the rest. */
    public Applied apply(Kind kind, Operation operation, List<Row> rows, List<RowVerdict> verdicts, String actor) {
        PrincipalBatchApplier.Applied applied = new PrincipalBatchApplier(contentService, repositoryId, actor)
                .apply(kind, operation, rows, verdicts);
        List<RowOutcome> outcomes = new ArrayList<>();
        for (PrincipalBatchApplier.RowOutcome o : applied.outcomes()) {
            outcomes.add(new RowOutcome(o.line(), o.id(), o.outcome(), o.reason()));
        }
        return new Applied(List.copyOf(outcomes), applied.stoppedAtLine(), applied.incidentId());
    }

    public static List<Row> parseCsv(byte[] bytes, Kind kind, Operation operation) {
        return PrincipalCsv.parse(bytes, kind, operation).rows();
    }

    /** JSON rows: the same columns as the CSV, an unknown key is a 400, lists may be arrays. */
    public static List<Row> rowsFromJson(List<?> list, Kind kind, Operation operation) {
        Set<String> allowed = PrincipalBatch.columnsFor(kind, operation);
        String idColumn = PrincipalBatch.idColumnFor(kind);
        List<Row> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int line = 1;
        for (Object item : list) {
            line++;
            if (!(item instanceof Map<?, ?> map)) {
                throw new PrincipalBatchRequestException(400, "row " + (line - 1) + " is not an object");
            }
            Map<String, String> cells = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!allowed.contains(key)) {
                    throw new PrincipalBatchRequestException(400, "row " + (line - 1) + ": unknown column '"
                            + key + "' for " + kind.name().toLowerCase(java.util.Locale.ROOT) + " / "
                            + operation.name().toLowerCase(java.util.Locale.ROOT) + "; the columns are "
                            + new java.util.TreeSet<>(allowed));
                }
                Object v = e.getValue();
                if (v == null) {
                    continue; // not stated
                }
                if (v instanceof List<?> parts) {
                    // A JSON array is the semicolon list of the CSV; an EMPTY array is stated-empty,
                    // which only memberships/replace reads as "make it empty" — kept as "" here so
                    // the planner sees exactly what a CSV would have carried.
                    List<String> strings = parts.stream().map(String::valueOf).toList();
                    cells.put(key, strings.isEmpty() ? "" : String.join(";", strings));
                } else {
                    cells.put(key, String.valueOf(v));
                }
            }
            String id = cells.get(idColumn);
            if (id == null || id.isBlank()) {
                throw new PrincipalBatchRequestException(400, "row " + (line - 1) + ": '" + idColumn + "' is blank");
            }
            String uniqueness = kind == Kind.MEMBERSHIPS && operation != Operation.REPLACE
                    ? id.trim() + "\u0000" + String.valueOf(cells.get("memberId")).trim() : id.trim();
            if (!ids.add(uniqueness)) {
                throw new PrincipalBatchRequestException(400, "row " + (line - 1) + ": '" + id.trim()
                        + "' appears more than once");
            }
            rows.add(new Row(line, id.trim(), cells));
        }
        return rows;
    }

    public static String sha256(byte[] bytes) {
        return PrincipalBatchPlanner.sha256(bytes);
    }
}
