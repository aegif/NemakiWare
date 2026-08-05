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

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * One authoritative source per endpoint kind, resolved exactly, with no fallback.
 *
 * <p>A CMIS document, an archive and an external asset are asked different questions of
 * different systems. A registry that fell back to some other kind's resolver would answer about
 * the wrong system entirely — and the answer authorises a permanent tombstone.
 *
 * <p>An unregistered kind is {@link LineageSourceDisposition#SOURCE_UNKNOWN}: nothing was
 * established, which is the same as every other failure and is never a licence to build.
 */
public class LineageSourceDispositionRegistry implements LineageSourceDispositionResolver {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(LineageSourceDispositionRegistry.class);

    private final Map<EndpointKind, LineageSourceDispositionResolver> byKind;
    private final java.util.function.LongSupplier clockMs;

    public LineageSourceDispositionRegistry(
            Map<EndpointKind, LineageSourceDispositionResolver> byKind,
            java.util.function.LongSupplier clockMs) {
        this.byKind = byKind == null ? new EnumMap<>(EndpointKind.class)
                : new EnumMap<>(byKind);
        this.clockMs = clockMs;
    }

    /** Which kinds this node can establish a source verdict for. Structural; no IO. */
    public Set<EndpointKind> knownKinds() {
        return Set.copyOf(byKind.keySet());
    }

    public boolean canResolve(EndpointKind kind) {
        return kind != null && byKind.get(kind) != null;
    }

    @Override
    public SourceEvidence dispositionOf(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        long now = clockMs == null ? 0L : clockMs.getAsLong();
        LineageSourceDispositionResolver resolver = kind == null ? null : byKind.get(kind);
        if (resolver == null) {
            logger.warn("No authoritative source resolver is wired for {} — answering UNKNOWN",
                    kind);
            return SourceEvidence.unknown(now);
        }
        try {
            SourceEvidence evidence =
                    resolver.dispositionOf(repositoryId, kind, catalogQualifiedName);
            return evidence == null ? SourceEvidence.unknown(now) : evidence;
        } catch (RuntimeException e) {
            // Class name only: a repository error can echo an object path.
            logger.warn("Authoritative source lookup for {} failed: {}",
                    kind, e.getClass().getSimpleName());
            return SourceEvidence.unknown(now);
        }
    }

    /**
     * The verdict and, where the resolver can build one, the catalog projection from that read.
     *
     * <p>Routed the same way and failing the same way. A kind with no resolver, a resolver that
     * threw, a null answer — all of them are an unknown verdict with no projection, never a
     * projection without a verdict to license it.
     */
    @Override
    public LiveSourceObservation observeLive(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        long now = clockMs == null ? 0L : clockMs.getAsLong();
        LineageSourceDispositionResolver resolver = kind == null ? null : byKind.get(kind);
        if (resolver == null) {
            logger.warn("No authoritative source resolver is wired for {} — answering UNKNOWN",
                    kind);
            return new LiveSourceObservation(SourceEvidence.unknown(now), null);
        }
        try {
            LiveSourceObservation observation =
                    resolver.observeLive(repositoryId, kind, catalogQualifiedName);
            return observation == null
                    ? new LiveSourceObservation(SourceEvidence.unknown(now), null)
                    : observation;
        } catch (RuntimeException e) {
            logger.warn("Authoritative live observation for {} failed: {}",
                    kind, e.getClass().getSimpleName());
            return new LiveSourceObservation(SourceEvidence.unknown(now), null);
        }
    }
}
