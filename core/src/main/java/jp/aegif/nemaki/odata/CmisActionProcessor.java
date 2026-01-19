package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Parameter;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmAction;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.deserializer.DeserializerException;
import org.apache.olingo.server.api.processor.ActionEntityProcessor;
import org.apache.olingo.server.api.processor.ActionVoidProcessor;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceAction;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;

import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.cmis.service.VersioningService;

import org.apache.chemistry.opencmis.commons.data.ObjectParentData;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OData Action Processor for CMIS operations.
 * 
 * Handles OData bound actions for CMIS objects:
 * - CheckOut: Check out a document for editing
 * - CancelCheckOut: Cancel a check out operation
 * - CheckIn: Check in a document with optional new version
 * - Move: Move an object to a different folder
 * 
 * Actions are invoked via POST requests to:
 * POST /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.CheckOut
 */
public class CmisActionProcessor implements ActionEntityProcessor, ActionVoidProcessor {
    
    private OData odata;
    private ServiceMetadata serviceMetadata;
    
    private final RepositoryService repositoryService;
    private final ObjectService objectService;
    private final VersioningService versioningService;
    private final NavigationService navigationService;
    private final String repositoryId;
    private final CallContext callContext;
    
    // Action names
    public static final String ACTION_CHECKOUT = "CheckOut";
    public static final String ACTION_CANCEL_CHECKOUT = "CancelCheckOut";
    public static final String ACTION_CHECKIN = "CheckIn";
    public static final String ACTION_MOVE = "Move";
    
