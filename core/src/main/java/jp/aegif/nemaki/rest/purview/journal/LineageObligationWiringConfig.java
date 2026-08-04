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
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles §2's obligation machine, once, in one place.
 *
 * <h2>Dependency direction</h2>
 *
 * <pre>
 *   readiness → wiring descriptor
 *   service   → readiness
 *   scanner / projector collaborator → service
 * </pre>
 *
 * <p>The descriptor holds references and identity accessors only, so nothing here closes a
 * cycle back through {@code service.active()}. Spring can therefore construct all of it in one
 * pass without a lazy proxy.
 *
 * <h2>Why the historical publisher registry is empty</h2>
 *
 * <p>Because the builder is not written yet. Registering a placeholder that answered
 * {@code PUBLISHED} would turn readiness green on a node that cannot actually rebuild a purged
 * source's entity — the exact false-green this whole slice exists to close, reintroduced one
 * layer down. Readiness stays red for every configured target until a real publisher is
 * registered, and that is the correct state to ship in.
 */
@Configuration
public class LineageObligationWiringConfig {

    /**
     * The obligation store, over the same database and strict-IO rules as the journal.
     *
     * <p>Built from the journal store's {@code LineageStoreSupport} rather than its own client:
     * one database, one provisioning path, one definition of what a 404 means.
     */
    @Bean
    public LineageCatalogObligationStore lineageCatalogObligationStore(
            ObjectProvider<LineageJournalStore> journalStore) {
        LineageJournalStore store = journalStore.getIfAvailable();
        if (!(store instanceof LineageStoreSupport support)) {
            // No usable store: the wiring check will say so, and readiness will be red. A
            // null bean is better than one that throws on first use inside a scheduled tick.
            return null;
        }
        return new CouchLineageCatalogObligationStore(support);
    }

    /**
     * One probe per target this node can actually reach a catalog for.
     *
     * <p>Derived from the sinks that support verification, each bound to a probe over the
     * catalog client. A target with no verifying sink has no way to be asked, and inventing a
     * probe for it would answer for a catalog nobody can reach — the wiring check then names it
     * as missing, which is true and is what an operator needs to see.
     *
     * <p>No fallback and no "first bean found": a probe answers for its bound target or says
     * UNKNOWN.
     */
    @Bean
    public LineageCatalogProbeRegistry lineageCatalogProbeRegistry(
            ObjectProvider<LineageTargetSink> sinks,
            ObjectProvider<jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver>
                    connectionResolver,
            ObjectProvider<jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient>
                    entityRegistryClient) {
        Map<String, LineageCatalogEntityProbe> byTarget = new LinkedHashMap<>();
        var resolver = connectionResolver.getIfAvailable();
        var client = entityRegistryClient.getIfAvailable();
        if (resolver == null || client == null) {
            return new LineageCatalogProbeRegistry(byTarget);
        }
        List<LineageTargetSink> wired = sinks.orderedStream().toList();
        for (LineageTargetSink sink : wired) {
            String target = sink == null ? null : sink.targetName();
            if (target == null || target.isBlank() || !sink.supportsVerification()) {
                continue;
            }
            byTarget.put(target, new LineageCatalogClientProbe(target, resolver, client));
        }
        return new LineageCatalogProbeRegistry(byTarget);
    }

    /**
     * Empty on purpose — see the class javadoc. Readiness stays red for configured targets.
     */
    @Bean
    public LineageHistoricalPublisherRegistry lineageHistoricalPublisherRegistry(
            ObjectProvider<LineageHistoricalEntityPublisher> publishers) {
        Map<String, LineageHistoricalEntityPublisher> byTarget = new LinkedHashMap<>();
        // Whatever real publishers exist get registered; today there are none, and the
        // registry refuses duplicates rather than choosing between them.
        for (LineageHistoricalEntityPublisher publisher : publishers.orderedStream().toList()) {
            if (publisher instanceof TargetedHistoricalPublisher targeted) {
                byTarget.put(targeted.targetName(), publisher);
            }
        }
        return new LineageHistoricalPublisherRegistry(byTarget);
    }

    /** A publisher that says which target it writes to, so the registry can key it. */
    public interface TargetedHistoricalPublisher extends LineageHistoricalEntityPublisher {
        String targetName();
    }

