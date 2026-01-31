package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.EntityProcessor;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;

import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * OData Entity Processor for CMIS objects.
 * 
 * Handles requests for single entities (e.g., /Documents('id')).
 * Supports read, create, update, and delete operations.
 */
public class CmisEntityProcessor implements EntityProcessor {
    
    private OData odata;
    private ServiceMetadata serviceMetadata;
    
    private final RepositoryService repositoryService;
    private final ObjectService objectService;
    private final NavigationService navigationService;
    private final String repositoryId;
    private final CallContext callContext;
    
    public CmisEntityProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            NavigationService navigationService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
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
    public void readEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Get the entity set and key from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
        
        // Get the key parameter (objectId)
        List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
        String objectId = getKeyValue(keyPredicates, "objectId");
        
        if (objectId == null) {
            throw new ODataApplicationException(
                    "Missing key parameter: objectId",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Fetch the ObjectData from CMIS
        ObjectData objectData = getObjectData(objectId);
        
        if (objectData == null) {
            throw new ODataApplicationException(
                    "Entity not found: " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Convert to OData entity with absolute URI for @odata.id
        String baseUri = request.getRawBaseUri();
        String entitySetName = edmEntitySet.getName();
        Entity entity = convertToEntity(objectData, baseUri, entitySetName);
        
        // Handle $expand for navigation properties
        ExpandOption expandOption = uriInfo.getExpandOption();
        if (expandOption != null) {
            java.util.Set<String> expandProperties = getExpandProperties(expandOption);
            if (!expandProperties.isEmpty()) {
                expandNavigationProperties(entity, objectData, expandProperties);
            }
        }
        
        // Serialize the response
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        
        EdmEntityType edmEntityType = edmEntitySet.getEntityType();
        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
        
        EntitySerializerOptions opts = EntitySerializerOptions.with()
                .contextURL(contextUrl)
                .expand(expandOption)
                .build();
        
        SerializerResult serializerResult = serializer.entity(serviceMetadata, edmEntityType, entity, opts);
        
        response.setContent(serializerResult.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }
    
    @Override
    public void createEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Get the entity set from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
        String entitySetName = edmEntitySet.getName();
        
        try {
            // Deserialize the request body
            InputStream requestInputStream = request.getBody();
            Entity requestEntity = odata.createDeserializer(requestFormat)
                    .entity(requestInputStream, edmEntitySet.getEntityType()).getEntity();
            
            // Convert OData entity to CMIS properties
            Properties cmisProperties = convertEntityToProperties(requestEntity, entitySetName);
            
            // Get parent folder ID from properties or use root folder
            String parentId = getParentIdFromEntity(requestEntity);
            if (parentId == null) {
                // Use root folder as default parent
                parentId = repositoryService.getRepositoryInfo(repositoryId)
                        .getRootFolderId();
            }
            
            // Create the object based on entity set type
            String createdObjectId;
            if (CmisEdmProvider.ES_DOCUMENTS_NAME.equals(entitySetName)) {
                createdObjectId = objectService.createDocument(
                        callContext, repositoryId, cmisProperties, parentId,
                        null, VersioningState.MAJOR, null, null, null, null);
            } else if (CmisEdmProvider.ES_FOLDERS_NAME.equals(entitySetName)) {
                createdObjectId = objectService.createFolder(
                        callContext, repositoryId, cmisProperties, parentId,
                        null, null, null, null);
            } else {
                throw new ODataApplicationException(
                        "Create not supported for entity set: " + entitySetName,
                        HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                        Locale.ENGLISH
                );
            }
            
            // Fetch the created entity
            Entity createdEntity = getEntity(createdObjectId);
            
            // Serialize the response
            ODataSerializer serializer = odata.createSerializer(responseFormat);
            EdmEntityType edmEntityType = edmEntitySet.getEntityType();
            ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
            
            EntitySerializerOptions opts = EntitySerializerOptions.with()
                    .contextURL(contextUrl)
                    .build();
            
            SerializerResult serializerResult = serializer.entity(serviceMetadata, edmEntityType, createdEntity, opts);
            
            response.setContent(serializerResult.getContent());
            response.setStatusCode(HttpStatusCode.CREATED.getStatusCode());
            response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
            
        } catch (ODataApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error creating entity: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    @Override
    public void updateEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Get the entity set and key from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
        String entitySetName = edmEntitySet.getName();
        
        // Get the key parameter (objectId)
        List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
        String objectId = getKeyValue(keyPredicates, "objectId");
        
        if (objectId == null) {
            throw new ODataApplicationException(
                    "Missing key parameter: objectId",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        try {
            // Deserialize the request body
            InputStream requestInputStream = request.getBody();
            Entity requestEntity = odata.createDeserializer(requestFormat)
                    .entity(requestInputStream, edmEntitySet.getEntityType()).getEntity();
            
            // Convert OData entity to CMIS properties
            Properties cmisProperties = convertEntityToProperties(requestEntity, entitySetName);
            
            // Get the current change token
            Entity existingEntity = getEntity(objectId);
            if (existingEntity == null) {
                throw new ODataApplicationException(
                        "Entity not found: " + objectId,
                        HttpStatusCode.NOT_FOUND.getStatusCode(),
                        Locale.ENGLISH
                );
            }
            
            String changeToken = null;
            Property changeTokenProp = existingEntity.getProperty("changeToken");
            if (changeTokenProp != null && changeTokenProp.getValue() != null) {
                changeToken = changeTokenProp.getValue().toString();
            }
            
            // Update the object
            Holder<String> objectIdHolder = new Holder<>(objectId);
            Holder<String> changeTokenHolder = new Holder<>(changeToken);
            
            objectService.updateProperties(
                    callContext, repositoryId, objectIdHolder, cmisProperties,
                    changeTokenHolder, null);
            
            // Fetch the updated entity
            Entity updatedEntity = getEntity(objectIdHolder.getValue());
            
            // Serialize the response
            ODataSerializer serializer = odata.createSerializer(responseFormat);
            EdmEntityType edmEntityType = edmEntitySet.getEntityType();
            ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
            
            EntitySerializerOptions opts = EntitySerializerOptions.with()
                    .contextURL(contextUrl)
                    .build();
            
            SerializerResult serializerResult = serializer.entity(serviceMetadata, edmEntityType, updatedEntity, opts);
            
            response.setContent(serializerResult.getContent());
            response.setStatusCode(HttpStatusCode.OK.getStatusCode());
            response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
            
        } catch (ODataApplicationException e) {
            throw e;
        } catch (CmisObjectNotFoundException e) {
            throw new ODataApplicationException(
                    "Entity not found: " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error updating entity: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    @Override
    public void deleteEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException {
        
        // Get the entity set and key from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        
        // Get the key parameter (objectId)
        List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
        String objectId = getKeyValue(keyPredicates, "objectId");
        
        if (objectId == null) {
            throw new ODataApplicationException(
                    "Missing key parameter: objectId",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        try {
            // Delete the object from CMIS
            objectService.deleteObject(
                    callContext,
                    repositoryId,
                    objectId,
                    Boolean.TRUE,  // allVersions
                    null           // extension
            );
            
            response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
        } catch (CmisObjectNotFoundException e) {
            throw new ODataApplicationException(
                    "Entity not found: " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error deleting entity: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    /**
     * Get an entity from CMIS by object ID.
     */
    private Entity getEntity(String objectId) throws ODataApplicationException {
        try {
            ObjectData objectData = objectService.getObject(
                    callContext,
                    repositoryId,
                    objectId,
                    "*",            // filter - all properties
                    Boolean.TRUE,   // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,           // renditionFilter
                    Boolean.FALSE,  // includePolicyIds
                    Boolean.FALSE,  // includeAcl
                    null            // extension
            );
            
            if (objectData != null) {
                return convertToEntity(objectData);
            }
            return null;
        } catch (CmisObjectNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error fetching entity: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    /**
     * Convert CMIS ObjectData to OData Entity with absolute URI for @odata.id.
     */
    private Entity convertToEntity(ObjectData objectData, String baseUri, String entitySetName) {
        Entity entity = new Entity();
        
        if (objectData.getProperties() != null) {
            for (PropertyData<?> propertyData : objectData.getProperties().getPropertyList()) {
                String propertyId = propertyData.getId();
                Object value = propertyData.getFirstValue();
                
                // Map CMIS property names to OData property names
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
     * Overloaded convertToEntity for backward compatibility (used in expand methods).
     * Uses just the object ID without absolute URI format.
     */
    private Entity convertToEntity(ObjectData objectData) {
        return convertToEntity(objectData, null, null);
    }
    
    /**
     * Map CMIS property ID to OData property name.
     */
    private String mapPropertyName(String cmisPropertyId) {
        switch (cmisPropertyId) {
            case "cmis:objectId":
                return "objectId";
            case "cmis:objectTypeId":
                return "objectTypeId";
            case "cmis:baseTypeId":
                return "baseTypeId";
            case "cmis:name":
                return "name";
            case "cmis:description":
                return "description";
            case "cmis:createdBy":
                return "createdBy";
            case "cmis:creationDate":
                return "creationDate";
            case "cmis:lastModifiedBy":
                return "lastModifiedBy";
            case "cmis:lastModificationDate":
                return "lastModificationDate";
            case "cmis:changeToken":
                return "changeToken";
            case "cmis:isImmutable":
                return "isImmutable";
            case "cmis:isLatestVersion":
                return "isLatestVersion";
            case "cmis:isMajorVersion":
                return "isMajorVersion";
            case "cmis:isLatestMajorVersion":
                return "isLatestMajorVersion";
            case "cmis:isPrivateWorkingCopy":
                return "isPrivateWorkingCopy";
            case "cmis:versionLabel":
                return "versionLabel";
            case "cmis:versionSeriesId":
                return "versionSeriesId";
            case "cmis:isVersionSeriesCheckedOut":
                return "isVersionSeriesCheckedOut";
            case "cmis:versionSeriesCheckedOutBy":
                return "versionSeriesCheckedOutBy";
            case "cmis:versionSeriesCheckedOutId":
                return "versionSeriesCheckedOutId";
            case "cmis:checkinComment":
                return "checkinComment";
            case "cmis:contentStreamLength":
                return "contentStreamLength";
            case "cmis:contentStreamMimeType":
                return "contentStreamMimeType";
            case "cmis:contentStreamFileName":
                return "contentStreamFileName";
            case "cmis:contentStreamId":
                return "contentStreamId";
            case "cmis:parentId":
                return "parentId";
            case "cmis:path":
                return "path";
            case "cmis:sourceId":
                return "sourceId";
            case "cmis:targetId":
                return "targetId";
            case "cmis:policyText":
                return "policyText";
            default:
                return null;
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
            PropertyData<?> propertyData = objectData.getProperties().getProperties().get(propertyId);
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
    
    /**
     * Get the value of a key parameter from the URI.
     */
    private String getKeyValue(List<UriParameter> keyPredicates, String keyName) {
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
        // If no specific key name found, return the first key value (for single-key entities)
        if (!keyPredicates.isEmpty()) {
            String value = keyPredicates.get(0).getText();
            if (value != null && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }
    
    /**
     * Convert OData Entity to CMIS Properties.
     */
    private Properties convertEntityToProperties(Entity entity, String entitySetName) {
        PropertiesImpl properties = new PropertiesImpl();
        
        // Determine the object type based on entity set
        String objectTypeId;
        if (CmisEdmProvider.ES_DOCUMENTS_NAME.equals(entitySetName)) {
            objectTypeId = "cmis:document";
        } else if (CmisEdmProvider.ES_FOLDERS_NAME.equals(entitySetName)) {
            objectTypeId = "cmis:folder";
        } else if (CmisEdmProvider.ES_RELATIONSHIPS_NAME.equals(entitySetName)) {
            objectTypeId = "cmis:relationship";
        } else if (CmisEdmProvider.ES_POLICIES_NAME.equals(entitySetName)) {
            objectTypeId = "cmis:policy";
        } else {
            objectTypeId = "cmis:document"; // Default
        }
        
        // Check if objectTypeId is provided in the entity
        Property objectTypeProp = entity.getProperty("objectTypeId");
        if (objectTypeProp != null && objectTypeProp.getValue() != null) {
            objectTypeId = objectTypeProp.getValue().toString();
        }
        
        // Add object type ID
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", objectTypeId));
        
        // Convert each property
        for (Property property : entity.getProperties()) {
            String propertyName = property.getName();
            Object value = property.getValue();
            
            if (value == null || "objectTypeId".equals(propertyName) || "objectId".equals(propertyName)) {
                continue; // Skip null values, objectTypeId (already added), and objectId (read-only)
            }
            
            // Map OData property name to CMIS property ID
            String cmisPropertyId = mapODataPropertyToCmis(propertyName);
            if (cmisPropertyId == null) {
                continue; // Skip unknown properties
            }
            
            // Convert value based on type
            PropertyData<?> cmisProperty = convertValueToCmisProperty(cmisPropertyId, value);
            if (cmisProperty != null) {
                properties.addProperty(cmisProperty);
            }
        }
        
        return properties;
    }
    
    /**
     * Convert a value to a CMIS PropertyData.
     */
    private PropertyData<?> convertValueToCmisProperty(String propertyId, Object value) {
        if (value == null) {
            return null;
        }
        
        // Handle different value types
        if (value instanceof String) {
            return new PropertyStringImpl(propertyId, (String) value);
        } else if (value instanceof Boolean) {
            return new PropertyBooleanImpl(propertyId, (Boolean) value);
        } else if (value instanceof Integer) {
            return new PropertyIntegerImpl(propertyId, BigInteger.valueOf((Integer) value));
        } else if (value instanceof Long) {
            return new PropertyIntegerImpl(propertyId, BigInteger.valueOf((Long) value));
        } else if (value instanceof BigInteger) {
            return new PropertyIntegerImpl(propertyId, (BigInteger) value);
        } else if (value instanceof Double) {
            return new PropertyDecimalImpl(propertyId, BigDecimal.valueOf((Double) value));
        } else if (value instanceof BigDecimal) {
            return new PropertyDecimalImpl(propertyId, (BigDecimal) value);
        } else if (value instanceof Date) {
            GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            calendar.setTime((Date) value);
            return new PropertyDateTimeImpl(propertyId, calendar);
        } else if (value instanceof GregorianCalendar) {
            return new PropertyDateTimeImpl(propertyId, (GregorianCalendar) value);
        } else {
            // Default to string
            return new PropertyStringImpl(propertyId, value.toString());
        }
    }
    
    /**
     * Get parent folder ID from entity properties.
     */
    private String getParentIdFromEntity(Entity entity) {
        Property parentIdProp = entity.getProperty("parentId");
        if (parentIdProp != null && parentIdProp.getValue() != null) {
            return parentIdProp.getValue().toString();
        }
        return null;
    }
    
    /**
     * Map OData property name to CMIS property ID.
     */
    private String mapODataPropertyToCmis(String odataProperty) {
        switch (odataProperty) {
            case "objectId":
                return "cmis:objectId";
            case "objectTypeId":
                return "cmis:objectTypeId";
            case "baseTypeId":
                return "cmis:baseTypeId";
            case "name":
                return "cmis:name";
            case "description":
                return "cmis:description";
            case "createdBy":
                return "cmis:createdBy";
            case "creationDate":
                return "cmis:creationDate";
            case "lastModifiedBy":
                return "cmis:lastModifiedBy";
            case "lastModificationDate":
                return "cmis:lastModificationDate";
            case "changeToken":
                return "cmis:changeToken";
            case "isImmutable":
                return "cmis:isImmutable";
            case "isLatestVersion":
                return "cmis:isLatestVersion";
            case "isMajorVersion":
                return "cmis:isMajorVersion";
            case "isLatestMajorVersion":
                return "cmis:isLatestMajorVersion";
            case "isPrivateWorkingCopy":
                return "cmis:isPrivateWorkingCopy";
            case "versionLabel":
                return "cmis:versionLabel";
            case "versionSeriesId":
                return "cmis:versionSeriesId";
            case "isVersionSeriesCheckedOut":
                return "cmis:isVersionSeriesCheckedOut";
            case "versionSeriesCheckedOutBy":
                return "cmis:versionSeriesCheckedOutBy";
            case "versionSeriesCheckedOutId":
                return "cmis:versionSeriesCheckedOutId";
            case "checkinComment":
                return "cmis:checkinComment";
            case "contentStreamLength":
                return "cmis:contentStreamLength";
            case "contentStreamMimeType":
                return "cmis:contentStreamMimeType";
            case "contentStreamFileName":
                return "cmis:contentStreamFileName";
            case "contentStreamId":
                return "cmis:contentStreamId";
            case "parentId":
                return "cmis:parentId";
            case "path":
                return "cmis:path";
            case "sourceId":
                return "cmis:sourceId";
            case "targetId":
                return "cmis:targetId";
            case "policyText":
                return "cmis:policyText";
            default:
                return null;
        }
    }
    
    /**
     * Get ObjectData from CMIS by object ID.
     */
    private ObjectData getObjectData(String objectId) throws ODataApplicationException {
        try {
            return objectService.getObject(
                    callContext,
                    repositoryId,
                    objectId,
                    "*",            // filter - all properties
                    Boolean.TRUE,   // includeAllowableActions
                    IncludeRelationships.NONE,
                    null,           // renditionFilter
                    Boolean.FALSE,  // includePolicyIds
                    Boolean.FALSE,  // includeAcl
                    null            // extension
            );
        } catch (CmisObjectNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error fetching entity: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
    }
    
    /**
     * Extract navigation property names from $expand option.
     */
    private java.util.Set<String> getExpandProperties(ExpandOption expandOption) {
        java.util.Set<String> expandProperties = new java.util.HashSet<>();
        if (expandOption != null && expandOption.getExpandItems() != null) {
            for (ExpandItem expandItem : expandOption.getExpandItems()) {
                if (expandItem.getResourcePath() != null && !expandItem.getResourcePath().getUriResourceParts().isEmpty()) {
                    UriResource uriResource = expandItem.getResourcePath().getUriResourceParts().get(0);
                    expandProperties.add(uriResource.getSegmentValue());
                }
            }
        }
        return expandProperties;
    }
    
    /**
     * Expand navigation properties for an entity based on $expand option.
     */
    private void expandNavigationProperties(Entity entity, ObjectData objectData, java.util.Set<String> expandProperties) {
        if (expandProperties == null || expandProperties.isEmpty()) {
            return;
        }
        
        Properties properties = objectData.getProperties();
        if (properties == null) {
            return;
        }
        
        // Get the base type to determine which navigation properties are valid
        PropertyData<?> baseTypeProperty = properties.getProperties().get("cmis:baseTypeId");
        String baseTypeId = baseTypeProperty != null ? (String) baseTypeProperty.getFirstValue() : null;
        
        // Get object ID for fetching related objects
        PropertyData<?> objectIdProperty = properties.getProperties().get("cmis:objectId");
        String objectId = objectIdProperty != null ? (String) objectIdProperty.getFirstValue() : null;
        
        if (objectId == null) {
            return;
        }
        
        try {
            // Handle 'parent' expansion (single parent folder)
            if (expandProperties.contains("parent")) {
                expandParentFolder(entity, properties);
            }
            
            // Handle 'parents' expansion (collection of parent folders)
            if (expandProperties.contains("parents")) {
                expandParentFolders(entity, objectId);
            }
            
            // Handle 'children' expansion (for folders only)
            if (expandProperties.contains("children") && "cmis:folder".equals(baseTypeId)) {
                expandChildren(entity, objectId);
            }
            
            // Handle 'source' and 'target' expansion (for relationships only)
            if ("cmis:relationship".equals(baseTypeId)) {
                if (expandProperties.contains("source")) {
                    expandRelationshipSource(entity, properties);
                }
                if (expandProperties.contains("target")) {
                    expandRelationshipTarget(entity, properties);
                }
            }
        } catch (Exception e) {
            // Log error but don't fail the entire request
        }
    }
    
    /**
     * Expand single parent folder navigation property.
     */
    private void expandParentFolder(Entity entity, Properties properties) {
        PropertyData<?> parentIdProperty = properties.getProperties().get("cmis:parentId");
        if (parentIdProperty == null || parentIdProperty.getFirstValue() == null) {
            return;
        }
        
        String parentId = (String) parentIdProperty.getFirstValue();
        
        try {
            ObjectData parentObject = objectService.getObject(
                    callContext, repositoryId, parentId, "*",
                    Boolean.FALSE, IncludeRelationships.NONE, null,
                    Boolean.FALSE, Boolean.FALSE, null
            );
            
            if (parentObject != null) {
                Entity parentEntity = convertToEntity(parentObject);
                Link parentLink = new Link();
                parentLink.setTitle("parent");
                parentLink.setType("application/json");
                parentLink.setInlineEntity(parentEntity);
                entity.getNavigationLinks().add(parentLink);
            }
        } catch (Exception e) {
            // Parent not accessible
        }
    }
    
    /**
     * Expand parent folders collection.
     */
    private void expandParentFolders(Entity entity, String objectId) {
        try {
            List<ObjectParentData> parents = navigationService.getObjectParents(
                    callContext, repositoryId, objectId, "*",
                    Boolean.FALSE, IncludeRelationships.NONE, null,
                    Boolean.FALSE, null
            );
            
            if (parents != null && !parents.isEmpty()) {
                EntityCollection parentsCollection = new EntityCollection();
                for (ObjectParentData parentData : parents) {
                    if (parentData.getObject() != null) {
                        Entity parentEntity = convertToEntity(parentData.getObject());
                        parentsCollection.getEntities().add(parentEntity);
                    }
                }
                
                Link parentsLink = new Link();
                parentsLink.setTitle("parents");
                parentsLink.setType("application/json");
                parentsLink.setInlineEntitySet(parentsCollection);
                entity.getNavigationLinks().add(parentsLink);
            }
        } catch (Exception e) {
            // Parents not accessible
        }
    }
    
    /**
     * Expand children collection for folders.
     */
    private void expandChildren(Entity entity, String folderId) {
        try {
            ObjectInFolderList children = navigationService.getChildren(
                    callContext, repositoryId, folderId, "*", null,
                    Boolean.FALSE, IncludeRelationships.NONE, null,
                    Boolean.FALSE, BigInteger.valueOf(100), BigInteger.ZERO,
                    null, null
            );
            
            if (children != null && children.getObjects() != null) {
                EntityCollection childrenCollection = new EntityCollection();
                for (ObjectInFolderData childData : children.getObjects()) {
                    if (childData.getObject() != null) {
                        Entity childEntity = convertToEntity(childData.getObject());
                        childrenCollection.getEntities().add(childEntity);
                    }
                }
                
                if (children.getNumItems() != null) {
                    childrenCollection.setCount(children.getNumItems().intValue());
                }
                
                Link childrenLink = new Link();
                childrenLink.setTitle("children");
                childrenLink.setType("application/json");
                childrenLink.setInlineEntitySet(childrenCollection);
                entity.getNavigationLinks().add(childrenLink);
            }
        } catch (Exception e) {
            // Children not accessible
        }
    }
    
    /**
     * Expand source object for relationships.
     */
    private void expandRelationshipSource(Entity entity, Properties properties) {
        PropertyData<?> sourceIdProperty = properties.getProperties().get("cmis:sourceId");
        if (sourceIdProperty == null || sourceIdProperty.getFirstValue() == null) {
            return;
        }
        
        String sourceId = (String) sourceIdProperty.getFirstValue();
        expandRelatedObject(entity, sourceId, "source");
    }
    
    /**
     * Expand target object for relationships.
     */
    private void expandRelationshipTarget(Entity entity, Properties properties) {
        PropertyData<?> targetIdProperty = properties.getProperties().get("cmis:targetId");
        if (targetIdProperty == null || targetIdProperty.getFirstValue() == null) {
            return;
        }
        
        String targetId = (String) targetIdProperty.getFirstValue();
        expandRelatedObject(entity, targetId, "target");
    }
    
    /**
     * Helper method to expand a related object by ID.
     */
    private void expandRelatedObject(Entity entity, String objectId, String linkTitle) {
        try {
            ObjectData relatedObject = objectService.getObject(
                    callContext, repositoryId, objectId, "*",
                    Boolean.FALSE, IncludeRelationships.NONE, null,
                    Boolean.FALSE, Boolean.FALSE, null
            );
            
            if (relatedObject != null) {
                Entity relatedEntity = convertToEntity(relatedObject);
                Link link = new Link();
                link.setTitle(linkTitle);
                link.setType("application/json");
                link.setInlineEntity(relatedEntity);
                entity.getNavigationLinks().add(link);
            }
        } catch (Exception e) {
            // Related object not accessible
        }
    }
}
