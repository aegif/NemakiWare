package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmFunction;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.EntityCollectionProcessor;
import org.apache.olingo.server.api.processor.EntityProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceFunction;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.cmis.service.VersioningService;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * OData Function Processor for CMIS operations.
 * 
 * Handles OData functions for CMIS objects:
 * - GetAllVersions: Get all versions of a document (bound to Document)
 * - GetObjectByPath: Get an object by its path (unbound)
 * - Query: Execute a CMIS query (unbound)
 * 
 * Functions are invoked via GET requests:
 * GET /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.GetAllVersions()
 * GET /odata/{repositoryId}/GetObjectByPath(path='/folder/document.pdf')
 * GET /odata/{repositoryId}/Query(statement='SELECT * FROM cmis:document')
 */
public class CmisFunctionProcessor implements EntityCollectionProcessor, EntityProcessor {
    
    private OData odata;
    private ServiceMetadata serviceMetadata;
    
    private final RepositoryService repositoryService;
    private final ObjectService objectService;
    private final NavigationService navigationService;
    private final DiscoveryService discoveryService;
    private final VersioningService versioningService;
    private final String repositoryId;
    private final CallContext callContext;
    
    // Function names
    public static final String FUNCTION_GET_ALL_VERSIONS = "GetAllVersions";
    public static final String FUNCTION_GET_OBJECT_BY_PATH = "GetObjectByPath";
    public static final String FUNCTION_QUERY = "Query";
    