    public CmisActionProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            VersioningService versioningService,
            NavigationService navigationService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
        this.versioningService = versioningService;
        this.navigationService = navigationService;
        this.repositoryId = repositoryId;
        this.callContext = callContext;
    }
    
    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }
    
    @Override
    public void processActionEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, DeserializerException, SerializerException {
        
        // Get the action and bound entity from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        
        // Find the entity set and action
        UriResourceEntitySet uriResourceEntitySet = null;
        UriResourceAction uriResourceAction = null;
        String objectId = null;
        
        for (UriResource resource : resourcePaths) {
            if (resource instanceof UriResourceEntitySet) {
                uriResourceEntitySet = (UriResourceEntitySet) resource;
                List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
                objectId = getKeyValue(keyPredicates, "objectId");
            } else if (resource instanceof UriResourceAction) {
                uriResourceAction = (UriResourceAction) resource;
            }
        }
        
        if (uriResourceAction == null) {
            throw new ODataApplicationException(
                    "Action not found in URI",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        EdmAction action = uriResourceAction.getAction();
        String actionName = action.getName();
        
        // Parse action parameters from request body
        Map<String, Parameter> parameters = null;
        if (requestFormat != null && request.getBody() != null) {
            try {
                parameters = odata.createDeserializer(requestFormat)
                        .actionParameters(request.getBody(), action).getActionParameters();
            } catch (DeserializerException e) {
                throw new ODataApplicationException(
                        "Invalid action parameters: " + e.getMessage(),
                        HttpStatusCode.BAD_REQUEST.getStatusCode(),
                        Locale.ENGLISH,
                        e
                );
            }
        }
        
        // Execute the action
        Entity resultEntity = executeAction(actionName, objectId, parameters, request);
        
        if (resultEntity != null) {
            // Serialize the response
            ODataSerializer serializer = odata.createSerializer(responseFormat);
            EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
            ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
            
            EntitySerializerOptions opts = EntitySerializerOptions.with()
                    .contextURL(contextUrl)
                    .build();
            
            SerializerResult serializerResult = serializer.entity(serviceMetadata, 
                    edmEntitySet.getEntityType(), resultEntity, opts);
            
            response.setContent(serializerResult.getContent());
            response.setStatusCode(HttpStatusCode.OK.getStatusCode());
            response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
        } else {
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
        }
    }
    
    @Override
    public void processActionVoid(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType requestFormat) throws ODataApplicationException, DeserializerException {
        
        // Get the action and bound entity from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        
        // Find the entity set and action
        UriResourceAction uriResourceAction = null;
        String objectId = null;
        
        for (UriResource resource : resourcePaths) {
            if (resource instanceof UriResourceEntitySet) {
                UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resource;
                List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
                objectId = getKeyValue(keyPredicates, "objectId");
            } else if (resource instanceof UriResourceAction) {
                uriResourceAction = (UriResourceAction) resource;
            }
        }
        
        if (uriResourceAction == null) {
            throw new ODataApplicationException(
                    "Action not found in URI",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        EdmAction action = uriResourceAction.getAction();
        String actionName = action.getName();
        
        // Parse action parameters from request body
        Map<String, Parameter> parameters = null;
        if (requestFormat != null && request.getBody() != null) {
            try {
                parameters = odata.createDeserializer(requestFormat)
                        .actionParameters(request.getBody(), action).getActionParameters();
            } catch (DeserializerException e) {
                throw new ODataApplicationException(
                        "Invalid action parameters: " + e.getMessage(),
                        HttpStatusCode.BAD_REQUEST.getStatusCode(),
                        Locale.ENGLISH,
                        e
                );
            }
        }
        
        // Execute the action (void return)
        executeAction(actionName, objectId, parameters, request);
        
        response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
    }
    
    /**
     * Execute the specified action on the object.
     */
    private Entity executeAction(String actionName, String objectId, Map<String, Parameter> parameters, ODataRequest request)
            throws ODataApplicationException {
        
        if (objectId == null) {
            throw new ODataApplicationException(
                    "Object ID is required for action: " + actionName,
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        String baseUri = request.getRawBaseUri();
        
        try {
            switch (actionName) {
                case ACTION_CHECKOUT:
                    return executeCheckOut(objectId, baseUri);
                    
                case ACTION_CANCEL_CHECKOUT:
                    executeCancelCheckOut(objectId);
                    return null;
                    
                case ACTION_CHECKIN:
                    return executeCheckIn(objectId, parameters, baseUri);
                    
                case ACTION_MOVE:
                    return executeMove(objectId, parameters, baseUri);
                    
                default:
                    throw new ODataApplicationException(
                            "Unknown action: " + actionName,
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
            }
        } catch (ODataApplicationException e) {
            throw e;
        } catch (CmisObjectNotFoundException e) {
            throw new ODataApplicationException(
                    "Object not found: " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error executing action " + actionName + ": " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    /**
     * Execute CheckOut action.
     * Returns the Private Working Copy (PWC) document.
     */
    private Entity executeCheckOut(String objectId, String baseUri) throws Exception {
        Holder<String> objectIdHolder = new Holder<>(objectId);
        Holder<Boolean> contentCopiedHolder = new Holder<>();
        
        versioningService.checkOut(
                callContext,
                repositoryId,
                objectIdHolder,
                contentCopiedHolder,
                null  // extension
        );
        
        // Get the PWC (the objectIdHolder now contains the PWC ID)
        String pwcId = objectIdHolder.getValue();
        ObjectData pwcData = objectService.getObject(
                callContext, repositoryId, pwcId, "*",
                Boolean.TRUE, IncludeRelationships.NONE, null,
                Boolean.FALSE, Boolean.FALSE, null
        );
        
        return convertToEntity(pwcData, baseUri, "Documents");
    }
    
    /**
     * Execute CancelCheckOut action.
     */
    private void executeCancelCheckOut(String objectId) throws Exception {
        versioningService.cancelCheckOut(
                callContext,
                repositoryId,
                objectId,
                null  // extension
        );
    }
    
    /**
     * Execute CheckIn action.
     * Parameters:
     * - major: boolean (default true)
     * - checkinComment: string (optional)
     */
    private Entity executeCheckIn(String objectId, Map<String, Parameter> parameters, String baseUri) throws Exception {
        Boolean major = Boolean.TRUE;
        String checkinComment = null;
        
        if (parameters != null) {
            Parameter majorParam = parameters.get("major");
            if (majorParam != null && majorParam.getValue() != null) {
                major = (Boolean) majorParam.getValue();
            }
            
            Parameter commentParam = parameters.get("checkinComment");
            if (commentParam != null && commentParam.getValue() != null) {
                checkinComment = (String) commentParam.getValue();
            }
        }
        
        Holder<String> objectIdHolder = new Holder<>(objectId);
        
        versioningService.checkIn(
                callContext,
                repositoryId,
                objectIdHolder,
                major,
                null,  // properties
                null,  // contentStream
                checkinComment,
                null,  // policies
                null,  // addAces
                null,  // removeAces
                null   // extension
        );
        
        // Get the new version (objectIdHolder now contains the new version ID)
        String newVersionId = objectIdHolder.getValue();
        ObjectData newVersionData = objectService.getObject(
                callContext, repositoryId, newVersionId, "*",
                Boolean.TRUE, IncludeRelationships.NONE, null,
                Boolean.FALSE, Boolean.FALSE, null
        );
        
        return convertToEntity(newVersionData, baseUri, "Documents");
    }
    
    /**
     * Execute Move action.
     * Parameters:
     * - targetFolderId: string (required)
     * - sourceFolderId: string (optional, uses current parent if not specified)
     */
    private Entity executeMove(String objectId, Map<String, Parameter> parameters, String baseUri) throws Exception {
        if (parameters == null) {
            throw new ODataApplicationException(
                    "Move action requires targetFolderId parameter",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        Parameter targetFolderParam = parameters.get("targetFolderId");
        if (targetFolderParam == null || targetFolderParam.getValue() == null) {
            throw new ODataApplicationException(
                    "Move action requires targetFolderId parameter",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        String targetFolderId = (String) targetFolderParam.getValue();
        
        // Get source folder ID (optional, use current parent if not specified)
        String sourceFolderId = null;
        Parameter sourceFolderParam = parameters.get("sourceFolderId");
        if (sourceFolderParam != null && sourceFolderParam.getValue() != null) {
            sourceFolderId = (String) sourceFolderParam.getValue();
        } else {
            // Get current parent using navigationService.getObjectParents()
            // This handles multi-filed objects and unfiled objects properly
            List<ObjectParentData> parents = navigationService.getObjectParents(
                    callContext,
                    repositoryId,
                    objectId,
                    "cmis:objectId",  // filter - only need the ID
                    Boolean.FALSE,  // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,  // renditionFilter
                    Boolean.FALSE,  // includeRelativePathSegment
                    null   // extension
            );
            
            if (parents != null && !parents.isEmpty()) {
                ObjectParentData firstParent = parents.get(0);
                if (firstParent.getObject() != null && firstParent.getObject().getProperties() != null) {
                    org.apache.chemistry.opencmis.commons.data.PropertyData<?> parentIdProp = 
                            firstParent.getObject().getProperties().getProperties().get("cmis:objectId");
                    if (parentIdProp != null && parentIdProp.getFirstValue() != null) {
                        sourceFolderId = (String) parentIdProp.getFirstValue();
                    }
                }
            }
        }
        
        if (sourceFolderId == null) {
            throw new ODataApplicationException(
                    "Could not determine source folder for move operation. " +
                    "The object may be unfiled or have no accessible parent folders.",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Execute move
        Holder<String> objectIdHolder = new Holder<>(objectId);
        objectService.moveObject(
                callContext,
                repositoryId,
                objectIdHolder,
                sourceFolderId,
                targetFolderId,
                null  // extension
        );
        
        // Get the moved object and determine entity set based on base type
        ObjectData movedObject = objectService.getObject(
                callContext, repositoryId, objectIdHolder.getValue(), "*",
                Boolean.TRUE, IncludeRelationships.NONE, null,
                Boolean.FALSE, Boolean.FALSE, null
        );
        
        // Determine entity set name based on base type
        String entitySetName = "Objects";
        String baseTypeId = getPropertyValue(movedObject, "cmis:baseTypeId");
        if ("cmis:document".equals(baseTypeId)) {
            entitySetName = "Documents";
        } else if ("cmis:folder".equals(baseTypeId)) {
            entitySetName = "Folders";
        }
        
        return convertToEntity(movedObject, baseUri, entitySetName);
    }
    
    /**
     * Get the value of a key parameter from the URI.
     */
    private String getKeyValue(List<UriParameter> keyPredicates, String keyName) {
        if (keyPredicates == null) {
            return null;
        }
        for (UriParameter param : keyPredicates) {
            if (param.getName().equals(keyName)) {
                String value = param.getText();
                // Remove quotes if present
                if (value != null && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }
    
    /**
     * Convert CMIS ObjectData to OData Entity with proper absolute URI for @odata.id.
     * 
     * @param objectData The CMIS object data
     * @param baseUri The base URI from the OData request (e.g., "http://localhost:8080/core/odata/bedroom")
     * @param entitySetName The entity set name (e.g., "Documents", "Folders")
     * @return The OData Entity with proper @odata.id
     */
    private Entity convertToEntity(ObjectData objectData, String baseUri, String entitySetName) {
        Entity entity = new Entity();
        
        if (objectData.getProperties() != null) {
            for (org.apache.chemistry.opencmis.commons.data.PropertyData<?> propertyData : 
                    objectData.getProperties().getPropertyList()) {
                String propertyId = propertyData.getId();
                Object value = propertyData.getFirstValue();
                
                String odataPropertyName = mapPropertyName(propertyId);
                if (odataPropertyName != null && value != null) {
                    Property property = convertProperty(odataPropertyName, value);
                    if (property != null) {
                        entity.addProperty(property);
                    }
                }
            }
        }
        
        // Set the entity ID as absolute URI (OData spec requirement)
        // Format: {baseUri}/{EntitySet}('{objectId}')
        String objectIdValue = getPropertyValue(objectData, "cmis:objectId");
        if (objectIdValue != null && baseUri != null && entitySetName != null) {
            // Encode the object ID for URL safety (handles /, ?, #, &, =, etc.)
            String encodedId = encodeODataKeyValue(objectIdValue);
            String absoluteUri = baseUri + "/" + entitySetName + "('" + encodedId + "')";
            entity.setId(URI.create(absoluteUri));
        } else if (objectIdValue != null) {
            // Fallback to just the object ID if base URI is not available
            entity.setId(URI.create(encodeODataKeyValue(objectIdValue)));
        }
        
        return entity;
    }
    
    /**
     * Map CMIS property ID to OData property name.
     */
    private String mapPropertyName(String cmisPropertyId) {
        switch (cmisPropertyId) {
            case "cmis:objectId": return "objectId";
            case "cmis:objectTypeId": return "objectTypeId";
            case "cmis:baseTypeId": return "baseTypeId";
            case "cmis:name": return "name";
            case "cmis:description": return "description";
            case "cmis:createdBy": return "createdBy";
            case "cmis:creationDate": return "creationDate";
            case "cmis:lastModifiedBy": return "lastModifiedBy";
            case "cmis:lastModificationDate": return "lastModificationDate";
            case "cmis:changeToken": return "changeToken";
            case "cmis:isImmutable": return "isImmutable";
            case "cmis:isLatestVersion": return "isLatestVersion";
            case "cmis:isMajorVersion": return "isMajorVersion";
            case "cmis:isLatestMajorVersion": return "isLatestMajorVersion";
            case "cmis:isPrivateWorkingCopy": return "isPrivateWorkingCopy";
            case "cmis:versionLabel": return "versionLabel";
            case "cmis:versionSeriesId": return "versionSeriesId";
            case "cmis:isVersionSeriesCheckedOut": return "isVersionSeriesCheckedOut";
            case "cmis:versionSeriesCheckedOutBy": return "versionSeriesCheckedOutBy";
            case "cmis:versionSeriesCheckedOutId": return "versionSeriesCheckedOutId";
            case "cmis:checkinComment": return "checkinComment";
            case "cmis:contentStreamLength": return "contentStreamLength";
            case "cmis:contentStreamMimeType": return "contentStreamMimeType";
            case "cmis:contentStreamFileName": return "contentStreamFileName";
            case "cmis:contentStreamId": return "contentStreamId";
            case "cmis:parentId": return "parentId";
            case "cmis:path": return "path";
            default: return null;
        }
    }
    
    /**
     * Convert a CMIS property value to an OData Property.
     */
    private Property convertProperty(String name, Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof String) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof Boolean) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof Number) {
            return new Property(null, name, ValueType.PRIMITIVE, value);
        } else if (value instanceof GregorianCalendar) {
            return new Property(null, name, ValueType.PRIMITIVE, ((GregorianCalendar) value).getTime());
        } else {
            return new Property(null, name, ValueType.PRIMITIVE, value.toString());
        }
    }
    
    /**
     * Get a string property value from ObjectData.
     */
    private String getPropertyValue(ObjectData objectData, String propertyId) {
        if (objectData.getProperties() != null) {
            org.apache.chemistry.opencmis.commons.data.PropertyData<?> propertyData = 
                    objectData.getProperties().getProperties().get(propertyId);
            if (propertyData != null && propertyData.getFirstValue() != null) {
                return propertyData.getFirstValue().toString();
            }
        }
        return null;
    }
    
    /**
     * Encode a value for use in OData entity key (inside single quotes).
     * 
     * This handles URI reserved characters that could break the URI structure:
     * - Single quotes are doubled (' -> '')
     * - Other reserved characters (/, ?, #, &, =, etc.) are URL-encoded
     * 
     * @param value The raw value to encode
     * @return The encoded value safe for use in OData entity key
     */
    private String encodeODataKeyValue(String value) {
        if (value == null) {
            return null;
        }
        
        // First, URL-encode the value to handle reserved characters
        String encoded;
        try {
            encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported, but handle the exception anyway
            encoded = value;
        }
        
        // URL encoding converts spaces to '+', but OData expects %20
        encoded = encoded.replace("+", "%20");
        
        // Single quotes need to be doubled for OData string literals
        // Note: URLEncoder encodes ' as %27, but OData expects '' for escaping
        encoded = encoded.replace("%27", "''");
        
        return encoded;
    }
}
