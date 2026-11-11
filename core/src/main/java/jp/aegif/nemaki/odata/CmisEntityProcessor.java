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
import org.apache.olingo.commons.api.edm.FullQualifiedName;
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
import org.apache.olingo.server.api.uri.UriResourceFunction;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
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

    // A function returning a single entity is dispatched by Olingo to
    // EntityProcessor.readEntity; delegate those URIs to the function processor
    // (only one processor may register per Olingo interface).
    private CmisFunctionProcessor functionProcessor;

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

    /** Inject the delegate that handles function URIs returning a single entity. */
    public void setFunctionProcessor(CmisFunctionProcessor functionProcessor) {
        this.functionProcessor = functionProcessor;
    }

    private static final String NAVIGATION_AFTER_THE_KEY_REFUSED =
            "a single entity is read only by its entity set and key — a navigation after the key is not read";
    static final String READ_NOT_TRANSLATED =
            "a single entity is read only from an entity set whose entities are CMIS objects — it is not translated: ";
    static final String READ_NOT_HELD =
            "a single entity is answered only from an entity set that holds its base type — there is no such entity in ";
    static final String READ_UNREADABLE = "the base type of the entity read could not be read";
    static final String EXPAND_NOT_TRANSLATED =
            "$expand is answered only for children and parent of a folder, parents and parent of a document, and source and "
                    + "target of a relationship, each with no option, cast or path of its own — it is not translated: ";
    static final String EXPAND_MORE_THAN_ONE_PARENT =
            "the document has more than one parent, so parent is not answered — read them with $expand=parents";
    static final String EXPAND_DOES_NOT_FIT =
            "the children do not fit in one expanded answer — read them at the folder's children navigation "
                    + "(Folders('<id>')/children)";
    static final String EXPAND_UNREADABLE = "what $expand asks for could not be read: ";
    /** The children one expanded answer carries. A folder with more is refused, not cut (R260, 3.4.2). */
    static final int EXPANDED_CHILDREN = 100;

    @Override
    public void readEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {

        // Get the entity set and key from the URI
        List<UriResource> resourcePaths = uriInfo.getUriResourceParts();

        // A function returning a single entity is dispatched here by Olingo too;
        // hand it to the function processor instead of treating the first path
        // segment as an entity set.
        if (functionProcessor != null) {
            for (UriResource part : resourcePaths) {
                if (part instanceof UriResourceFunction) {
                    functionProcessor.readEntity(request, response, uriInfo, responseFormat);
                    return;
                }
            }
        }

        // Only the key of the first segment was read: Folders('F')/parent answered F itself, Documents('D')/parent D
        // itself and Folders('F')/children('R') F for an R that is not F's child (R255, 3.4.2). A navigation after the
        // key is not translated; it is refused.
        if (resourcePaths.size() > 1) {
            throw new ODataApplicationException(NAVIGATION_AFTER_THE_KEY_REFUSED,
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
        }

        UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0);
        EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();

        // None of the casts Olingo keeps for one entity was read — after the key (Objects('D')/NemakiWare.CMIS.Policy),
        // before it (Objects/NemakiWare.CMIS.Policy('D')) or on $entity ($entity/NemakiWare.CMIS.Policy?$id=Objects('D')) —
        // so the document was answered for a type it is not (R254, 3.4.2). A cast is answered only when it is the entity
        // set's own type; the type of the entity read does not decide the answer. Olingo reports the cast on $entity in
        // place of the one after the key, so all three are read.
        for (org.apache.olingo.commons.api.edm.EdmType cast : new org.apache.olingo.commons.api.edm.EdmType[] {
                uriInfo.getEntityTypeCast(), uriResourceEntitySet.getTypeFilterOnCollection(),
                uriResourceEntitySet.getTypeFilterOnEntry()}) {
            if (cast != null && !edmEntitySet.getEntityType().getFullQualifiedName().equals(cast.getFullQualifiedName())) {
                throw new ODataApplicationException(
                        "a type cast on one entity is answered only for its entity set's own type, not for "
                                + cast.getFullQualifiedName().getFullQualifiedNameAsString(),
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
            }
        }

        // Types('cmis:document') and Users('admin') were looked up as CMIS objects — 404 for a type and a user that exist, and
        // the object itself for a key that is an object id (R264, 3.4.2). Their entities are not CMIS objects and are not
        // translated: refused with a 400 before anything is read.
        if (WriteTarget.entitiesAreNotCmisObjects(edmEntitySet.getName())) {
            throw new ODataApplicationException(READ_NOT_TRANSLATED + edmEntitySet.getName(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
        }

        // $expand=children answered a folder that has children as one that has none, $expand=* and
        // Documents('D')?$expand=parent a parent that exists as null, and the options of an item ($top, $levels, …)
        // were dropped (R260, 3.4.2). The items this processor translates are named; any other refuses the request
        // before anything is read.
        List<String> expansions = translatedExpansions(uriInfo.getExpandOption());

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

        // Folders('<document>') answered the document, Documents('<folder>') the folder (R268, 3.4.2). An entity whose base
        // type its entity set does not hold is not in it: 404, and the entity is not answered. Objects holds every object base
        // type. A base type that cannot be read is an error, not a 404.
        WriteTarget.OnRead found = WriteTarget.onRead(edmEntitySet.getName(), objectData);
        if (found == WriteTarget.OnRead.CANNOT_TELL) {
            throw ODataExceptions.map(READ_UNREADABLE,
                    new IllegalStateException("the base type of " + objectId + " cannot be told for " + edmEntitySet.getName()));
        }
        if (found == WriteTarget.OnRead.NOT_HELD_BY_IT) {
            throw new ODataApplicationException(READ_NOT_HELD + edmEntitySet.getName() + ": " + objectId,
                    HttpStatusCode.NOT_FOUND.getStatusCode(), Locale.ENGLISH);
        }
        
        // Convert to OData entity with absolute URI for @odata.id
        String baseUri = request.getRawBaseUri();
        String entitySetName = edmEntitySet.getName();
        Entity entity = convertToEntity(objectData, baseUri, entitySetName);
        
        // What each expansion asks for is read, or the request fails (R260, 3.4.2).
        ExpandOption expandOption = uriInfo.getExpandOption();
        for (String expansion : expansions) {
            expand(entity, objectData, objectId, expansion, edmEntitySet.getEntityType().getFullQualifiedName());
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
        
        // Only the first segment was read: POST Folders('F')/children created the folder under the root, not under F (R261),
        // and a cast on the entity set was dropped (R270, 3.4.2). Refused with a 400 before the body is read.
        UriResourceEntitySet uriResourceEntitySet = WriteTarget.aimedAt(uriInfo, false);
        // POST Users answered a 500 after the body and the repository were read (R272, 3.4.2). Types, Users and Groups are
        // not CMIS objects; refused with a 400 before either is read.
        WriteTarget.translated(uriResourceEntitySet);
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
            throw ODataExceptions.map("Error creating entity", e);
        }
    }
    
    @Override
    public void updateEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType requestFormat, ContentType responseFormat)
            throws ODataApplicationException, SerializerException {
        
        // Only the key of the first segment was read: PATCH Folders('F')/children('D') updated F (R261), and
        // PATCH Objects('D')/NemakiWare.CMIS.Policy updated the document D (R270, 3.4.2). Refused with a 400 before the body
        // is read.
        UriResourceEntitySet uriResourceEntitySet = WriteTarget.aimedAt(uriInfo, false);
        // PATCH Users('admin') answered a 500 after the body was read (R272, 3.4.2). Refused with a 400 before it is read.
        WriteTarget.translated(uriResourceEntitySet);
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

        // PATCH Folders('<document>') updated the document (R271, 3.4.2). The entity is read before the body, and one its
        // entity set does not hold is answered 404.
        WriteTarget.holds(uriResourceEntitySet, objectId, objectService, callContext, repositoryId, "Entity not found: ");
        
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
            throw ODataExceptions.map("Error updating entity", e);
        }
    }
    
    @Override
    public void deleteEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo)
            throws ODataApplicationException {
        
        // Only the key of the first segment was read: DELETE Folders('F')/children('D') deleted F (R261), and
        // DELETE Objects('D')/NemakiWare.CMIS.Folder deleted the document D (R270, 3.4.2). Refused with a 400.
        UriResourceEntitySet uriResourceEntitySet = WriteTarget.aimedAt(uriInfo, false);
        // DELETE Users('admin') called deleteObject("admin") (R272, 3.4.2). Refused with a 400 before anything is read.
        WriteTarget.translated(uriResourceEntitySet);
        
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

        // DELETE Folders('<document>') deleted the document (R271, 3.4.2). One its entity set does not hold is answered 404.
        WriteTarget.holds(uriResourceEntitySet, objectId, objectService, callContext, repositoryId, "Entity not found: ");
        
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
            throw ODataExceptions.map("Error deleting entity", e);
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
            throw ODataExceptions.map("Error fetching entity", e);
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
            throw ODataExceptions.map("Error fetching entity", e);
        }
    }
    
    /**
     * The navigation properties a request's $expand names, in the order given, when every item is a navigation property
     * and nothing else. Any other item refuses the request (R260, 3.4.2). Olingo lets through only the navigation
     * properties the entity's type defines — children and parent of a folder, parents and parent of a document, source
     * and target of a relationship — and {@link #expand} reads each of them.
     */
    private static List<String> translatedExpansions(ExpandOption expandOption) throws ODataApplicationException {
        List<String> names = new ArrayList<>();
        if (expandOption == null || expandOption.getExpandItems() == null) {
            return names;
        }
        for (ExpandItem item : expandOption.getExpandItems()) {
            String name = plainNavigation(item);
            if (name == null) {
                throw new ODataApplicationException(
                        EXPAND_NOT_TRANSLATED + (item.isStar() ? "*" : "an item with an option, a cast or a path of its own"),
                        HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The navigation property an $expand item names when the item is that name and nothing else; null for any other. The
     * item {@code *} has no path, which is how it is told here.
     */
    private static String plainNavigation(ExpandItem item) {
        if (item.isRef() || item.hasCountPath() || item.getStartTypeFilter() != null
                || item.getLevelsOption() != null || item.getFilterOption() != null || item.getSearchOption() != null
                || item.getOrderByOption() != null || item.getSkipOption() != null || item.getTopOption() != null
                || item.getCountOption() != null || item.getSelectOption() != null || item.getExpandOption() != null
                || item.getApplyOption() != null || item.getResourcePath() == null) {
            return null;
        }
        List<UriResource> parts = item.getResourcePath().getUriResourceParts();
        if (parts.size() != 1 || !(parts.get(0) instanceof UriResourceNavigation)) {
            return null;
        }
        UriResourceNavigation navigation = (UriResourceNavigation) parts.get(0);
        if (navigation.getTypeFilterOnCollection() != null || navigation.getTypeFilterOnEntry() != null
                || !navigation.getKeyPredicates().isEmpty()) {
            return null;
        }
        return navigation.getProperty().getName();
    }

    /**
     * Reads what one expansion asks for and puts it on the entity. A read that fails, or that does not say what it was
     * asked, fails the request: it is never written as no children, no parents or no parent (R260, 3.4.2).
     */
    private void expand(Entity entity, ObjectData objectData, String objectId, String name, FullQualifiedName type)
            throws ODataApplicationException {
        try {
            switch (name) {
                case "children":
                    entity.getNavigationLinks().add(inlineSet(name, childrenOf(objectId)));
                    return;
                case "parents":
                    entity.getNavigationLinks().add(inlineSet(name, parentsOf(objectId)));
                    return;
                case "parent":
                    if (CmisEdmProvider.ET_DOCUMENT_FQN.equals(type)) {
                        // A document names no parent of its own; its parents are read. One is the parent, none is no
                        // parent (null), and more than one cannot be answered as one.
                        List<ObjectData> parents = parentsOf(objectId);
                        if (parents.size() > 1) {
                            throw new ODataApplicationException(EXPAND_MORE_THAN_ONE_PARENT,
                                    HttpStatusCode.BAD_REQUEST.getStatusCode(), Locale.ENGLISH);
                        }
                        if (!parents.isEmpty()) {
                            entity.getNavigationLinks().add(inline(name, parents.get(0)));
                        }
                        return;
                    }
                    String parentId = idNamedBy(objectData, "cmis:parentId");
                    if (parentId == null) {
                        // Only the root folder has no parent, and that is answered as null (no link). Any other folder
                        // that names none was not read well enough to say so.
                        if (!objectId.equals(repositoryService.getRepositoryInfo(repositoryId).getRootFolderId())) {
                            throw new IllegalStateException("the folder names no parent and is not the root folder");
                        }
                        return;
                    }
                    entity.getNavigationLinks().add(inline(name, readForExpansion(parentId)));
                    return;
                case "source":
                case "target":
                    String relatedId = idNamedBy(objectData, "source".equals(name) ? "cmis:sourceId" : "cmis:targetId");
                    if (relatedId == null) {
                        throw new IllegalStateException("the relationship names no " + name);
                    }
                    entity.getNavigationLinks().add(inline(name, readForExpansion(relatedId)));
                    return;
                default:
                    throw new IllegalStateException("not an expansion this processor translates: " + name);
            }
        } catch (ODataApplicationException refused) {
            throw refused;
        } catch (CmisObjectNotFoundException gone) {
            // What the entity names is not there. The entity itself was read, so this is not the request's 404.
            throw ODataExceptions.map(EXPAND_UNREADABLE + name + " of " + objectId,
                    new IllegalStateException(gone.getMessage(), gone));
        } catch (Exception e) {
            throw ODataExceptions.map(EXPAND_UNREADABLE + name + " of " + objectId, e);
        }
    }

    /** All the children of a folder, or a refusal when they do not fit in one answer. */
    private List<ObjectData> childrenOf(String folderId) throws ODataApplicationException {
        // getChildren hands the folder itself back through this holder. Given none it throws, and that used to be caught
        // and written as no children.
        ObjectInFolderList list = navigationService.getChildren(callContext, repositoryId, folderId, "*", null,
                Boolean.FALSE, IncludeRelationships.NONE, null, Boolean.FALSE, BigInteger.valueOf(EXPANDED_CHILDREN),
                BigInteger.ZERO, new Holder<ObjectData>(), null);
        if (list == null || list.getObjects() == null) {
            throw new IllegalStateException("the children were not answered");
        }
        // Complete when the listing says so; when it says nothing, only when it is shorter than what was asked for.
        boolean complete = list.hasMoreItems() == null ? list.getObjects().size() < EXPANDED_CHILDREN
                : !list.hasMoreItems().booleanValue();
        if (!complete) {
            throw new ODataApplicationException(EXPAND_DOES_NOT_FIT, HttpStatusCode.BAD_REQUEST.getStatusCode(),
                    Locale.ENGLISH);
        }
        List<ObjectData> children = new ArrayList<>();
        for (ObjectInFolderData child : list.getObjects()) {
            if (child == null || child.getObject() == null) {
                throw new IllegalStateException("a child was answered without its object");
            }
            children.add(child.getObject());
        }
        return children;
    }

    /** All the parent folders of an object, each as the caller may read it. */
    private List<ObjectData> parentsOf(String objectId) {
        List<ObjectParentData> list = navigationService.getObjectParents(callContext, repositoryId, objectId, "*",
                Boolean.FALSE, IncludeRelationships.NONE, null, Boolean.FALSE, null);
        if (list == null) {
            throw new IllegalStateException("the parents were not answered");
        }
        List<ObjectData> parents = new ArrayList<>();
        for (ObjectParentData parent : list) {
            if (parent == null || parent.getObject() == null) {
                throw new IllegalStateException("a parent was answered without its object");
            }
            // getObjectParents asks whether the caller may read the object's parents, not whether the caller may read
            // each of them: it answers a folder the caller cannot read. Each parent is read again as getObject answers
            // it, so such a folder fails the request here, as it does for $expand=parent on a folder.
            String parentId = idNamedBy(parent.getObject(), "cmis:objectId");
            if (parentId == null) {
                throw new IllegalStateException("a parent was answered without its id");
            }
            parents.add(readForExpansion(parentId));
        }
        return parents;
    }

    /** The one object an expansion names by id. */
    private ObjectData readForExpansion(String id) {
        ObjectData related = objectService.getObject(callContext, repositoryId, id, "*", Boolean.FALSE,
                IncludeRelationships.NONE, null, Boolean.FALSE, Boolean.FALSE, null);
        if (related == null) {
            throw new IllegalStateException("the object " + id + " was not answered");
        }
        return related;
    }

    private static String idNamedBy(ObjectData objectData, String propertyId) {
        Properties properties = objectData.getProperties();
        PropertyData<?> property = properties == null || properties.getProperties() == null ? null
                : properties.getProperties().get(propertyId);
        Object value = property == null ? null : property.getFirstValue();
        return value == null ? null : value.toString();
    }

    private Link inline(String name, ObjectData related) {
        Link link = new Link();
        link.setTitle(name);
        link.setType("application/json");
        link.setInlineEntity(convertToEntity(related));
        return link;
    }

    private Link inlineSet(String name, List<ObjectData> related) {
        EntityCollection collection = new EntityCollection();
        for (ObjectData one : related) {
            collection.getEntities().add(convertToEntity(one));
        }
        Link link = new Link();
        link.setTitle(name);
        link.setType("application/json");
        link.setInlineEntitySet(collection);
        return link;
    }
}
