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
package jp.aegif.nemaki.cmis.service.impl;

/**
 * A re-drive achieved nothing because every node it touched was behind the ACL-epoch PENDING
 * GATE — an ancestor is mid-mutation.
 *
 * <p>Thrown so the reconciliation scheduler can retain the task under capped backoff WITHOUT
 * consuming one of its ten attempts, the same treatment a quarantined dependency already gets.
 * Spending attempts on a gate that clears by itself is how a subtree under sustained ACL churn
 * used to reach terminal FAILED and keep stale {@code readers} until someone noticed.
 *
 * @see RefreshCounters
 */
public class SearchIndexRefreshPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String objectId;
    private final int blockedNodes;

    public SearchIndexRefreshPendingException(String objectId, int blockedNodes) {
        super("Search-index ACL refresh for " + objectId + " deferred: " + blockedNodes
                + " node(s) blocked by the ACL-epoch pending gate (an ancestor is mid-mutation)");
        this.objectId = objectId;
        this.blockedNodes = blockedNodes;
    }

    public String getObjectId() {
        return objectId;
    }

    public int getBlockedNodes() {
        return blockedNodes;
    }
}
