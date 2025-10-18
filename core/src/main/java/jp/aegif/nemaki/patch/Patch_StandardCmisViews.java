package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add all 38 standard CMIS views required by CMIS 1.1 specification.
 *
 * CRITICAL FIX (2025-10-18): Enhanced to create all 38 views from bedroom_init.dump
 * specification instead of only 5 views. This ensures complete CouchDB design document
 * initialization without depending on DatabasePreInitializer.
 *
 * All view definitions are taken from the official bedroom_init.dump file to ensure
 * consistency and correctness.
 */
public class Patch_StandardCmisViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_StandardCmisViews.class);
    private static final String PATCH_NAME = "StandardCmisViews";

    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding all 38 standard CMIS views");

        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for repository");
                return;
            }

            // Get current design document
            String designDocId = "_design/_repo";
            ObjectMapper mapper = new ObjectMapper();

            // Read current design document
            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
                return;
            }

            // Clone the document as ObjectNode for modification
            ObjectNode updatedDoc = currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }

            // Add all 38 standard CMIS views from bedroom_init.dump specification
            addViewIfMissing(views, "attachments", "function(doc) { if (doc.type == 'attachment')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "countByObjectType",
                "function(doc) { if (['cmis:document', 'cmis:folder', 'cmis:relationship', 'cmis:policy', 'cmis:item','cmis:secondary'].indexOf(doc.type) >= 0)  emit(doc.objectType, doc) }",
                "function(key,values){return values.length}", repositoryId);

            addViewIfMissing(views, "propertyDefinitionCoresByPropertyId", "function(doc) { if (doc.type == 'propertyDefinitionCore')  emit(doc.propertyId, doc) }", null, repositoryId);

            addViewIfMissing(views, "children", "function(doc) { if (doc.type == 'cmis:folder' || doc.type == 'cmis:document' && doc.latestVersion || doc.type == 'cmis:item') emit(doc.parentId, doc) }", null, repositoryId);

            addViewIfMissing(views, "relationships", "function(doc) { if (doc.type == 'cmis:relationship')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "documents", "function(doc) { if (doc.type == 'cmis:document')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "privateWorkingCopies", "function(doc) { if (doc.type == 'cmis:document' && doc.privateWorkingCopy) emit(doc.parentId, doc) }", null, repositoryId);

            addViewIfMissing(views, "childByName", "function(doc) { if (doc.type == 'cmis:folder' || doc.type == 'cmis:document' && doc.latestVersion) emit({parentId: doc.parentId, name:doc.name}, doc) }", null, repositoryId);

            addViewIfMissing(views, "propertyDefinitionCores", "function(doc) { if (doc.type == 'propertyDefinitionCore')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "folders", "function(doc) { if (doc.type == 'cmis:folder')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "propertyDefinitionDetailsByCoreNodeId", "function(doc) { if (doc.type == 'propertyDefinitionDetail')  emit(doc.coreNodeId, doc) }", null, repositoryId);

            addViewIfMissing(views, "contentsById", "function(doc) { if (['cmis:document', 'cmis:folder', 'cmis:relationship', 'cmis:policy', 'cmis:item','cmis:secondary'].indexOf(doc.type) >= 0)  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "relationshipsByTarget", "function(doc) { if (doc.type == 'cmis:relationship') emit(doc.targetId, doc) }", null, repositoryId);

            addViewIfMissing(views, "policiesByAppliedObject", "function(doc) { if (doc.type == 'cmis:policy') for(i in doc.appliedIds){emit(i, doc)} }", null, repositoryId);

            addViewIfMissing(views, "documentsByVersionSeriesId", "function(doc) { if (doc.type == 'cmis:document') emit(doc.versionSeriesId, doc) }", null, repositoryId);

            addViewIfMissing(views, "foldersByPath", "function(doc) { if (doc.type == 'cmis:folder')  emit(doc.path, doc) }", null, repositoryId);

            addViewIfMissing(views, "versionSeries", "function(doc) { if (doc.type == 'versionSeries')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "relationshipsBySource", "function(doc) { if (doc.type == 'cmis:relationship') emit(doc.sourceId, doc) }", null, repositoryId);

            addViewIfMissing(views, "propertyDefinitionDetails", "function(doc) { if (doc.type == 'propertyDefinitionDetail')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "admin", "function(doc) { if (doc.type == 'cmis:item' && doc.objectType == 'nemaki:user' && doc.admin === true) emit(doc.userId, doc) }", null, repositoryId);

            addViewIfMissing(views, "items", "function(doc) { if (doc.type == 'cmis:item')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "latestMajorVersions", "function(doc) { if (doc.type == 'cmis:document' && doc.latestMajorVersion)  emit(doc.versionSeriesId, doc) }", null, repositoryId);

            addViewIfMissing(views, "typeDefinitions", "function(doc) { if (doc.type == 'typeDefinition')  emit(doc.typeId, doc) }", null, repositoryId);

            addViewIfMissing(views, "policies", "function(doc) { if (doc.type == 'cmis:policy')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "renditions", "function(doc) { if (doc.type == 'rendition')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "latestVersions", "function(doc) { if (doc.type == 'cmis:document' && doc.latestVersion)  emit(doc.versionSeriesId, doc) }", null, repositoryId);

            addViewIfMissing(views, "changesByToken", "function(doc) { if (doc.type == 'change')  emit(doc.token, doc) }", null, repositoryId);

            addViewIfMissing(views, "changes", "function(doc) { if (doc.type == 'change')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "childrenNames", "function(doc) {var result={}; result[doc.name]=doc._id; if (doc.type == 'cmis:document' && doc.latestVersion || doc.type == 'cmis:folder')   emit(doc.parentId, doc.name);}", null, repositoryId);

            addViewIfMissing(views, "patch", "function(doc) { if (doc.type == 'patch')  emit(doc.name, doc) }", null, repositoryId);

            addViewIfMissing(views, "configuration", "function(doc) { if (doc.type == 'configuration')  emit(doc._id, doc) }", null, repositoryId);

            addViewIfMissing(views, "userItemsById", "function(doc) { if (doc.type == 'cmis:item' && doc.userId)  emit(doc.userId, doc) }", null, repositoryId);

            addViewIfMissing(views, "groupItemsById", "function(doc) { if (doc.type == 'cmis:item' && doc.groupId)  emit(doc.groupId, doc) }", null, repositoryId);

            addViewIfMissing(views, "joinedDirectGroupsByUserId", "function(doc) {if (doc.type == 'cmis:item' && doc.groupId) {if ( doc.subTypeProperties ) {for(var i in doc.subTypeProperties ) {if ( doc.subTypeProperties[i].key == 'nemaki:users' ) {for(var user in doc.subTypeProperties[i].value) {emit(doc.subTypeProperties[i].value[user], doc)}}}}}}", null, repositoryId);

            addViewIfMissing(views, "joinedDirectGroupsByGroupId", "function(doc) {\n    if (doc.type == 'cmis:item' && doc.groupId) {\n        if (doc.subTypeProperties) {\n            for (var i in doc.subTypeProperties) {\n                if (doc.subTypeProperties[i].key == 'nemaki:groups') {\n                    for (var group in doc.subTypeProperties[i].value) {\n                        emit([doc.subTypeProperties[i].value[group],0], doc);\n                        emit([doc.subTypeProperties[i].value[group],1], doc);\n                        emit([doc.subTypeProperties[i].value[group],2], doc);\n                        emit([doc.subTypeProperties[i].value[group],3], doc);\n                        emit([doc.subTypeProperties[i].value[group],4], doc);\n                        emit([doc.subTypeProperties[i].value[group],5], doc);\n                        emit([doc.subTypeProperties[i].value[group],6], doc);\n                        emit([doc.subTypeProperties[i].value[group],7], doc);\n                        emit([doc.subTypeProperties[i].value[group],8], doc);\n                        emit([doc.subTypeProperties[i].value[group],9], doc);\n                        emit([doc.subTypeProperties[i].value[group],10], doc);\n                        emit([doc.subTypeProperties[i].value[group],11], doc);\n                        emit([doc.subTypeProperties[i].value[group],12], doc);\n                        emit([doc.subTypeProperties[i].value[group],13], doc);\n                        emit([doc.subTypeProperties[i].value[group],14], doc);\n                        emit([doc.subTypeProperties[i].value[group],15], doc);\n                        emit([doc.subTypeProperties[i].value[group],16], doc);\n                        emit([doc.subTypeProperties[i].value[group],17], doc);\n                        emit([doc.subTypeProperties[i].value[group],18], doc);\n                        emit([doc.subTypeProperties[i].value[group],19], doc);\n                    }\n                }\n            }\n        }\n    }\n}", null, repositoryId);

            addViewIfMissing(views, "changesByObjectId", "function(doc) { if (doc.type == 'change')  emit(doc.objectId, doc) }", null, repositoryId);

            addViewIfMissing(views, "dupVersionSeries", "function(doc) { if(doc.baseType == 'cmis:document')  emit([doc.name,doc.versionSeriesId],1)}", "function(keys, values) {return sum(values)}", repositoryId);

            addViewIfMissing(views, "dupLatestVersion", "function(doc) { if (doc.baseType == 'cmis:document' && doc.latestVersion )  emit([doc.name,doc.versionSeriesId],1) }", "function(keys, values) { return sum(values) }", repositoryId);

            // Update the design document
            client.update(updatedDoc);

            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully updated design document with all 38 standard CMIS views");

        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add standard CMIS views", e);
            throw new RuntimeException("Failed to apply standard CMIS views patch", e);
        }
    }

    private void addViewIfMissing(ObjectNode views, String viewName, String mapFunction, String reduceFunction, String repositoryId) {
        if (!views.has(viewName)) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode viewDef = mapper.createObjectNode();
            viewDef.put("map", mapFunction);
            if (reduceFunction != null && !reduceFunction.isEmpty()) {
                viewDef.put("reduce", reduceFunction);
            }
            views.set(viewName, viewDef);
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added missing view: " + viewName);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] View already exists: " + viewName);
            }
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}
