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
package jp.aegif.nemaki.acl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import jp.aegif.nemaki.util.cache.PrincipalGeneration;

/**
 * A short-lived memo of "does this principal id name a user, or a group?".
 *
 * <h2>Why this is the lever</h2>
 *
 * <p>Projecting an ACL into search {@code readers} tokens asks that question once per ACE, and the
 * answer comes from a CouchDB view query that no cache layer serves — the by-id principal reads
 * deliberately bypass the caches. It is paid twice over: once on the synchronous applyACL path
 * (measured at about 8ms per ACE, so a folder with 22 ACEs spends 180ms of its 360ms response
 * there) and again for every descendant during the asynchronous refresh (a large part of the
 * ~100ms per node on an ACE-heavy tree). The same handful of principals is looked up over and over
 * within a single traversal.
 *
 * <h2>Why the by-id reads were left uncached, and why this does not undo that</h2>
 *
 * <p>They bypass the caches because the projection must not be computed from stale principal data:
 * a lookup that cannot be served is {@code UNAVAILABLE} and must never be mistaken for "absent",
 * or the reader set silently shrinks. Two properties keep that intact here:
 *
 * <ul>
 * <li><b>{@code UNAVAILABLE} is never stored.</b> Only a definite FOUND / NOT_FOUND is memoised,
 *     so an outage can never be cached and replayed as an answer.</li>
 * <li><b>Entries die when principals change.</b> The memo is keyed on the repository's principal
 *     generation, which every user and group create, update and delete advances — including
 *     deletes, which are the revocations that matter. A change does not wait out a TTL; it makes
 *     every entry from the previous generation unreachable at once.</li>
 * </ul>
 *
 * <p>A short TTL bounds it as well, for the case a principal write happens on another replica: the
 * generation is JVM-local, so this replica learns of that write through the cross-replica poller,
 * and the TTL is the backstop until it does. Both are far tighter than the hour that the general
 * caches allow.
 *
 * <p>What is memoised is only the KIND of a principal (user / group / neither), not its content —
 * membership and permissions are resolved elsewhere and are not affected by this.
 */
public final class PrincipalLookupCache {

    /**
     * Short enough that a principal created on another replica appears quickly even before the
     * cross-replica poller notices, long enough to collapse the repeated lookups inside one
     * subtree traversal, which is the whole point.
     */
    static final long TTL_MS = 10_000;

    /**
     * Hard cap. Entries expire logically (TTL + generation) but nothing removes them, so without
     * a bound a repository that sees many distinct principal ids — or many generations — would
     * accumulate keys for the life of the JVM. Over the cap the map is emptied rather than
     * carefully evicted: losing entries costs one view query each, so the failure mode is slow,
     * never wrong.
     */
    static final int MAX_ENTRIES = 20_000;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private static final class Entry {
        final PrincipalLookup outcome;
        final long generation;
        final long expiresAtMs;

        Entry(PrincipalLookup outcome, long generation, long expiresAtMs) {
            this.outcome = outcome;
            this.generation = generation;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private PrincipalLookupCache() {
    }

    private static String key(String repositoryId, String kind, String principalId) {
        repositoryId = repositoryId == null ? "" : repositoryId;
        principalId = principalId == null ? "" : principalId;
        return repositoryId + "" + kind + "" + principalId;
    }

    /**
     * Returns the memoised outcome, or computes and (when definite) stores it.
     *
     * @param loader the real lookup; may return {@code UNAVAILABLE} or null, neither of which is
     *     stored
     */
    public static PrincipalLookup get(String repositoryId, String kind, String principalId,
            Supplier<PrincipalLookup> loader) {
        long generation = PrincipalGeneration.current(repositoryId);
        String k = key(repositoryId, kind, principalId);
        Entry cached = ENTRIES.get(k);
        long now = System.currentTimeMillis();
        if (cached != null && cached.generation == generation && cached.expiresAtMs > now) {
            return cached.outcome;
        }
        PrincipalLookup outcome = loader.get();
        if (outcome == PrincipalLookup.FOUND || outcome == PrincipalLookup.NOT_FOUND) {
            if (ENTRIES.size() >= MAX_ENTRIES) {
                ENTRIES.clear();
            }
            ENTRIES.put(k, new Entry(outcome, generation, now + TTL_MS));
        } else {
            // An outage must not become an answer. Drop any previous entry too: it was computed
            // before whatever is wrong started, and re-deriving is cheaper than being wrong.
            ENTRIES.remove(k);
        }
        return outcome;
    }

    /** Entries currently held — diagnostics only. */
    public static int size() {
        return ENTRIES.size();
    }

    /**
     * Drops every memoised outcome.
     *
     * <p>Called when a principal changed somewhere this JVM's generation counter cannot see —
     * i.e. on another replica. The generation is process-local, so a remote change would
     * otherwise be invisible to this memo until its TTL.
     */
    public static void invalidateAll() {
        ENTRIES.clear();
    }

    static void resetForTests() {
        ENTRIES.clear();
    }
}