    public CmisFunctionProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            NavigationService navigationService,
            DiscoveryService discoveryService,
            VersioningService versioningService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
        this.navigationService = navigationService;
        this.discoveryService = discoveryService;
        this.versioningService = versioningService;
        this.repositoryId = repositoryId;
        this.callContext = callContext;
    }
    
    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }
    
    @Override
    public void readEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType responseFormat) throws ODataApplicationException, SerializerException {
        
        // Check if this is a function call
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceFunction uriResourceFunction = null;
        String objectId = null;
        
        for (UriResource resource : resourcePaths) {
            if (resource instanceof UriResourceEntitySet) {
                UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resource;
                List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
                objectId = getKeyValue(keyPredicates, "objectId");
            } else if (resource instanceof UriResourceFunction) {
                uriResourceFunction = (UriResourceFunction) resource;
            }
        }
        
        if (uriResourceFunction == null) {
            throw new ODataApplicationException(
                    "Function not found in URI",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        EdmFunction function = uriResourceFunction.getFunction();
        String functionName = function.getName();
        String baseUri = request.getRawBaseUri();
        
        try {
            EntityCollection entityCollection;
            String entitySetName;
            EdmEntityType edmEntityType;
            
            switch (functionName) {
                case FUNCTION_GET_ALL_VERSIONS:
                    entityCollection = executeGetAllVersions(objectId, baseUri);
                    entitySetName = "Documents";
                    edmEntityType = serviceMetadata.getEdm().getEntityType(CmisEdmProvider.ET_DOCUMENT_FQN);
                    break;
                    
                case FUNCTION_QUERY:
                    entityCollection = executeQuery(uriResourceFunction, baseUri);
                    entitySetName = "Objects";
                    edmEntityType = serviceMetadata.getEdm().getEntityType(CmisEdmProvider.ET_OBJECT_FQN);
                    break;
                    
                default:
                    throw new ODataApplicationException(
                            "Unknown function: " + functionName,
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
            }
            
            // Serialize the response
            ODataSerializer serializer = odata.createSerializer(responseFormat);
            EdmEntitySet edmEntitySet = serviceMetadata.getEdm()
                    .getEntityContainer().getEntitySet(entitySetName);
            
            ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).build();
            EntityCollectionSerializerOptions opts = EntityCollectionSerializerOptions.with()
                    .contextURL(contextUrl)
                    .build();
            
            SerializerResult serializerResult = serializer.entityCollection(
                    serviceMetadata, edmEntityType, entityCollection, opts);
            
            response.setContent(serializerResult.getContent());
            response.setStatusCode(HttpStatusCode.OK.getStatusCode());
            response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
            
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
                    "Error executing function " + functionName + ": " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    @Override
    public void readEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType responseFormat) throws ODataApplicationException, SerializerException {
        
        // Check if this is a function call
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceFunction uriResourceFunction = null;
        
        for (UriResource resource : resourcePaths) {
            if (resource instanceof UriResourceFunction) {
                uriResourceFunction = (UriResourceFunction) resource;
            }
        }
        
        if (uriResourceFunction == null) {
            throw new ODataApplicationException(
                    "Function not found in URI",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        EdmFunction function = uriResourceFunction.getFunction();
        String functionName = function.getName();
        String baseUri = request.getRawBaseUri();
        
        try {
            Entity entity;
            String entitySetName;
            EdmEntityType edmEntityType;
            
            switch (functionName) {
                case FUNCTION_GET_OBJECT_BY_PATH:
                    entity = executeGetObjectByPath(uriResourceFunction, baseUri);
                    entitySetName = "Objects";
                    edmEntityType = serviceMetadata.getEdm().getEntityType(CmisEdmProvider.ET_OBJECT_FQN);
                    break;
                    
                default:
                    throw new ODataApplicationException(
                            "Unknown function: " + functionName,
                            HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                            Locale.ENGLISH
                    );
            }
            
            if (entity == null) {
                throw new ODataApplicationException(
                        "Object not found",
                        HttpStatusCode.NOT_FOUND.getStatusCode(),
                        Locale.ENGLISH
                );
            }
            
            // Serialize the response
            ODataSerializer serializer = odata.createSerializer(responseFormat);
            EdmEntitySet edmEntitySet = serviceMetadata.getEdm()
                    .getEntityContainer().getEntitySet(entitySetName);
            
            ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
            EntitySerializerOptions opts = EntitySerializerOptions.with()
                    .contextURL(contextUrl)
                    .build();
            
            SerializerResult serializerResult = serializer.entity(
                    serviceMetadata, edmEntityType, entity, opts);
            
            response.setContent(serializerResult.getContent());
            response.setStatusCode(HttpStatusCode.OK.getStatusCode());
            response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
            
        } catch (ODataApplicationException e) {
            throw e;
        } catch (CmisObjectNotFoundException e) {
            throw new ODataApplicationException(
                    "Object not found",
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error executing function " + functionName + ": " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    @Override
    public void createEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        throw new ODataApplicationException(
                "Create not supported for functions",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ENGLISH
        );
    }
    
    @Override
    public void updateEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
            ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        throw new ODataApplicationException(
                "Update not supported for functions",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ENGLISH
        );
    }
    
    @Override
    public void deleteEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException {
        throw new ODataApplicationException(
                "Delete not supported for functions",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ENGLISH
        );
    }
    
    /**
     * Execute GetAllVersions function.
     * Returns all versions of a document.
     */
    private EntityCollection executeGetAllVersions(String objectId, String baseUri) throws Exception {
        if (objectId == null) {
            throw new ODataApplicationException(
                    "GetAllVersions function requires a document ID",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Get all versions of the document
        List<ObjectData> versions = versioningService.getAllVersions(
                callContext,
                repositoryId,
                objectId,
                null,  // versionSeriesId (will be derived from objectId)
                "*",   // filter - all properties
                Boolean.FALSE,  // includeAllowableActions
                null   // extension
        );
        
        EntityCollection entityCollection = new EntityCollection();
        if (versions != null) {
            for (ObjectData version : versions) {
                Entity entity = convertToEntity(version, baseUri, "Documents");
                entityCollection.getEntities().add(entity);
            }
        }
        
        return entityCollection;
    }
    
    /**
     * Execute GetObjectByPath function.
     * Returns the object at the specified path.
     */
    private Entity executeGetObjectByPath(UriResourceFunction uriResourceFunction, String baseUri) throws Exception {
        // Get path parameter
        String path = null;
        List<UriParameter> parameters = uriResourceFunction.getParameters();
        for (UriParameter param : parameters) {
            if ("path".equals(param.getName())) {
                path = param.getText();
                // Remove quotes if present
                if (path != null && path.startsWith("'") && path.endsWith("'")) {
                    path = path.substring(1, path.length() - 1);
                }
                break;
            }
        }
        
        if (path == null || path.isEmpty()) {
            throw new ODataApplicationException(
                    "GetObjectByPath function requires path parameter",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Get object by path
        ObjectData objectData = objectService.getObjectByPath(
                callContext,
                repositoryId,
                path,
                "*",   // filter - all properties
                Boolean.TRUE,  // includeAllowableActions
                IncludeRelationships.NONE,
                null,  // renditionFilter
                Boolean.FALSE,  // includePolicyIds
                Boolean.FALSE,  // includeAcl
                null   // extension
        );
        
        // Determine entity set name based on base type
        String entitySetName = "Objects";
        String baseTypeId = getPropertyValue(objectData, "cmis:baseTypeId");
        if ("cmis:document".equals(baseTypeId)) {
            entitySetName = "Documents";
        } else if ("cmis:folder".equals(baseTypeId)) {
            entitySetName = "Folders";
        }
        
        return convertToEntity(objectData, baseUri, entitySetName);
    }
    
    /**
     * Execute Query function.
     * Executes a CMIS query and returns the results.
     */
    private EntityCollection executeQuery(UriResourceFunction uriResourceFunction, String baseUri) throws Exception {
        // Get parameters
        String statement = null;
        Boolean searchAllVersions = Boolean.FALSE;
        BigInteger maxItems = null;
        BigInteger skipCount = null;
        
        List<UriParameter> parameters = uriResourceFunction.getParameters();
        for (UriParameter param : parameters) {
            String paramName = param.getName();
            String paramValue = param.getText();
            
            // Remove quotes if present
            if (paramValue != null && paramValue.startsWith("'") && paramValue.endsWith("'")) {
                paramValue = paramValue.substring(1, paramValue.length() - 1);
            }
            
            switch (paramName) {
                case "statement":
                    statement = paramValue;
                    break;
                case "searchAllVersions":
                    searchAllVersions = Boolean.parseBoolean(paramValue);
                    break;
                case "maxItems":
                    if (paramValue != null && !paramValue.isEmpty()) {
                        maxItems = new BigInteger(paramValue);
                    }
                    break;
                case "skipCount":
                    if (paramValue != null && !paramValue.isEmpty()) {
                        skipCount = new BigInteger(paramValue);
                    }
                    break;
            }
        }
        
        if (statement == null || statement.isEmpty()) {
            throw new ODataApplicationException(
                    "Query function requires statement parameter",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Execute CMIS query
        ObjectList queryResult = discoveryService.query(
                callContext,
                repositoryId,
                statement,
                searchAllVersions,
                Boolean.TRUE,  // includeAllowableActions
                IncludeRelationships.NONE,
                null,  // renditionFilter
                maxItems,
                skipCount,
                null   // extension
        );
        
        EntityCollection entityCollection = new EntityCollection();
        if (queryResult != null && queryResult.getObjects() != null) {
            for (ObjectData objectData : queryResult.getObjects()) {
                // Determine entity set name based on base type
                String entitySetName = "Objects";
                String baseTypeId = getPropertyValue(objectData, "cmis:baseTypeId");
                if ("cmis:document".equals(baseTypeId)) {
                    entitySetName = "Documents";
                } else if ("cmis:folder".equals(baseTypeId)) {
                    entitySetName = "Folders";
                }
                
                Entity entity = convertToEntity(objectData, baseUri, entitySetName);
                entityCollection.getEntities().add(entity);
            }
        }
        
        // Set count if available
        if (queryResult != null && queryResult.getNumItems() != null) {
            entityCollection.setCount(queryResult.getNumItems().intValue());
        }
        
        return entityCollection;
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
        String objectIdValue = getPropertyValue(objectData, "cmis:objectId");
        if (objectIdValue != null && baseUri != null && entitySetName != null) {
            String encodedId = encodeODataKeyValue(objectIdValue);
            String absoluteUri = baseUri + "/" + entitySetName + "('" + encodedId + "')";
            entity.setId(URI.create(absoluteUri));
        } else if (objectIdValue != null) {
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
     */
    private String encodeODataKeyValue(String value) {
        if (value == null) {
            return null;
        }
        
        String encoded;
        try {
            encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            encoded = value;
        }
        
        encoded = encoded.replace("+", "%20");
        encoded = encoded.replace("%27", "''");
        
        return encoded;
    }
}
