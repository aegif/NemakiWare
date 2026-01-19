package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
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

import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import java.io.InputStream;
import java.net.URI;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

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
    private final String repositoryId;
    private final CallContext callContext;
    
    public CmisEntityProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
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
        
        // Fetch the entity from CMIS
        Entity entity = getEntity(objectId);
        
        if (entity == null) {
            throw new ODataApplicationException(
                    "Entity not found: " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(),
                    Locale.ENGLISH
            );
        }
        
        // Serialize the response
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        
        EdmEntityType edmEntityType = edmEntitySet.getEntityType();
        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(ContextURL.Suffix.ENTITY).build();
        
        EntitySerializerOptions opts = EntitySerializerOptions.with()
                .contextURL(contextUrl)
                .build();
        
        SerializerResult serializerResult = serializer.entity(serviceMetadata, edmEntityType, entity, opts);
        
        response.setContent(serializerResult.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }
    
    @Override
    public void createEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        // Create operation - to be implemented
        throw new ODataApplicationException(
                "Create operation not yet implemented",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ENGLISH
        );
    }
    
    @Override
    public void updateEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        // Update operation - to be implemented
        throw new ODataApplicationException(
                "Update operation not yet implemented",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ENGLISH
        );
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
     * Convert CMIS ObjectData to OData Entity.
     */
    private Entity convertToEntity(ObjectData objectData) {
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
        
        // Set the entity ID
        String objectIdValue = getPropertyValue(objectData, "cmis:objectId");
        if (objectIdValue != null) {
            entity.setId(URI.create(objectIdValue));
        }
        
        return entity;
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
}
