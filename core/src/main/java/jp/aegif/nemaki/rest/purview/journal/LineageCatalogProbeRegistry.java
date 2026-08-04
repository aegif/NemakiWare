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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes a presence question to the one catalog that can answer it.
 *
 * <p>One probe per target, and no fallback. A registry that answered an unknown target by
 * picking some other probe would be the reuse this separation exists to stop: the obligation's
 * task key would name one catalog and its verdict would come from another.
 *
 * <p>An unknown target is {@link Presence#UNKNOWN} — fail-closed. Not an exception, because the
 * caller's response to "cannot ask" is already correct (owe an obligation, retry later), and not
 * {@code ABSENT}, because nothing was established.
 */
public class LineageCatalogProbeRegistry implements LineageCatalogEntityProbe {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageCatalogProbeRegistry.class);

    private final Map<String, LineageCatalogEntityProbe> byTarget;

    public LineageCatalogProbeRegistry(Map<String, LineageCatalogEntityProbe> byTarget) {
        this.byTarget = byTarget == null ? Map.of() : new LinkedHashMap<>(byTarget);
    }

    /** Which targets this node can actually ask. Used by the readiness wiring check. */
    public Set<String> knownTargets() {
        return Set.copyOf(byTarget.keySet());
    }

    /** Whether a probe is wired for this target — a structural check, with no catalog call. */
    public boolean canProbe(String target) {
        return target != null && byTarget.get(target) != null;
    }

    @Override
    public Presence presenceOf(String target, String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        LineageCatalogEntityProbe probe = target == null ? null : byTarget.get(target);
        if (probe == null) {
            // Deliberately not "ask someone else": a verdict from another catalog would be
            // applied to a task key that names this one.
            logger.warn("No catalog probe is wired for target '{}' — answering UNKNOWN", target);
            return Presence.UNKNOWN;
        }
        try {
            Presence presence =
                    probe.presenceOf(target, repositoryId, kind, catalogQualifiedName);
            return presence == null ? Presence.UNKNOWN : presence;
        } catch (RuntimeException e) {
            // The class name only. A catalog response body can echo the qualified name, and an
            // external asset's qualified name contains its stable key.
            logger.warn("Catalog probe for target '{}' failed: {}",
                    target, e.getClass().getSimpleName());
            return Presence.UNKNOWN;
        }
    }
}
