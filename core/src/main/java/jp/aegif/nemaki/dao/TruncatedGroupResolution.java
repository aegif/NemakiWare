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
package jp.aegif.nemaki.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A group-membership resolution that hit the traversal limit and is therefore INCOMPLETE.
 *
 * <h2>Why a marker type rather than a flag</h2>
 *
 * <p>{@code getJoinedGroupByUserId} returns {@code List<String>} through the DAO interface and
 * two implementations. When the upward walk gives up at the iteration limit it still returns the
 * groups it managed to reach, and that list is indistinguishable from a complete answer — so the
 * caching layer memoises a partial membership, and the authorization gate treats it as the whole
 * truth. Measured: a user at the bottom of a 55-level chain silently loses access to documents
 * ACL'd to the top of that chain — search returns 0 hits and getObject returns 403, with one
 * {@code log.warn} on the server as the only trace.
 *
 * <p>Subclassing the returned list carries the signal without changing the interface for the
 * many callers that legitimately do not care. Callers that DO care use {@code instanceof}.
 *
 * <h2>Why truncated answers are not cached</h2>
 *
 * <p>A truncated answer is wrong, and caching it makes it durably wrong for the entry's lifetime.
 * Recomputing costs this user roughly a second per request (the walk is one CouchDB view query
 * per group in the frontier), which is a real cost — but it applies only to an account sitting
 * below a group chain deeper than the limit, which is an administrative misconfiguration rather
 * than a reachable state for an ordinary user (only administrators can create groups). Paying it
 * keeps the answer self-healing: the moment the chain is shortened the correct membership
 * appears, with no cache to wait out.
 */
public final class TruncatedGroupResolution extends ArrayList<String> {

    private static final long serialVersionUID = 1L;

    /** Times a resolution was truncated since this JVM started. Surfaced by the admin metrics. */
    private static final AtomicLong TRUNCATIONS = new AtomicLong();

    private final int limit;

    public TruncatedGroupResolution(Collection<String> reached, int limit) {
        super(reached);
        this.limit = limit;
        TRUNCATIONS.incrementAndGet();
    }

    /** The iteration limit that stopped the walk. */
    public int getLimit() {
        return limit;
    }

    public static long truncationCount() {
        return TRUNCATIONS.get();
    }

    /** True when {@code groups} is known to be an incomplete membership. */
    public static boolean isTruncated(Object groups) {
        return groups instanceof TruncatedGroupResolution;
    }
}
