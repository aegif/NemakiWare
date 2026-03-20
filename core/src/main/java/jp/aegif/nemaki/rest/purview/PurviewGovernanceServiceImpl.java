package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewGovernanceServiceImpl implements PurviewGovernanceService {

    private static final String DOCUMENT_ENTITY_TYPE = "nemaki_document";
    private static final String FOLDER_ENTITY_TYPE = "nemaki_folder";

    private final PurviewConfig purviewConfig;
    private final ContentService contentService;
    private final ExceptionService exceptionService;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewEntityPayloadFactory entityPayloadFactory;

    public PurviewGovernanceServiceImpl(
            PurviewConfig purviewConfig,
            @Qualifier("ContentService") ContentService contentService,
            ExceptionService exceptionService,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewEntityPayloadFactory entityPayloadFactory) {
        this.purviewConfig = purviewConfig;
        this.contentService = contentService;
        this.exceptionService = exceptionService;
        this.entityRegistryClient = entityRegistryClient;
        this.entityPayloadFactory = entityPayloadFactory;
    }

    @Override
    public PurviewGovernanceView getGovernance(String repositoryId, String objectId, CallContext callContext) {
        Content content = contentService.getContent(repositoryId, objectId);
        if (content == null) {
            throw new CmisObjectNotFoundException("Object not found: " + objectId);
        }

        exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

        GovernanceTarget target = resolveTarget(repositoryId, content);
        if (!target.supportedObjectType()) {
            return new PurviewGovernanceView(
                    purviewConfig.isEnabled(),
                    false,
                    false,
                    false,
                    repositoryId,
                    objectId,
                    target.objectBaseType(),
                    null,
                    null,
                    purviewConfig.getAtlasBasePath(),
                    "Purview governance is available only for documents and folders",
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
        }

        if (!purviewConfig.isEnabled()) {
            return new PurviewGovernanceView(
                    false,
                    false,
                    true,
                    false,
                    repositoryId,
                    objectId,
                    target.objectBaseType(),
                    target.entityTypeName(),
                    target.qualifiedName(),
                    purviewConfig.getAtlasBasePath(),
                    "Purview integration is disabled",
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
        }

        List<String> missingConfiguration = getMissingConfiguration();
        if (!missingConfiguration.isEmpty()) {
            return new PurviewGovernanceView(
                    true,
                    false,
                    true,
                    false,
                    repositoryId,
                    objectId,
                    target.objectBaseType(),
                    target.entityTypeName(),
                    target.qualifiedName(),
                    purviewConfig.getAtlasBasePath(),
                    "Missing Purview configuration: " + String.join(", ", missingConfiguration),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of());
        }

        String lastBasePath = purviewConfig.getAtlasBasePath();
        for (String candidateBasePath : buildCandidateBasePaths(purviewConfig.getAtlasBasePath())) {
            lastBasePath = candidateBasePath;
            try {
                Map<String, Object> response = entityRegistryClient.getEntityByUniqueAttribute(
                        buildConnectionRequest(candidateBasePath),
                        target.entityTypeName(),
                        "qualifiedName",
                        target.qualifiedName());
                if (response == null) {
                    continue;
                }
                return buildFoundView(repositoryId, objectId, target, candidateBasePath, response);
            } catch (PurviewClientException e) {
                return new PurviewGovernanceView(
                        true,
                        false,
                        true,
                        false,
                        repositoryId,
                        objectId,
                        target.objectBaseType(),
                        target.entityTypeName(),
                        target.qualifiedName(),
                        candidateBasePath,
                        e.getMessage(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of());
            }
        }

        return new PurviewGovernanceView(
                true,
                true,
                true,
                false,
                repositoryId,
                objectId,
                target.objectBaseType(),
                target.entityTypeName(),
                target.qualifiedName(),
                lastBasePath,
                "Purview governance metadata is not synced for this object yet",
                List.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    @Override
    public List<PurviewGovernanceBulkItemView> getGovernanceBulk(
            String repositoryId,
            List<String> objectIds,
            CallContext callContext) {
        if (objectIds == null || objectIds.isEmpty()) {
            return List.of();
        }

        List<PurviewGovernanceBulkItemView> items = new ArrayList<>();
        Set<String> uniqueObjectIds = new LinkedHashSet<>();
        for (String objectId : objectIds) {
            if (objectId != null && !objectId.isBlank()) {
                uniqueObjectIds.add(objectId.trim());
            }
        }

        for (String objectId : uniqueObjectIds) {
            try {
                PurviewGovernanceView governance = getGovernance(repositoryId, objectId, callContext);
                items.add(new PurviewGovernanceBulkItemView(
                        objectId,
                        "OK",
                        governance.getMessage(),
                        governance));
            } catch (CmisObjectNotFoundException e) {
                items.add(new PurviewGovernanceBulkItemView(objectId, "NOT_FOUND", "Object not found", null));
            } catch (org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException e) {
                items.add(new PurviewGovernanceBulkItemView(objectId, "FORBIDDEN", "Permission denied", null));
            } catch (RuntimeException e) {
                items.add(new PurviewGovernanceBulkItemView(
                        objectId,
                        "ERROR",
                        e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage(),
                        null));
            }
        }
        return List.copyOf(items);
    }

    private PurviewGovernanceView buildFoundView(
            String repositoryId,
            String objectId,
            GovernanceTarget target,
            String atlasBasePath,
            Map<String, Object> response) {
        Map<String, Object> entity = extractEntity(response);
        return new PurviewGovernanceView(
                true,
                true,
                true,
                true,
                repositoryId,
                objectId,
                target.objectBaseType(),
                stringValue(entity.getOrDefault("typeName", target.entityTypeName())),
                target.qualifiedName(),
                atlasBasePath,
                "Purview governance metadata loaded",
                normalizeClassifications(entity.get("classifications")),
                normalizeGlossaryTerms(entity.get("meanings")),
                normalizeLabels(entity.get("labels")),
                normalizeBusinessMetadata(entity.get("businessAttributes")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractEntity(Map<String, Object> response) {
        Object entity = response.get("entity");
        if (entity instanceof Map<?, ?> entityMap) {
            return (Map<String, Object>) entityMap;
        }
        return response;
    }

    private List<Map<String, Object>> normalizeClassifications(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> classifications = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> classification = new LinkedHashMap<>();
            classification.put("typeName", stringValue(map.get("typeName")));
            classification.put("entityStatus", stringValue(map.get("entityStatus")));
            classifications.add(classification);
        }
        return List.copyOf(classifications);
    }

    private List<Map<String, Object>> normalizeGlossaryTerms(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> glossaryTerms = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> term = new LinkedHashMap<>();
            term.put("displayText", stringValue(map.get("displayText")));
            term.put("termGuid", stringValue(map.get("termGuid")));
            term.put("relationGuid", stringValue(map.get("relationGuid")));
            term.put("status", stringValue(map.get("status")));
            glossaryTerms.add(term);
        }
        return List.copyOf(glossaryTerms);
    }

    private List<String> normalizeLabels(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (Object item : list) {
            String label = stringValue(item);
            if (!label.isBlank()) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    private Map<String, Map<String, Object>> normalizeBusinessMetadata(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> businessMetadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> valueMap)) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            valueMap.forEach((key, attributeValue) -> attributes.put(String.valueOf(key), attributeValue));
            businessMetadata.put(String.valueOf(entry.getKey()), Map.copyOf(attributes));
        }
        return Map.copyOf(businessMetadata);
    }

    private GovernanceTarget resolveTarget(String repositoryId, Content content) {
        if (Boolean.TRUE.equals(content.isDocument())) {
            return new GovernanceTarget(
                    true,
                    "cmis:document",
                    DOCUMENT_ENTITY_TYPE,
                    entityPayloadFactory.buildObjectQualifiedName(repositoryId, content.getId()));
        }
        if (Boolean.TRUE.equals(content.isFolder())) {
            return new GovernanceTarget(
                    true,
                    "cmis:folder",
                    FOLDER_ENTITY_TYPE,
                    entityPayloadFactory.buildObjectQualifiedName(repositoryId, content.getId()));
        }
        return new GovernanceTarget(false, stringValue(content.getType()), null, null);
    }

    private PurviewConnectionRequest buildConnectionRequest(String atlasBasePath) {
        return new PurviewConnectionRequest(
                purviewConfig.getEndpoint(),
                atlasBasePath,
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }

    private List<String> buildCandidateBasePaths(String configuredBasePath) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(configuredBasePath);
        candidates.add(PurviewConfig.DEFAULT_ATLAS_BASE_PATH);
        candidates.add(PurviewConnectionServiceImpl.ALTERNATE_ATLAS_BASE_PATH);
        return new ArrayList<>(candidates);
    }

    private List<String> getMissingConfiguration() {
        List<String> missing = new ArrayList<>();
        if (isBlank(purviewConfig.getEndpoint())) {
            missing.add("endpoint");
        }
        if (isBlank(purviewConfig.getTenantId())) {
            missing.add("tenantId");
        }
        if (isBlank(purviewConfig.getClientId())) {
            missing.add("clientId");
        }
        if (isBlank(purviewConfig.getClientSecret())) {
            missing.add("clientSecret");
        }
        return missing;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record GovernanceTarget(
            boolean supportedObjectType,
            String objectBaseType,
            String entityTypeName,
            String qualifiedName) {
    }
}
