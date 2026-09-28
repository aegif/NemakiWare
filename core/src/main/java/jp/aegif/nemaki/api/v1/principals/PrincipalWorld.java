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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reads the batch makes, with the store's "could not answer" kept apart from "not there".
 *
 * <p>{@code getUserItemById} / {@code getGroupItemById} return null for an absent principal and
 * throw when the store could not be read. A planner that caught the throw and went on would
 * turn an unreadable store into a file full of {@code NOT_FOUND} rows — and then delete or
 * recreate on that basis. Here every read failure becomes a 503 for the WHOLE plan
 * ({@code STORE_UNAVAILABLE}), before any verdict is written down.
 */
final class PrincipalWorld {

    static final String STORE_UNAVAILABLE = "STORE_UNAVAILABLE";

    private final ContentService contentService;
    private final String repositoryId;

    PrincipalWorld(ContentService contentService, String repositoryId) {
        this.contentService = contentService;
        this.repositoryId = repositoryId;
    }

    /** The user, or null when there is none — never null for "could not read". */
    UserItem user(String userId) {
        try {
            return contentService.getUserItemById(repositoryId, userId);
        } catch (RuntimeException e) {
            throw couldNotRead("user " + userId, e);
        }
    }

    /** The group as stored NOW (the cache is bypassed), or null when there is none. */
    GroupItem group(String groupId) {
        try {
            return contentService.getGroupItemByIdFresh(repositoryId, groupId);
        } catch (RuntimeException e) {
            throw couldNotRead("group " + groupId, e);
        }
    }

    boolean userExists(String userId) {
        return user(userId) != null;
    }

    boolean groupExists(String groupId) {
        return group(groupId) != null;
    }

    /**
     * Whether making {@code childGroups} the nested groups of {@code groupId} would close a cycle:
     * the group reaching itself through the nested-group edges, bounded so a corrupt graph cannot
     * spin this forever.
     */
    boolean wouldCycle(String groupId, List<String> childGroups) {
        Deque<String> todo = new ArrayDeque<>(childGroups);
        Set<String> seen = new HashSet<>();
        int budget = 10_000;
        while (!todo.isEmpty() && budget-- > 0) {
            String next = todo.pop();
            if (next == null || !seen.add(next)) {
                continue;
            }
            if (next.equals(groupId)) {
                return true;
            }
            GroupItem g = group(next);
            if (g != null) {
                todo.addAll(g.getGroups());
            }
        }
        if (budget <= 0) {
            throw new PrincipalBatchRequestException(503, STORE_UNAVAILABLE,
                    "the nested-group graph around " + groupId + " could not be walked to its end, "
                            + "so a cycle can be neither confirmed nor ruled out");
        }
        return false;
    }

    private static PrincipalBatchRequestException couldNotRead(String what, RuntimeException cause) {
        // The cause's text stays out of the message: it reaches the client, and a store
        // exception's text is whatever the failing layer wrote. The resource logs it.
        PrincipalBatchRequestException refusal = new PrincipalBatchRequestException(503,
                STORE_UNAVAILABLE, "the store could not answer whether " + what
                        + " exists, so nothing about this file was decided. This is NOT a "
                        + "statement that it is absent");
        refusal.initCause(cause);
        return refusal;
    }
}
