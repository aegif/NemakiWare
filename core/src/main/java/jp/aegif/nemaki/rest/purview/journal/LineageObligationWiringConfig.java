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
     * The purge ledger, over the same database as the journal.
     *
     * <p>The only thing that can authorise a tombstone. Registered here rather than beside the
     * repository services because it lives in the lineage database and shares its provisioning.
     */
    @Bean
    public LineagePurgeLedger lineagePurgeLedger(
            ObjectProvider<LineageJournalStore> journalStore) {
        LineageJournalStore store = journalStore.getIfAvailable();
        return store instanceof LineageStoreSupport support
                ? new CouchLineagePurgeLedger(support) : null;
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
        for (LineageTargetSink sink : sinks.orderedStream().toList()) {
            String target = sink == null ? null : sink.targetName();
            if (!hasCatalogClient(resolver, target)) {
                continue;
            }
            byTarget.put(target, new LineageCatalogClientProbe(target, resolver, client));
        }
        return new LineageCatalogProbeRegistry(byTarget);
    }

    /**
     * One historical publisher per target that can be verified.
     *
     * <p>Derived from the same sinks as the probes, and bound the same way: a publisher answers
     * for its own target and refuses every other, so a write can never be recorded against a
     * catalog it did not reach. A target with no verifying sink gets none, and readiness names
     * it — inventing one would claim this node can rebuild a purged entity in a catalog it
     * cannot even ask about.
     */
    @Bean
    public LineageHistoricalPublisherRegistry lineageHistoricalPublisherRegistry(
            ObjectProvider<LineageTargetSink> sinks,
            ObjectProvider<jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver>
                    connectionResolver,
            ObjectProvider<jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient>
                    entityRegistryClient,
            ObjectProvider<LineageHistoricalEntityPublisher> explicitPublishers) {
        Map<String, LineageHistoricalEntityPublisher> byTarget = new LinkedHashMap<>();
        var resolver = connectionResolver.getIfAvailable();
        var client = entityRegistryClient.getIfAvailable();
        if (resolver != null && client != null) {
            for (LineageTargetSink sink : sinks.orderedStream().toList()) {
                String target = sink == null ? null : sink.targetName();
                if (!hasCatalogClient(resolver, target)) {
                    continue;
                }
                byTarget.put(target,
                        new CatalogHistoricalEntityPublisher(target, resolver, client));
            }
        }
        // An explicitly declared publisher wins over the derived one: a deployment that needs a
        // different write path for a target says so with a bean, and this must not overrule it.
        for (LineageHistoricalEntityPublisher publisher
                : explicitPublishers.orderedStream().toList()) {
            if (publisher instanceof TargetedHistoricalPublisher targeted) {
                byTarget.put(targeted.targetName(), publisher);
            }
        }
        return new LineageHistoricalPublisherRegistry(byTarget);
    }

    /**
     * The compensation's repair path.
     *
     * <p>Uses the ordinary entity payload factory, so a repair writes what the next normal sync
     * would write. A second builder here would let the repair and the steady state disagree.
     */
    @Bean
    public LineageCurrentEntityRepublisher lineageCurrentEntityRepublisher(
            ObjectProvider<jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver>
                    connectionResolver,
            ObjectProvider<jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient>
                    entityRegistryClient,
            ObjectProvider<jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory>
                    payloadFactory,
            ObjectProvider<jp.aegif.nemaki.businesslogic.ContentService> contentService,
            ObjectProvider<LineagePurgeLedger> purgeLedger) {
        return new CatalogCurrentEntityRepublisher(connectionResolver.getIfAvailable(),
                entityRegistryClient.getIfAvailable(), payloadFactory.getIfAvailable(),
                contentService.getIfAvailable(), purgeLedger.getIfAvailable());
    }

    /**
     * Whether the catalog client this node holds actually answers for this target.
     *
     * <h2>Why not {@code supportsVerification()}</h2>
     *
     * <p>That flag is about reading a published lineage <em>Process</em> back — the D-rest
     * machine's own verify step. A catalog probe and a historical entity write need neither: they
     * talk to the entity API. Keying off it meant a target whose catalog is perfectly reachable
     * got no probe and no publisher, and the preflight reported the adapters as missing when the
     * only thing missing was Process verification.
     *
     * <h2>Why the active backend, and not "the config is enabled"</h2>
     *
     * <p>{@code buildConnectionRequest()} answers for whichever backend is active — one client,
     * one endpoint. Binding a probe to a target the client does not point at would attribute an
     * answer to a catalog it never reached, which is the cross-target inference this whole
     * increment refuses. A target that is configured but not the active backend gets nothing,
     * and readiness says so.
     */
    private static boolean hasCatalogClient(
            jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver resolver,
            String target) {
        if (resolver == null || target == null || target.isBlank()) {
            return false;
        }
        return switch (resolver.activeBackend()) {
            case PURVIEW -> "purview".equals(target);
            case ATLAS -> "atlas".equals(target);
            case NONE -> false;
        };
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

    /** The reverse lookup, over the same database and strict-IO rules as the journal. */
    @Bean
    public LineageWaitingSnapshotResolver lineageWaitingSnapshotResolver(
            ObjectProvider<LineageJournalStore> journalStore) {
        LineageJournalStore store = journalStore.getIfAvailable();
        return store instanceof LineageStoreSupport support
                ? new LineageWaitingSnapshotResolver(new CouchLineageWaitingEventSource(support))
                : null;
    }

    /**
     * One observed-entity materializer per target that this node's catalog client answers for.
     *
     * <p>Derived from the same sinks as the probes and the historical publishers, and bound the
     * same way: a materializer answers for its own target and refuses every other, so a write
     * cannot be recorded against a catalog it did not reach.
     *
     * <p>Every matching sink is registered, not the first one. Returning the first was what made
     * this a single instance, and a single instance is only correct while exactly one target is
     * configured — a condition nothing checked and nothing enforced.
     */
    @Bean
    public LineageObservedEntityMaterializerRegistry lineageObservedEntityMaterializerRegistry(
            ObjectProvider<LineageTargetSink> sinks,
            ObjectProvider<jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver>
                    connectionResolver,
            ObjectProvider<jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient>
                    entityRegistryClient) {
        Map<String, LineageObservedEntityMaterializer> byTarget = new LinkedHashMap<>();
        var resolver = connectionResolver.getIfAvailable();
        var client = entityRegistryClient.getIfAvailable();
        if (resolver == null || client == null) {
            return new LineageObservedEntityMaterializerRegistry(byTarget);
        }
        for (LineageTargetSink sink : sinks.orderedStream().toList()) {
            String target = sink == null ? null : sink.targetName();
            if (!hasCatalogClient(resolver, target)) {
                continue;
            }
            byTarget.put(target, new CatalogObservedEntityMaterializer(target, resolver, client));
        }
        return new LineageObservedEntityMaterializerRegistry(byTarget);
    }

    /**
     * The ABSENT branch, wired to the same instances everything else got.
     *
     * <p>Set onto the service rather than constructor-injected: the settler and the service
     * would otherwise be constructor arguments of each other. Readiness compares the references
     * by identity, so a second settler or a second machine cannot hide behind a null check.
     */
    @Bean
    public LineageCatalogAbsenceSettler lineageCatalogAbsenceSettler(
            LineageCatalogObligationService service,
            ObjectProvider<LineageWaitingSnapshotResolver> waitingSnapshotResolver,
            LineageHistoricalPublishMachine historicalMachine,
            ObjectProvider<LineageObservedEntityMaterializerRegistry> observedMaterializers,
            LineageSourceDispositionRegistry sourceResolvers) {
        LineageCatalogAbsenceSettler settler = new PolicyRoutedAbsenceSettler(
                waitingSnapshotResolver.getIfAvailable(), historicalMachine,
                observedMaterializers.getIfAvailable(), sourceResolvers);
        service.setAbsenceSettler(settler);
        return settler;
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

    /**
     * The capture boundary's store. ONE bean, satisfying both of its interfaces.
     *
     * <p>An earlier version registered three beans over one instance — the concrete type plus one
     * per interface — reasoning that the two seams have different callers. That does not work in
     * Spring: {@code CouchCaptureIntentStore} implements both interfaces, so each interface type
     * then had TWO candidates, and {@code @Autowired(required = false)} tolerates <em>absence</em>
     * but not <em>ambiguity</em>. Injection points whose field name does not happen to match a
     * bean name — {@code CaptureIntentSweeper.maintenanceStore},
     * {@code CaptureIntentController.maintenanceStore} — would fail with
     * {@code NoUniqueBeanDefinitionException}, and because {@code serviceContext.xml} is loaded by
     * re-refreshing the root context that is not a degraded feature but a WAR that does not start
     * (external review).
     *
     * <p>One bean gives both seams the same instance, which was the actual requirement.
     */
    @Bean
    public CouchCaptureIntentStore couchCaptureIntentStore(
            ObjectProvider<LineageJournalStore> journalStore,
            ObjectProvider<LineageConfig> lineageConfig) {
        LineageJournalStore store = journalStore.getIfAvailable();
        return store instanceof LineageStoreSupport support
                ? new CouchCaptureIntentStore(support, lineageConfig.getIfAvailable()) : null;
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
     * <h2>Not an always-UNKNOWN placeholder</h2>
     *
     * <p>Every kind gets a resolver that can reach a real verdict: PURGED whenever the purge
     * ledger holds an unsuperseded mark for the subject, and EXISTS additionally for the kinds
     * whose live object this service can read by identity. Registering a resolver that always
     * answered UNKNOWN would turn readiness green while making {@code SOURCE_PURGED}
     * unreachable, so every obligation for a genuinely purged source would retry for ever.
     *
     * <p>What none of them does is infer PURGED from absence — see
     * {@link RepositorySourceDispositionResolver}.
     */
    @Bean
    public LineageSourceDispositionRegistry lineageSourceDispositionRegistry(
            ObjectProvider<KindBoundSourceResolver> resolvers,
            ObjectProvider<jp.aegif.nemaki.businesslogic.ContentService> contentService,
            ObjectProvider<LineagePurgeLedger> purgeLedger,
            ObjectProvider<jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory>
                    payloadFactory) {
        Map<EndpointKind, LineageSourceDispositionResolver> byKind =
                new java.util.EnumMap<>(EndpointKind.class);
        var content = contentService.getIfAvailable();
        var ledger = purgeLedger.getIfAvailable();
        // The same factory the ordinary sync and the compensation use, so a live-source
        // publication lands on the content the next ordinary sync would write.
        var payloads = payloadFactory.getIfAvailable();
        if (ledger != null) {
            for (EndpointKind kind : EndpointKind.values()) {
                byKind.put(kind, new RepositorySourceDispositionResolver(kind, content, ledger,
                        System::currentTimeMillis, payloads));
            }
        }
        // An explicitly declared resolver wins: a connector that owns a kind's lifecycle knows
        // more about it than the generic ledger-backed one does.
        for (KindBoundSourceResolver resolver : resolvers.orderedStream().toList()) {
            byKind.put(resolver.endpointKind(), resolver);
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
            ObjectProvider<LineageCurrentEntityRepublisher> republisher,
            LineageOperationBudgetProvider budgets,
            ObjectProvider<LineagePurgeLedger> purgeLedger,
            ObjectProvider<LineageConfig> lineageConfig) {
        var config = lineageConfig.getIfAvailable();
        return new LineageObligationWiring(store.getIfAvailable(), probes, historicalPublishers,
                service, scanner, projectorCollaborator, intentStore.getIfAvailable(),
                compensationStore.getIfAvailable(), historicalMachine, sourceResolvers,
                republisher.getIfAvailable(), budgets, purgeLedger.getIfAvailable(),
                config == null ? java.util.Set.of() : config.getNonEmittableKinds());
    }

    /**
     * The budget readiness compares against the fence lease.
     *
     * <p>A provider, not a value. Timeouts are administrator-managed and resolve dynamically, so
     * a number captured here would freeze readiness at whatever was configured when the context
     * started — and a later change that pushed a section past the lease would never turn the
     * gate red.
     */
    @Bean
    public LineageOperationBudgetProvider lineageOperationBudgetProvider(
            ObjectProvider<jp.aegif.nemaki.rest.purview.PurviewConfig> purviewConfig,
            ObjectProvider<AtlasConfig> atlasConfig) {
        return new ConfiguredLineageOperationBudgetProvider(purviewConfig.getIfAvailable(),
                atlasConfig.getIfAvailable());
    }

}
