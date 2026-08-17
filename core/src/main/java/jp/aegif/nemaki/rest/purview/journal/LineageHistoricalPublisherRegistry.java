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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One historical-entity publisher per target, resolved exactly, with no fallback.
 *
 * <h2>Why collisions are refused at construction</h2>
 *
 * <p>Two beans claiming the same target is not a preference to resolve — it is a deployment
 * where nobody can say which catalog a purged source's historical entity was written to. The
 * registry refuses to be built rather than picking one, because picking one is a decision that
 * would be made once, silently, at startup, and never surface again.
 *
 * <p>Target names are compared case-insensitively for collision detection: {@code Atlas} and
 * {@code atlas} in one deployment is the same ambiguity wearing different capitalisation.
 * Lookup stays exact, so a lookup never quietly matches a differently-cased registration.
 */
public final class LineageHistoricalPublisherRegistry {

    private final Map<String, LineageHistoricalEntityPublisher> byTarget;

    /**
     * @throws IllegalStateException if two publishers claim the same target, or a target name
     *         is blank
     */
    public LineageHistoricalPublisherRegistry(
            Map<String, LineageHistoricalEntityPublisher> byTarget) {
        Map<String, Object> seenLowercase = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, LineageHistoricalEntityPublisher> copy = new LinkedHashMap<>();
        if (byTarget != null) {
            for (Map.Entry<String, LineageHistoricalEntityPublisher> entry : byTarget.entrySet()) {
                String target = entry.getKey();
                if (target == null || target.isBlank()) {
                    problems.add("a historical publisher is registered under a blank target");
                    continue;
                }
                if (entry.getValue() == null) {
                    problems.add("target '" + target + "' is registered with no publisher");
                    continue;
                }
                String folded = target.toLowerCase(Locale.ROOT);
                if (seenLowercase.put(folded, entry.getValue()) != null) {
                    problems.add("two historical publishers claim target '" + target + "'");
                }
                copy.put(target, entry.getValue());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "the historical publisher registry is ambiguous: " + problems);
        }
        this.byTarget = Map.copyOf(copy);
    }

    /** The targets this node can write a historical entity to. Structural; no IO. */
    public Set<String> knownTargets() {
        return byTarget.keySet();
    }

    /** Whether a publisher is registered for exactly this target name. */
    public boolean canPublish(String target) {
        return target != null && byTarget.get(target) != null;
    }

    /**
     * The publisher for this target, or {@code null}.
     *
     * <p>Never another target's. A historical entity written to the wrong catalog would satisfy
     * an obligation whose task key names a different one.
     */
    public LineageHistoricalEntityPublisher publisherFor(String target) {
        return target == null ? null : byTarget.get(target);
    }
}
