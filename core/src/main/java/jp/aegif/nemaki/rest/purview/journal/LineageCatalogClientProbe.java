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

import java.util.Map;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;

/**
 * Asks one catalog whether it holds an entity, by qualified name.
 *
 * <h2>The 404 / failure split, which is the whole job</h2>
 *
 * <p>{@code getEntityByUniqueAttribute} returns {@code null} for a 404 and throws for anything
 * else. That is exactly the three-valued answer the obligation machine needs, and it is the one
 * place the distinction can be made honestly: past this point a caller sees only PRESENT,
 * ABSENT or UNKNOWN and can no longer confuse an outage with a missing entity.
 *
 * <p>Bound to one target at construction. A probe that read the active backend at call time
 * would answer for whichever catalog was configured, under a task key naming another.
 */
public class LineageCatalogClientProbe implements LineageCatalogEntityProbe {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(LineageCatalogClientProbe.class);

    private final String boundTarget;
    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public LineageCatalogClientProbe(String boundTarget,
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.boundTarget = boundTarget;
        this.connectionResolver = connectionResolver;
        this.entityRegistryClient = entityRegistryClient;
    }

    /** The one target this probe may answer for. */
    public String targetName() {
        return boundTarget;
    }

    @Override
    public Presence presenceOf(String target, String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        if (boundTarget == null || !boundTarget.equals(target)) {
            // Asked about a catalog this probe is not bound to. Refusing is the point: an
            // answer here would be attributed to a target it did not come from.
            return Presence.UNKNOWN;
        }
        if (connectionResolver == null || entityRegistryClient == null
                || catalogQualifiedName == null || catalogQualifiedName.isBlank()) {
            return Presence.UNKNOWN;
        }
        try {
            Map<String, Object> read = entityRegistryClient.getEntityByUniqueAttribute(
                    connectionResolver.buildConnectionRequest(), kind.atlasTypeName(),
                    "qualifiedName", catalogQualifiedName);
            // null is the client's 404 — the catalog answered, and does not hold it.
            return read == null || read.isEmpty() ? Presence.ABSENT : Presence.PRESENT;
        } catch (PurviewClientException | RuntimeException e) {
            // Class name only: a catalog response body echoes the qualified name, and an
            // external asset's qualified name contains its stable key.
            logger.warn("Catalog probe for target '{}' could not answer: {}",
                    boundTarget, e.getClass().getSimpleName());
            return Presence.UNKNOWN;
        }
    }
}
