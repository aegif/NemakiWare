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
 * One observed-entity materializer per target, resolved exactly, with no fallback.
 *
 * <h2>Why this is a registry and not one instance</h2>
 *
 * <p>It used to be a single materializer reached through the settler. That was readable only as
 * long as nobody asked which catalog it answered for: a deployment publishing to two targets had
 * one instance bound to one of them, and the other target's obligations would have been settled
 * against a catalog nobody wrote to. Readiness could not state the problem either, because there
 * was no per-target question to ask — which is exactly how a gap survives a green preflight.
 *
 * <p>Probes and historical publishers were already keyed this way. The three now agree, so a
 * target either has all of its adapters or readiness names the one it lacks.
 *
 * <h2>Why collisions are refused at construction</h2>
 *
 * <p>Two beans claiming the same target is not a preference to resolve — it is a deployment where
 * nobody can say which catalog an observed entity was materialised into. The registry refuses to
 * be built rather than picking one, because picking one is a decision that would be made once,
 * silently, at startup, and never surface again.
 *
 * <p>Target names are compared case-insensitively for collision detection: {@code Atlas} and
 * {@code atlas} in one deployment is the same ambiguity wearing different capitalisation. Lookup
 * stays exact, so a lookup never quietly matches a differently-cased registration.
 */
public final class LineageObservedEntityMaterializerRegistry {

    private final Map<String, LineageObservedEntityMaterializer> byTarget;

    /**
     * @throws IllegalStateException if two materializers claim the same target, a target name is
     *         blank, or a materializer answers for a different target than the one it is
     *         registered under
     */
    public LineageObservedEntityMaterializerRegistry(
            Map<String, LineageObservedEntityMaterializer> byTarget) {
        Map<String, Object> seenLowercase = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, LineageObservedEntityMaterializer> copy = new LinkedHashMap<>();
        if (byTarget != null) {
            for (Map.Entry<String, LineageObservedEntityMaterializer> entry
                    : byTarget.entrySet()) {
                String target = entry.getKey();
                if (target == null || target.isBlank()) {
                    problems.add("an observed materializer is registered under a blank target");
                    continue;
                }
                if (entry.getValue() == null) {
                    problems.add("target '" + target + "' is registered with no materializer");
                    continue;
                }
                String folded = target.toLowerCase(Locale.ROOT);
                if (seenLowercase.put(folded, entry.getValue()) != null) {
                    problems.add("two observed materializers claim target '" + target + "'");
                }
                copy.put(target, entry.getValue());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "the observed materializer registry is ambiguous: " + problems);
        }
        this.byTarget = Map.copyOf(copy);
    }

    /** The targets this node can materialise an observed entity into. Structural; no IO. */
    public Set<String> knownTargets() {
        return byTarget.keySet();
    }

    /** Whether a materializer is registered for exactly this target name. */
    public boolean canMaterialize(String target) {
        return target != null && byTarget.get(target) != null;
    }

    /**
     * The materializer for this target, or {@code null}.
     *
     * <p>Never another target's. An entity materialised into the wrong catalog would satisfy an
     * obligation whose task key names a different one, and the obligation would resolve with the
     * named catalog still empty.
     */
    public LineageObservedEntityMaterializer materializerFor(String target) {
        return target == null ? null : byTarget.get(target);
    }
}
