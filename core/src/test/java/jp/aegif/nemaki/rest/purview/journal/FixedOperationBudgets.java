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
import java.util.function.LongSupplier;

/**
 * A budget provider whose numbers a test controls.
 *
 * <p>Backed by a supplier rather than a value, so a test can change the configuration after
 * construction and watch a fresh readiness evaluation change with it — the property that a
 * startup-captured timeout would silently lose.
 */
final class FixedOperationBudgets implements LineageOperationBudgetProvider {

    /** Comfortably inside the fence lease for every target and kind. */
    static LineageOperationBudgetProvider healthy() {
        return new FixedOperationBudgets(() -> 1_000L, null);
    }

    /** Resolves nothing, as a target with no configuration of its own does. */
    static LineageOperationBudgetProvider unresolvable() {
        return (target, kind) -> Optional.empty();
    }

    private final LongSupplier readTimeoutMs;
    private final String onlyTarget;

    FixedOperationBudgets(LongSupplier readTimeoutMs, String onlyTarget) {
        this.readTimeoutMs = readTimeoutMs;
        this.onlyTarget = onlyTarget;
    }

    @Override
    public Optional<LineageOperationBudget> budgetFor(String target, EndpointKind kind) {
        if (onlyTarget != null && !onlyTarget.equals(target)) {
            return Optional.empty();
        }
        long read = readTimeoutMs.getAsLong();
        if (read <= 0) {
            return Optional.empty();
        }
        return Optional.of(new LineageOperationBudget(target, kind, 500L, read, 0, 0L, 0L, 500L));
    }
}
