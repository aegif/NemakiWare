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
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;

/**
 * Puts the live object's entity back over a tombstone that should not be there.
 *
 * <h2>What this is for</h2>
 *
 * <p>A historical entity is written for a source that was purged. If the source is restored
 * between the authorising check and the write, the catalog ends up holding a tombstone for a
 * document that exists. Not resolving the obligation is not enough — the wrong entity would sit
 * there for ever, because the obligation machine's retries all end in "the source is present,
 * nothing owed".
 *
 * <p>So the compensation converges on the truth: republish the object as it is now. A repair,
 * not a delete — deleting would leave the catalog with no record of an object that exists, which
 * is the same class of wrong.
 *
 * <h2>How it finds the object from a digest</h2>
 *
 * <p>The compensation carries a subject digest and nothing else, deliberately: it is stored,
 * listed on admin routes and logged, and an external asset's qualified name contains its stable
 * key. A digest cannot be reversed — but the purge ledger is already keyed by exactly this
 * digest, and its mark records the incarnation that was destroyed, which for a CMIS subject is
 * the object id. So the identifier comes from the ledger rather than from a hash nobody can
 * invert, and the derived qualified name is re-hashed and compared before anything is written.
 * A subject whose ledger entry does not reproduce the digest is not repaired.
 */
public final class CatalogCurrentEntityRepublisher implements LineageCurrentEntityRepublisher {

    private static final Logger logger =
            LoggerFactory.getLogger(CatalogCurrentEntityRepublisher.class);

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewEntityPayloadFactory payloadFactory;
    private final ContentService contentService;
    private final LineagePurgeLedger purgeLedger;

    public CatalogCurrentEntityRepublisher(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewEntityPayloadFactory payloadFactory, ContentService contentService,
            LineagePurgeLedger purgeLedger) {
        this.connectionResolver = connectionResolver;
        this.entityRegistryClient = entityRegistryClient;
        this.payloadFactory = payloadFactory;
        this.contentService = contentService;
        this.purgeLedger = purgeLedger;
    }

