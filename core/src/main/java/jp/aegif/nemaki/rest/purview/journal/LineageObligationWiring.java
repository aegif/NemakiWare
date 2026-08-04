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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether the obligation machine is actually assembled on this node — structurally.
 *
 * <h2>The false-green this closes</h2>
 *
 * <p>{@code catalog:obligations} is a <em>static</em> capability: it says the code is in the
 * binary. Nothing said the code was wired. A node could therefore satisfy the barrier's
 * condition 8 and a green D-rest readiness while having no store, no probe for a configured
 * target, and no publisher — and would discover that the moment v2 writes opened, which is
 * precisely what 4b being a flag flip forbids.
 *
 * <p>So the capability keeps its meaning ("the code exists") and readiness gains this: the
 * parts exist, and there is one for every target this node is configured to publish to.
 *
 * <h2>Why it does not ask the service</h2>
 *
 * <p>{@code LineageCatalogObligationService.active()} reads readiness. If readiness asked the
 * service back, the two would recurse. This check therefore reads no gate at all — it only
 * looks at whether references are present and whether the registries cover the configured
 * targets. That also makes it meaningful while D-rest is off, which is when an operator most
 * wants to know whether the flip would land on a wired node.
 */
public final class LineageObligationWiring {

    private final LineageCatalogObligationStore store;
    private final LineageCatalogProbeRegistry probes;
    private final Map<String, LineageHistoricalEntityPublisher> historicalPublishers;
    private final LineageCatalogObligationService service;
    private final Object scanner;
    private final Object projectorCollaborator;

    public LineageObligationWiring(LineageCatalogObligationStore store,
            LineageCatalogProbeRegistry probes,
            Map<String, LineageHistoricalEntityPublisher> historicalPublishers,
            LineageCatalogObligationService service,
            Object scanner,
            Object projectorCollaborator) {
        this.store = store;
        this.probes = probes;
        this.historicalPublishers =
                historicalPublishers == null ? Map.of() : Map.copyOf(historicalPublishers);
        this.service = service;
        this.scanner = scanner;
        this.projectorCollaborator = projectorCollaborator;
    }

    /**
     * What is missing, named. Empty means assembled.
     *
     * @param configuredTargets the targets this node publishes lineage to
     */
    public List<String> violations(Set<String> configuredTargets) {
        List<String> violations = new ArrayList<>();
        if (store == null) {
            violations.add("no catalog obligation store is wired");
        }
        if (service == null) {
            violations.add("no catalog obligation service is wired");
        }
        if (scanner == null) {
            violations.add("no obligation scanner/reclaimer is wired");
        }
        if (projectorCollaborator == null) {
            violations.add("the projector is not wired to the obligation service");
        }
        if (probes == null) {
            violations.add("no catalog probe registry is wired");
        }
        Set<String> targets = configuredTargets == null ? Set.of() : configuredTargets;
        if (targets.isEmpty()) {
            // Not a violation: a node with no lineage targets has nothing to owe. Said
            // explicitly so the empty case is a decision rather than a gap in the loop.
            return violations;
        }
        for (String target : targets) {
            if (probes == null || !probes.canProbe(target)) {
                violations.add("no catalog probe is wired for target '" + target + "'");
            }
            if (historicalPublishers.get(target) == null) {
                // Without this, a purged source's obligation can never leave PENDING — the
                // consumer would retry a source that will never come back.
                violations.add("no historical entity publisher is wired for target '"
                        + target + "'");
            }
        }
        return violations;
    }

    /** The service the scanner and the projector must both be using — identity, not equality. */
    public boolean sharesService(LineageCatalogObligationService other) {
        return service != null && service == other;
    }

    /** The store the service must be using. */
    public LineageCatalogObligationStore store() {
        return store;
    }
}
