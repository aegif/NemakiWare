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

import java.util.Optional;

/**
 * The budget the publisher, read-back and source resolver actually run under.
 *
 * <h2>Read every time, never captured at startup</h2>
 *
 * <p>Timeouts are administrator-managed configuration. A value read once when the context was
 * built would let readiness keep reporting green after someone raised a timeout past the fence
 * lease — the deployment would be unsafe and the gate would say otherwise until a restart.
 * A fresh {@code evaluate()} has to see the current configuration.
 *
 * <h2>Per target and kind, resolved exactly</h2>
 *
 * <p>No fallback. A target with no budget is one whose safety cannot be shown, which is red —
 * not one that inherits another target's numbers.
 */
public interface LineageOperationBudgetProvider {

    /**
     * @return empty when the budget cannot be established for this combination; never a guess
     */
    Optional<LineageOperationBudget> budgetFor(String target, EndpointKind kind);
}
