package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines semantic relationship subtypes for external ingestion.
 *
 * <ul>
 *   <li>{@code nemaki:hasAttachment} — links a message/page to its extracted attachment</li>
 *   <li>{@code nemaki:attachedToRecord} — links an attachment to a business record</li>
 *   <li>{@code nemaki:derivedFromContext} — links a document to its conversation/thread context</li>
 * </ul>
 *
 * These extend {@code cmis:relationship} and are queryable via the standard
 * CMIS relationship API with type filtering.
 */
public class Patch_IngestRelationshipTypes extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_IngestRelationshipTypes.class);
    private static final String PATCH_NAME = "ingest-relationship-types-20260404";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== INGEST RELATIONSHIP TYPES PATCH for repository: " + repositoryId + " ===");

        TypeService typeService = patchUtil.getTypeService();
        if (typeService == null) {
            log.warn("TypeService not available for " + repositoryId);
            return;
        }

        createRelationshipTypeIfAbsent(typeService, repositoryId,
                "nemaki:hasAttachment", "Has Attachment",
                "Links a message or page to its extracted file attachment");

        createRelationshipTypeIfAbsent(typeService, repositoryId,
                "nemaki:attachedToRecord", "Attached To Record",
                "Links an attachment to a business record or case");

        createRelationshipTypeIfAbsent(typeService, repositoryId,
                "nemaki:derivedFromContext", "Derived From Context",
                "Links a document to its conversation, thread, or process context");

        // Invalidate and rebuild type cache so new types are immediately available
        if (patchUtil.getTypeManager() != null) {
            patchUtil.getTypeManager().invalidateTypeCache(repositoryId);
            try {
                patchUtil.getTypeManager().refreshTypes();
            } catch (Exception e) {
                log.warn("Type cache refresh failed: " + e.getMessage());
            }
        }

        log.info("=== INGEST RELATIONSHIP TYPES PATCH COMPLETED for " + repositoryId + " ===");
    }

    private void createRelationshipTypeIfAbsent(TypeService typeService, String repositoryId,
                                                 String typeId, String displayName, String description) {
        try {
            if (typeService.getTypeDefinition(repositoryId, typeId) != null) {
                log.info("Relationship type already exists: " + typeId);
                return;
            }
        } catch (Exception e) {
            // Type doesn't exist — proceed to create
        }

        try {
            NemakiTypeDefinition typeDef = new NemakiTypeDefinition();
            typeDef.setTypeId(typeId);
            typeDef.setLocalName(typeId);
            typeDef.setLocalNameSpace("http://www.aegif.jp/NEMAKI");
            typeDef.setQueryName(typeId);
            typeDef.setDisplayName(displayName);
            typeDef.setDescription(description);
            typeDef.setBaseId(BaseTypeId.CMIS_RELATIONSHIP);
            typeDef.setParentId("cmis:relationship");
            typeDef.setCreatable(true);
            typeDef.setFilable(false);
            typeDef.setQueryable(true);
            typeDef.setControllablePolicy(false);
            typeDef.setControllableACL(false);
            typeDef.setFulltextIndexed(false);
            typeDef.setIncludedInSupertypeQuery(true);
            typeDef.setProperties(new ArrayList<>());
            // Allowed source/target: any document type
            typeDef.setAllowedSourceTypes(List.of("cmis:document"));
            typeDef.setAllowedTargetTypes(List.of("cmis:document"));

            typeService.createTypeDefinition(repositoryId, typeDef);
            log.info("Created relationship type: " + typeId);
        } catch (Exception e) {
            log.warn("Failed to create relationship type " + typeId + ": " + e.getMessage());
        }
    }
}