    @Override
    public Outcome republishCurrent(String target, String repositoryId, EndpointKind kind,
            String subjectDigest) {
        if (connectionResolver == null || entityRegistryClient == null || payloadFactory == null
                || contentService == null || purgeLedger == null) {
            return Outcome.RETRYABLE;
        }
        if (repositoryId == null || kind == null || subjectDigest == null) {
            return Outcome.SOURCE_UNKNOWN;
        }
        Map<String, Object> entity;
        try {
            entity = currentEntity(repositoryId, kind, subjectDigest);
        } catch (RuntimeException e) {
            // A ledger or repository read that failed says nothing about the object. Retryable,
            // because the compensation must stay open until the entity is actually corrected.
            logger.warn("Could not rebuild the current entity for a {} subject: {}", kind,
                    e.getClass().getSimpleName());
            return Outcome.RETRYABLE;
        }
        if (entity == null) {
            // The object cannot be identified or is not one this service owns. Not a success:
            // SOURCE_UNKNOWN leaves the compensation visible rather than silently resolved.
            return Outcome.SOURCE_UNKNOWN;
        }
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    LineageHistoricalEntityFactory.bulkPayload(entity));
            if (result == null || !result.isSuccess()) {
                return Outcome.RETRYABLE;
            }
            return Outcome.REPUBLISHED;
        } catch (PurviewClientException | RuntimeException e) {
            logger.warn("Republishing the current entity to '{}' failed: {}", target,
                    e.getClass().getSimpleName());
            return Outcome.RETRYABLE;
        }
    }

    /**
     * The live object's entity, built by the same factory the ordinary catalog sync uses.
     *
     * <p>Same builder, so the repair lands on the same content the next ordinary sync would
     * write. A second implementation here would let the repair and the steady state disagree,
     * and the disagreement would look like drift nobody caused.
     *
     * @return null when the object cannot be identified — never a guess
     */
    private Map<String, Object> currentEntity(String repositoryId, EndpointKind kind,
            String subjectDigest) {
        // Only the CMIS kinds have a live object this service can rebuild. The external kinds
        // are owned by their own connectors, which publish them on their own schedule; a repair
        // invented here would assert content this node never observed.
        if (kind != EndpointKind.CMIS_DOCUMENT && kind != EndpointKind.CMIS_FOLDER) {
            return null;
        }
        Optional<LineagePurgeLedger.PurgeMark> mark =
                purgeLedger.find(repositoryId, kind, subjectDigest);
        if (mark.isEmpty()) {
            // No ledger entry, so nothing says which object this subject was. Without it the
            // repair would have to guess, and a guess here overwrites some other object's
            // entity.
            return null;
        }
        String objectId = mark.get().incarnation();
        if (objectId == null || objectId.isBlank()) {
            return null;
        }
        // The ledger says which object; this proves the ledger is talking about the subject
        // being repaired. A mismatch means the digest and the mark disagree, and writing on a
        // disagreement is how one object's repair corrupts another's entity.
        String derived = LineageSourceDispositionResolver.SourceEvidence.subjectDigest(
                repositoryId, kind, qualifiedNameOf(repositoryId, kind, objectId));
        if (!subjectDigest.equals(derived)) {
            logger.warn("A purge mark for a {} subject does not reproduce its subject digest —"
                    + " refusing to repair an entity this cannot identify", kind);
            return null;
        }

        Content content = contentService.getContent(repositoryId, objectId);
        if (content == null) {
            // The object is not back after all. Nothing to republish, and the tombstone may
            // well be correct — SOURCE_UNKNOWN rather than an invented entity.
            return null;
        }
        // The subject's own type, not the folder's.
        //
        // A CMIS_FOLDER subject is the DataSet proxy — nemaki_folder_dataset at
        // .../folders/{id}/dataset — which is what a tombstone for it was written against and
        // what qualifiedNameOf() above compares. buildFolderEntity produces the other thing: a
        // nemaki_folder at .../objects/{id}. Repairing with it wrote a different type at a
        // different name, so the tombstone this compensation exists to overwrite was left
        // exactly where it was.
        //
        // It could not even fail visibly: nemaki_folder does not declare sourceState, so the
        // explicit ACTIVE below made Atlas reject the whole write, and the compensation retried
        // for ever against a tombstone nothing could reach.
        Map<String, Object> entity = kind == EndpointKind.CMIS_FOLDER
                ? payloadFactory.buildFolderDatasetEntity(repositoryId, content,
                        PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE)
                : payloadFactory.buildDocumentEntity(repositoryId, content);
        if (entity == null) {
            return null;
        }
        // The object is live, so its entity says so. Whatever state the builder produced would
        // be right for a normal sync, but this write exists specifically to overwrite a PURGED
        // tombstone and must be explicit about that. Per type: the DataSet proxy carries
        // sourceState (already set by its builder above), a document carries lifecycleState.
        Object attributes = entity.get("attributes");
        if (attributes instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) attributes;
            attrs.put(kind == EndpointKind.CMIS_FOLDER
                            ? LineageHistoricalEntityFactory.SOURCE_STATE
                            : LineageHistoricalEntityFactory.LIFECYCLE_STATE,
                    PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE);
        }
        return entity;
    }

    /**
     * The qualified name a CMIS subject has.
     *
     * <p>Built here rather than taken from anywhere, so the comparison above is against the
     * form {@link LineageEndpoint} actually produces. A folder is referenced through its
     * DataSet proxy, which is a different name from the folder's own.
     */
    static String qualifiedNameOf(String repositoryId, EndpointKind kind, String objectId) {
        return kind == EndpointKind.CMIS_FOLDER
                ? "nemaki://" + repositoryId + "/folders/" + objectId + "/dataset"
                : "nemaki://" + repositoryId + "/objects/" + objectId;
    }
}
