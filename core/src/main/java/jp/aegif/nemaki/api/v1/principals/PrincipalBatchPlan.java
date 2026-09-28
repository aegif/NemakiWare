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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A preview that execute can be asked to carry out — what the planner decided, for which rows,
 * against which state of the store.
 *
 * <p>The rows are kept WITHOUT their secret cells (design §6.1): a plan never holds a password,
 * in clear or hashed. A plan whose rows had one records only {@code passwordPresent} and the
 * digest of the file it was read from, and execute demands the same file again.
 */
public record PrincipalBatchPlan(String planId, String repositoryId, PrincipalBatch.Kind kind,
                                 PrincipalBatch.Operation operation, List<PrincipalBatch.Row> rows,
                                 List<PrincipalBatch.RowVerdict> verdicts, String snapshotHash,
                                 boolean passwordPresent, String fileDigest, Instant expiresAt,
                                 String nodeId) {

    public PrincipalBatchPlan {
        for (PrincipalBatch.Row row : rows) {
            for (String secret : PrincipalBatch.SECRET_COLUMNS) {
                if (row.cells().containsKey(secret)) {
                    throw new IllegalArgumentException("a plan must not hold the column '" + secret + "'");
                }
            }
        }
        if (passwordPresent && (fileDigest == null || fileDigest.isBlank())) {
            throw new IllegalArgumentException("a plan with a password must record the file's digest");
        }
    }

    /** The rows with their secret cells removed — the only shape a plan stores. */
    public static List<PrincipalBatch.Row> withoutSecrets(List<PrincipalBatch.Row> rows) {
        return rows.stream().map(row -> {
            Map<String, String> cells = new LinkedHashMap<>(row.cells());
            PrincipalBatch.SECRET_COLUMNS.forEach(cells::remove);
            return new PrincipalBatch.Row(row.line(), row.id(), cells);
        }).toList();
    }

    /** Whether any row STATES a secret cell (a blank one is "not stated"). */
    public static boolean anySecretStated(List<PrincipalBatch.Row> rows) {
        return rows.stream().anyMatch(row -> PrincipalBatch.SECRET_COLUMNS.stream()
                .anyMatch(secret -> row.cell(secret) != null));
    }

    /**
     * Where plans live between preview and execute: this node's memory, for a short while.
     *
     * <p>Node-local on purpose (design §6.1, known limitation 3): a replica that did not make the
     * plan answers {@code PLAN_UNKNOWN}, which is a refusal, not a wrong execution. Entries are
     * purged on every access so a forgotten plan does not outlive its TTL by being unread.
     */
    public static final class Store {
        public static final Duration TTL = Duration.ofMinutes(10);

        private final ConcurrentHashMap<String, PrincipalBatchPlan> plans = new ConcurrentHashMap<>();
        private final String nodeId = UUID.randomUUID().toString();

        public String nodeId() {
            return nodeId;
        }

        public PrincipalBatchPlan put(String repositoryId, PrincipalBatch.Kind kind,
                PrincipalBatch.Operation operation, List<PrincipalBatch.Row> rows,
                List<PrincipalBatch.RowVerdict> verdicts, String snapshotHash,
                boolean passwordPresent, String fileDigest, Instant now) {
            purge(now);
            PrincipalBatchPlan plan = new PrincipalBatchPlan(UUID.randomUUID().toString(), repositoryId,
                    kind, operation, withoutSecrets(rows), verdicts, snapshotHash, passwordPresent,
                    fileDigest, now.plus(TTL), nodeId);
            plans.put(plan.planId(), plan);
            return plan;
        }

        /** The plan without consuming it, or empty when unknown here or expired. A request that
         *  is refused for its own shape (no file, wrong file) leaves the plan in place. */
        public Optional<PrincipalBatchPlan> peek(String planId, Instant now) {
            purge(now);
            if (planId == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(plans.get(planId));
        }

        /**
         * Consumed: a plan is applied once, and a plan whose world moved on is not kept. Returns
         * whether THIS call took it — two confirmations racing for one plan both pass {@link #peek}
         * and the snapshot check, and exactly one of them gets {@code true} here.
         */
        public boolean remove(String planId) {
            return planId != null && plans.remove(planId) != null;
        }

        public boolean knew(String planId) {
            return planId != null && plans.containsKey(planId);
        }

        /** Whether this node purged the plan as expired (so the refusal can say EXPIRED, not UNKNOWN). */
        public boolean expiredRecently(String planId) {
            return planId != null && expired.containsKey(planId);
        }

        private void purge(Instant now) {
            plans.entrySet().removeIf(e -> {
                if (!e.getValue().expiresAt().isAfter(now)) {
                    expired.put(e.getKey(), e.getValue().expiresAt());
                    return true;
                }
                return false;
            });
            // Bounded memory of the expired: a plan is remembered as expired for as long as it took
            // to expire, then forgotten (it becomes PLAN_UNKNOWN, which is still a refusal).
            expired.values().removeIf(at -> at.plus(TTL).isBefore(now));
        }

        private final ConcurrentHashMap<String, Instant> expired = new ConcurrentHashMap<>();
    }
}
