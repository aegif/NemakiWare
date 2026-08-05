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

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for compensation requests.
 *
 * <p>Deliberately a store rather than a queue: the request has to survive the restart that a
 * restore-heavy incident is likely to involve, and it has to be visible on an admin route
 * afterwards. Create-if-absent by deterministic id, so a retried historical publish cannot
 * queue two compensations for one write.
 */
public interface LineageHistoricalCompensationStore {

    /** @return what is stored, whether this call created it or found it */
    LineageHistoricalCompensation createIfAbsent(LineageHistoricalCompensation compensation);

    Optional<LineageHistoricalCompensation> read(String taskId);

    /** Bounded. For the processor and for admin status. */
    List<LineageHistoricalCompensation> findByState(LineageHistoricalCompensation.State state,
            int limit);

    /** Marks one done, by CAS. False on a lost race. */
    boolean markResolved(LineageHistoricalCompensation compensation, String reason);

    /** Marks one failed, by CAS, so it stays visible rather than being retried silently. */
    boolean markFailed(LineageHistoricalCompensation compensation, String reason);

    /**
     * Exact counts per state, from the view's own reduce.
     *
     * <p>A compensation left PENDING means a catalog still holds an entity that disagrees with
     * the repository, so a preflight must be able to see how many there are.
     */
    java.util.Map<LineageHistoricalCompensation.State,
            LineageCatalogObligationStore.StateCount> countByState();
}
