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
package jp.aegif.nemaki.util.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-repository counter that advances when a USER or GROUP changes.
 *
 * <h2>Why this cannot be the ACL epoch</h2>
 *
 * <p>It is tempting to reuse the durable ACL epoch as one general-purpose "something changed"
 * clock. It would be wrong: the epoch allocator has exactly one caller, ACL finalization. A
 * password change, an admin-flag change, an account being disabled, or a user being removed from
 * a group does not advance it at all. A cache keyed on the ACL epoch would therefore claim to
 * cover revocations it never observes — the most dangerous kind of wrong, because the mechanism
 * looks present.
 *
 * <p>So principal changes get their own counter. The two are read the same way and are equally
 * cheap; keeping them separate is what keeps each one's guarantee true.
 *
 * <h2>What advances it</h2>
 *
 * <p>Every create, update and delete of a user or a group, at the DAO layer — the single point all
 * of those paths funnel through, so an administrative route that bypasses the service layer still
 * counts. Membership changes advance it too, since they arrive as a group update.
 *
 * <h2>What it is for</h2>
 *
 * <p>Replicas poll this value and drop their principal caches when it moves. Without that, a
 * replica that did not perform the change keeps serving the old password hash, the old admin flag
 * and the old group membership until the entry expires — with a time-to-live bound now, but that
 * is a ceiling, not a propagation mechanism.
 */
public final class PrincipalGeneration {

    private static final Map<String, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private PrincipalGeneration() {
    }

    private static AtomicLong counter(String repositoryId) {
        return GENERATIONS.computeIfAbsent(repositoryId == null ? "" : repositoryId,
                k -> new AtomicLong());
    }

    public static long current(String repositoryId) {
        return counter(repositoryId).get();
    }

    /** Called after a user or group has been created, updated or deleted. */
    public static long advance(String repositoryId) {
        return counter(repositoryId).incrementAndGet();
    }

    /** Test hook: forget all counters. */
    public static void resetForTests() {
        GENERATIONS.clear();
    }
}