    @Bean
    public LineageCatalogObligationService lineageCatalogObligationService(
            ObjectProvider<LineageCatalogObligationStore> store,
            LineageCatalogProbeRegistry probes,
            LineageDrestReadiness readiness,
            LineageNodeIdentity identity) {
        return new LineageCatalogObligationService(store.getIfAvailable(), probes, readiness,
                identity, System::currentTimeMillis);
    }

    @Bean
    public LineageObligationScanner lineageObligationScanner(
            LineageCatalogObligationService service) {
        return new LineageObligationScannerImpl(service);
    }

    @Bean
    public LineageObligationProjectorCollaborator lineageObligationProjectorCollaborator(
            LineageCatalogObligationService service) {
        return new LineageObligationProjectorCollaboratorImpl(service);
    }

    /** Durable storage for the intent that precedes every historical write. */
    @Bean
    public LineageHistoricalPublishIntentStore lineageHistoricalPublishIntentStore(
            ObjectProvider<LineageJournalStore> journalStore) {
        LineageJournalStore store = journalStore.getIfAvailable();
        return store instanceof LineageStoreSupport support
                ? new CouchLineageHistoricalPublishIntentStore(support) : null;
    }

    /** Durable storage for compensation requests. Same database, same strict-IO rules. */
    @Bean
    public LineageHistoricalCompensationStore lineageHistoricalCompensationStore(
            ObjectProvider<LineageJournalStore> journalStore) {
        LineageJournalStore store = journalStore.getIfAvailable();
        return store instanceof LineageStoreSupport support
                ? new CouchLineageHistoricalCompensationStore(support) : null;
    }

    /**
     * One authoritative source resolver per endpoint kind.
     *
     * <p>Empty for now, exactly as the historical publisher registry is: a resolver that
     * guessed would license a tombstone. Readiness names every kind that has none.
     */
    @Bean
    public LineageSourceDispositionRegistry lineageSourceDispositionRegistry(
            ObjectProvider<KindBoundSourceResolver> resolvers) {
        Map<EndpointKind, LineageSourceDispositionResolver> byKind =
                new java.util.EnumMap<>(EndpointKind.class);
        for (KindBoundSourceResolver resolver : resolvers.orderedStream().toList()) {
            if (byKind.put(resolver.endpointKind(), resolver) != null) {
                throw new IllegalStateException(
                        "two authoritative source resolvers claim " + resolver.endpointKind());
            }
        }
        return new LineageSourceDispositionRegistry(byKind, System::currentTimeMillis);
    }

    /** A source resolver that says which kind it answers for, so the registry can key it. */
    public interface KindBoundSourceResolver extends LineageSourceDispositionResolver {
        EndpointKind endpointKind();
    }

    @Bean
    public LineageHistoricalPublishMachine lineageHistoricalPublishMachine(
            ObjectProvider<LineageHistoricalPublishIntentStore> intents,
            ObjectProvider<LineageHistoricalCompensationStore> compensations,
            LineageHistoricalPublisherRegistry publishers,
            LineageSourceDispositionRegistry sources,
            ObjectProvider<LineageCurrentEntityRepublisher> republisher,
            LineageNodeIdentity identity) {
        return new LineageHistoricalPublishMachine(intents.getIfAvailable(),
                compensations.getIfAvailable(), publishers, sources,
                republisher.getIfAvailable(), identity, System::currentTimeMillis);
    }

    /**
     * The descriptor readiness reads.
     *
     * <p>Takes the same instances the rest of the context got, so the identity comparisons in
     * {@link LineageObligationWiring} are comparing what production actually uses.
     */
    @Bean
    public LineageObligationWiring lineageObligationWiring(
            ObjectProvider<LineageCatalogObligationStore> store,
            LineageCatalogProbeRegistry probes,
            LineageHistoricalPublisherRegistry historicalPublishers,
            LineageCatalogObligationService service,
            LineageObligationScanner scanner,
            LineageObligationProjectorCollaborator projectorCollaborator,
            ObjectProvider<LineageHistoricalPublishIntentStore> intentStore,
            ObjectProvider<LineageHistoricalCompensationStore> compensationStore,
            LineageHistoricalPublishMachine historicalMachine,
            LineageSourceDispositionRegistry sourceResolvers,
            ObjectProvider<LineageCurrentEntityRepublisher> republisher) {
        return new LineageObligationWiring(store.getIfAvailable(), probes, historicalPublishers,
                service, scanner, projectorCollaborator, intentStore.getIfAvailable(),
                compensationStore.getIfAvailable(), historicalMachine, sourceResolvers,
                republisher.getIfAvailable());
    }
}
