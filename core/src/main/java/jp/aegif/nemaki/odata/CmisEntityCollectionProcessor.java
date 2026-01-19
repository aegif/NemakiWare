package jp.aegif.nemaki.odata;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
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
import org.apache.olingo.server.api.processor.EntityCollectionProcessor;
import org.apache.olingo.server.api.serializer.EntityCollectionSerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.queryoption.CountOption;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import java.math.BigInteger;
import java.net.URI;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * OData Entity Collection Processor for CMIS objects.
 * 
 * Handles requests for entity collections (e.g., /Documents, /Folders).
 * Converts CMIS query results to OData entity collections.
 */
public class CmisEntityCollectionProcessor implements EntityCollectionProcessor {
    
    private OData odata;
    private ServiceMetadata serviceMetadata;
    
    private final RepositoryService repositoryService;
    private final ObjectService objectService;
    private final NavigationService navigationService;
    private final DiscoveryService discoveryService;
    private final String repositoryId;
    private final CallContext callContext;
    
    public CmisEntityCollectionProcessor(
            RepositoryService repositoryService,
            ObjectService objectService,
            NavigationService navigationService,
            DiscoveryService discoveryService,
            String repositoryId,
            CallContext callContext) {
        this.repositoryService = repositoryService;
        this.objectService = objectService;
        this.navigationService = navigationService;
        this.discoveryService = discoveryService;
        this.repositoryId = repositoryId;
        this.callContext = callContext;
    }
    
    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
        this.serviceMetadata = serviceMetadata;
    }
    
    @Override
    public void readEntityCollection(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Get the entity set from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
        
        // Get pagination options
        int top = getTopOption(uriInfo);
        int skip = getSkipOption(uriInfo);
        boolean count = getCountOption(uriInfo);
        
        // Fetch the data from CMIS
        EntityCollection entityCollection = getData(edmEntitySet, top, skip);
        
        // Serialize the response
        ODataSerializer serializer = odata.createSerializer(responseFormat);
        
        EdmEntityType edmEntityType = edmEntitySet.getEntityType();
        ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).build();
        
        final String id = request.getRawBaseUri() + "/" + edmEntitySet.getName();
        EntityCollectionSerializerOptions.Builder optsBuilder = EntityCollectionSerializerOptions.with()
                .id(id)
                .contextURL(contextUrl)
                .count(uriInfo.getCountOption());
        EntityCollectionSerializerOptions opts = optsBuilder.build();
        
        SerializerResult serializerResult = serializer.entityCollection(serviceMetadata, edmEntityType, entityCollection, opts);
        
        response.setContent(serializerResult.getContent());
        response.setStatusCode(HttpStatusCode.OK.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
    }
    
    /**
     * Get data from CMIS based on the entity set name.
     */
    private EntityCollection getData(EdmEntitySet edmEntitySet, int maxItems, int skipCount) throws ODataApplicationException {
        EntityCollection entityCollection = new EntityCollection();
        String entitySetName = edmEntitySet.getName();
        
        try {
            String cmisQuery = buildCmisQuery(entitySetName);
            
            if (cmisQuery != null) {
                ObjectList objectList = discoveryService.query(
                        callContext,
                        repositoryId,
                        cmisQuery,
                        Boolean.FALSE,  // searchAllVersions
                        Boolean.TRUE,   // includeAllowableActions
                        IncludeRelationships.NONE,
                        null,           // renditionFilter
                        BigInteger.valueOf(maxItems),
                        BigInteger.valueOf(skipCount),
                        null            // extension
                );
                
                if (objectList != null && objectList.getObjects() != null) {
                    for (ObjectData objectData : objectList.getObjects()) {
                        Entity entity = convertToEntity(objectData);
                        entityCollection.getEntities().add(entity);
                    }
                    
                    if (objectList.getNumItems() != null) {
                        entityCollection.setCount(objectList.getNumItems().intValue());
                    }
                }
            }
        } catch (Exception e) {
            throw new ODataApplicationException(
                    "Error fetching data: " + e.getMessage(),
                    HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
                    Locale.ENGLISH,
                    e
            );
        }
        
        return entityCollection;
    }
    
    /**
     * Build CMIS query based on entity set name.
     */
    private String buildCmisQuery(String entitySetName) {
        switch (entitySetName) {
            case CmisEdmProvider.ES_DOCUMENTS_NAME:
                return "SELECT * FROM cmis:document";
            case CmisEdmProvider.ES_FOLDERS_NAME:
                return "SELECT * FROM cmis:folder";
            case CmisEdmProvider.ES_RELATIONSHIPS_NAME:
                return "SELECT * FROM cmis:relationship";
            case CmisEdmProvider.ES_POLICIES_NAME:
                return "SELECT * FROM cmis:policy";
            case CmisEdmProvider.ES_ITEMS_NAME:
                return "SELECT * FROM cmis:item";
            case CmisEdmProvider.ES_OBJECTS_NAME:
                // For Objects, we query all base types
                return "SELECT * FROM cmis:document";
            default:
                return null;
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
        String objectId = getPropertyValue(objectData, "cmis:objectId");
        if (objectId != null) {
            entity.setId(URI.create(objectId));
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
     * Get the $top query option value.
     */
    private int getTopOption(UriInfo uriInfo) {
        TopOption topOption = uriInfo.getTopOption();
        if (topOption != null) {
            return topOption.getValue();
        }
        return 100; // Default max items
    }
    
    /**
     * Get the $skip query option value.
     */
    private int getSkipOption(UriInfo uriInfo) {
        SkipOption skipOption = uriInfo.getSkipOption();
        if (skipOption != null) {
            return skipOption.getValue();
        }
        return 0;
    }
    
    /**
     * Get the $count query option value.
     */
    private boolean getCountOption(UriInfo uriInfo) {
        CountOption countOption = uriInfo.getCountOption();
        if (countOption != null) {
            return countOption.getValue();
        }
        return false;
    }
}
