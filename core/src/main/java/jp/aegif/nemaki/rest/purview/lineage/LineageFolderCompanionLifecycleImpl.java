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
package jp.aegif.nemaki.rest.purview.lineage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/** See {@link LineageFolderCompanionLifecycle}. */
@Service
public class LineageFolderCompanionLifecycleImpl implements LineageFolderCompanionLifecycle {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageFolderCompanionLifecycleImpl.class);

    static final String COMPANION_TYPE = "nemaki_folder_dataset";

    /**
     * The attributes a lifecycle transition owns. Everything else on the companion is left as it
     * was — a rename is a different event, and this one does not know the new name.
     */
    private static final List<String> LIFECYCLE_ATTRIBUTES = List.of("active", "sourceState");

    /**
     * Every attribute a companion may carry (§3). Anything else is dropped on write-back.
     *
     * <p>{@link #markState} reads the stored entity and writes it back with two fields changed.
     * A catalog bulk write MERGES — an attribute the payload omits keeps its old value — so
     * writing back whatever came out would preserve attributes this build no longer declares:
     * a field removed from the schema, or one an older version set, survives every transition
     * forever and nothing ever says why it is there. Rebuilding from a known list is what makes
     * a transition converge on the current shape instead of accumulating the history of every
     * shape the type has had.
     */
    private static final List<String> COMPANION_ATTRIBUTES =
            List.of("qualifiedName", "name", "repositoryId", "objectId", "active", "sourceState");

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public LineageFolderCompanionLifecycleImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.connectionResolver = connectionResolver;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public Map<String, Object> companionFor(String repositoryId, Content content) {
        if (content == null || !content.isFolder() || content.getId() == null
                || content.getId().isBlank()) {
            return null;
        }
        return entityPayloadFactory.buildFolderDatasetEntity(repositoryId, content,
                PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE);
    }

    @Override
    public int tie(String repositoryId, List<Content> folders,
            Map<String, String> guidByQualifiedName) {
        if (folders == null || folders.isEmpty()) {
            return 0;
        }
        Map<String, String> guids = guidByQualifiedName == null ? Map.of() : guidByQualifiedName;
        int tied = 0;
        for (Content folder : folders) {
            if (folder == null || !folder.isFolder()) {
                continue;
            }
            try {
                PurviewEntityPublishResult result = entityRegistryClient.createRelationship(
                        connectionResolver.buildConnectionRequest(),
                        entityPayloadFactory.buildFolderDatasetRelationship(
                                repositoryId, folder, guids));
                // A null result is a client that answered nothing. Treated as a failed tie
                // rather than allowed to NPE: an exception here would abort the whole publish
                // batch over one relationship, losing the entities that did succeed.
                if (result != null && result.isSuccess()) {
                    tied++;
                } else {
                    // The message can echo a catalog response body, so it is not logged.
                    logger.warn("Could not tie the folder companion for one folder in '{}'",
                            repositoryId);
                }
            } catch (PurviewClientException e) {
                logger.warn("Could not tie a folder companion in '{}': {}",
                        repositoryId, e.getClass().getSimpleName());
            }
        }
        return tied;
    }

    @Override
    public boolean markState(String repositoryId, String objectId, String sourceState) {
        if (repositoryId == null || objectId == null || sourceState == null) {
            return false;
        }
        String qualifiedName =
                LineageEndpoint.folderProxyQualifiedName(repositoryId, objectId);
        Map<String, Object> existing;
        try {
            existing = entityRegistryClient.getEntityByUniqueAttribute(
                    connectionResolver.buildConnectionRequest(),
                    COMPANION_TYPE, "qualifiedName", qualifiedName);
        } catch (PurviewClientException e) {
            logger.warn("Cannot read the folder companion in '{}' to mark it {}: {}",
                    repositoryId, sourceState, e.getClass().getSimpleName());
            return false;
        }
        Map<String, Object> attributes = attributesOf(existing);
        if (attributes == null) {
            // No companion, and nothing here knows what the folder was called. Inventing one
            // would assert a history nobody observed; reconciliation reports the gap instead.
            logger.info("No folder companion in '{}' to mark {} — leaving it to reconciliation",
                    repositoryId, sourceState);
            return false;
        }

        // Only the declared attributes are carried forward; see COMPANION_ATTRIBUTES.
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String declared : COMPANION_ATTRIBUTES) {
            if (attributes.containsKey(declared)) {
                merged.put(declared, attributes.get(declared));
            }
        }
        merged.put("qualifiedName", qualifiedName);
        merged.put("active",
                PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE.equals(sourceState));
        merged.put("sourceState", sourceState);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", COMPANION_TYPE);
        entity.put("attributes", CatalogSecretBoundary.sealed(merged));
        entity.put("status", "ACTIVE");

        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(List.of(entity)));
            if (result == null || !result.isSuccess()) {
                logger.warn("Could not mark the folder companion in '{}' as {}",
                        repositoryId, sourceState);
                return false;
            }
            return true;
        } catch (PurviewClientException e) {
            logger.warn("Could not mark the folder companion in '{}' as {}: {}",
                    repositoryId, sourceState, e.getClass().getSimpleName());
            return false;
        }
    }

    /** Which attributes a lifecycle call owns; the rest of a companion is not its business. */
    static List<String> lifecycleAttributes() {
        return LIFECYCLE_ATTRIBUTES;
    }

    /**
     * The attributes of a read entity, whether the catalog wrapped them in {@code entity} or not.
     *
     * <p>Both shapes are in use across catalog versions, and guessing wrong would look like
     * "the companion does not exist" — which is the one answer that must be right, since it is
     * what stops a purge from inventing one.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> read) {
        if (read == null || read.isEmpty()) {
            return null;
        }
        Object wrapped = read.get("entity");
        Map<String, Object> source = wrapped instanceof Map<?, ?> map
                ? (Map<String, Object>) map : read;
        Object attributes = source.get("attributes");
        return attributes instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
