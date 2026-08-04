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

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewHttpRetryHandler;

/**
 * The budget read from the configuration each target's client actually uses.
 *
 * <h2>Why not the active-backend connection request</h2>
 *
 * <p>{@code MetadataCatalogConnectionResolver.buildConnectionRequest()} answers for whichever
 * backend is <em>enabled</em>. Asking it for a budget therefore returned Purview's timeouts on a
 * node publishing to Atlas, and threw outright when neither was enabled. That is exactly the
 * inference this increment refuses everywhere else: one target's measurement standing in for
 * another's. Here each target is read from its own configuration object, by name, or not at all.
 *
 * <h2>Read on every call</h2>
 *
 * <p>Both configs resolve through {@code readDynamic*}, so a value changed by an administrator
 * takes effect on the next read. Capturing these into fields at construction would freeze
 * readiness at the numbers that happened to be set when the context started, and a later change
 * that pushed the section past the fence lease would never turn the gate red.
 *
 * <h2>A target with no configuration gets no budget</h2>
 *
 * <p>Empty, not a default. Dataplex has no timeout configuration of its own today, so a node
 * that configures it as a lineage target cannot show its section fits inside the fence — and
 * readiness says so instead of quietly borrowing Atlas's numbers.
 */
public final class ConfiguredLineageOperationBudgetProvider
        implements LineageOperationBudgetProvider {

    /**
     * Headroom for what the client does around the request itself: OAuth token refresh on a
     * 401 (the handler retries that path without counting it), connection-pool wait, TLS
     * handshake, response parsing.
     */
    static final long CLIENT_OVERHEAD_MS = 5_000L;

    /**
     * The authoritative source re-check that immediately precedes the publish.
     *
     * <p>Per kind because the resolvers do not share a backend: a CMIS document is a local DAO
     * read, a cold-storage object may be a remote call. Until each kind's real resolver declares
     * its own cost, the remote-shaped kinds are budgeted as if they were remote, which is the
     * safe direction — over-budgeting turns readiness red, under-budgeting lets a section run
     * past its fence.
     */
    static long sourceRecheckMs(EndpointKind kind) {
        return switch (kind) {
            case CMIS_DOCUMENT, CMIS_FOLDER, ARCHIVE, IMPORT_ARTIFACT, EXPORT_ARTIFACT ->
                    2_000L;
            case EXTERNAL_ASSET, CLOUD_OBJECT, COLD_STORAGE -> 30_000L;
        };
    }

    private final PurviewConfig purviewConfig;
    private final AtlasConfig atlasConfig;

    public ConfiguredLineageOperationBudgetProvider(PurviewConfig purviewConfig,
            AtlasConfig atlasConfig) {
        this.purviewConfig = purviewConfig;
        this.atlasConfig = atlasConfig;
    }

    @Override
    public Optional<LineageOperationBudget> budgetFor(String target, EndpointKind kind) {
        if (target == null || target.isBlank() || kind == null) {
            return Optional.empty();
        }
        long connect;
        long read;
        try {
            switch (target.toLowerCase(java.util.Locale.ROOT)) {
                case "purview" -> {
                    if (purviewConfig == null) {
                        return Optional.empty();
                    }
                    connect = purviewConfig.getConnectTimeoutMs();
                    read = purviewConfig.getReadTimeoutMs();
                }
                case "atlas" -> {
                    if (atlasConfig == null) {
                        return Optional.empty();
                    }
                    connect = atlasConfig.getConnectTimeoutMs();
                    read = atlasConfig.getReadTimeoutMs();
                }
                default -> {
                    // No configuration of its own. Not an error, and not a default either.
                    return Optional.empty();
                }
            }
        } catch (RuntimeException unreadable) {
            // A configuration read that fails is a budget that cannot be established. Returning
            // a default here would assert a margin nobody measured.
            return Optional.empty();
        }
        if (connect <= 0 || read <= 0) {
            return Optional.empty();
        }
        return Optional.of(new LineageOperationBudget(target, kind, connect, read,
                PurviewHttpRetryHandler.maxRetries(),
                PurviewHttpRetryHandler.worstCaseBackoffTotalMs(),
                CLIENT_OVERHEAD_MS, sourceRecheckMs(kind)));
    }
}
