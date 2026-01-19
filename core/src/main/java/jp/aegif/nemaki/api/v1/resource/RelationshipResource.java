package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.PropertyValue;
import jp.aegif.nemaki.api.v1.model.request.RelationshipRequest;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectListResponse;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.api.v1.model.response.RelationshipResponse;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RelationshipService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/relationships")
@Tag(name = "relationships", description = "CMIS relationship operations")
@Produces(MediaType.APPLICATION_JSON)
public class RelationshipResource {
    
    private static final Logger logger = Logger.getLogger(RelationshipResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Autowired
    private RelationshipService relationshipService;
    
    @Autowired
    private ObjectService objectService;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create relationship",
            description = "Creates a relationship object of the specified type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Relationship created",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RelationshipResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createRelationship(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Relationship to create", required = true)
            RelationshipRequest request) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (request == null) {
                throw ApiException.invalidArgument("Request body is required");
            }
            if (request.getSourceId() == null || request.getSourceId().isEmpty()) {
                throw ApiException.invalidArgument("Source object ID (sourceId) is required");
            }
            if (request.getTargetId() == null || request.getTargetId().isEmpty()) {
                throw ApiException.invalidArgument("Target object ID (targetId) is required");
            }
            
            Properties properties = convertToProperties(request);
            
            String relationshipId = objectService.createRelationship(
                    callContext, repositoryId, properties, null, null, null, null);
            
            ObjectData relationshipData = objectService.getObject(
                    callContext, repositoryId, relationshipId, null, Boolean.FALSE, 
                    null, null, Boolean.FALSE, Boolean.FALSE, null);
            
            RelationshipResponse response = mapToRelationshipResponse(relationshipData, repositoryId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating relationship: " + e.getMessage());
            throw ApiException.internalError("Failed to create relationship: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/object/{objectId}")
    @Operation(
            summary = "Get object relationships",
            description = "Gets all or a subset of relationships associated with an independent object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of relationships",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getObjectRelationships(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Include relationships of subtypes")
            @QueryParam("includeSubRelationshipTypes") @DefaultValue("false") Boolean includeSubRelationshipTypes,
            @Parameter(description = "Relationship direction (source, target, either)")
            @QueryParam("relationshipDirection") @DefaultValue("either") String relationshipDirection,
            @Parameter(description = "Relationship type ID filter")
            @QueryParam("typeId") String typeId,
            @Parameter(description = "Property filter")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            RelationshipDirection direction = parseRelationshipDirection(relationshipDirection);
            
            ObjectList relationships = relationshipService.getObjectRelationships(
                    callContext, repositoryId, objectId, includeSubRelationshipTypes,
                    direction, typeId, filter, includeAllowableActions,
                    BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            ObjectListResponse response = mapToObjectListResponse(relationships, repositoryId, objectId, maxItems, skipCount);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting relationships for object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get relationships: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (repositoryService.getRepositoryInfo(repositoryId) == null) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }
    
    private CallContext getCallContext() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return callContext;
    }
    
    private Properties convertToProperties(RelationshipRequest request) {
        PropertiesImpl properties = new PropertiesImpl();
        
        String typeId = request.getTypeId() != null ? request.getTypeId() : "cmis:relationship";
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, typeId));
        properties.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID, request.getSourceId()));
        properties.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID, request.getTargetId()));
        
        if (request.getName() != null) {
            properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, request.getName()));
        }
        
        addCustomProperties(properties, request.getProperties());
        
        return properties;
    }

    private void addCustomProperties(PropertiesImpl properties, Map<String, PropertyValue> propertyMap) {
        if (propertyMap == null) {
            return;
        }

        for (Map.Entry<String, PropertyValue> entry : propertyMap.entrySet()) {
            String propertyId = entry.getKey();
            PropertyValue pv = entry.getValue();

            if (pv == null || pv.getValue() == null) {
                continue;
            }

            String type = pv.getType() != null ? pv.getType() : "string";
            Object value = pv.getValue();
            boolean isMultiValued = "multi".equals(pv.getCardinality()) || value instanceof List;

            switch (type) {
                case "id":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyIdImpl(propertyId, convertToStringList(value)));
                    } else {
                        properties.addProperty(new PropertyIdImpl(propertyId, value.toString()));
                    }
                    break;
                case "boolean":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyBooleanImpl(propertyId, convertToBooleanList(value)));
                    } else {
                        properties.addProperty(new PropertyBooleanImpl(propertyId, convertToBoolean(value)));
                    }
                    break;
                case "integer":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigIntegerList(value)));
                    } else {
                        properties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigInteger(value)));
                    }
                    break;
                case "decimal":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimalList(value)));
                    } else {
                        properties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimal(value)));
                    }
                    break;
                case "datetime":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendarList(value)));
                    } else {
                        properties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendar(value)));
                    }
                    break;
                case "html":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyHtmlImpl(propertyId, convertToStringList(value)));
                    } else {
                        properties.addProperty(new PropertyHtmlImpl(propertyId, value.toString()));
                    }
                    break;
                case "uri":
                    if (isMultiValued) {
                        properties.addProperty(new PropertyUriImpl(propertyId, convertToStringList(value)));
                    } else {
                        properties.addProperty(new PropertyUriImpl(propertyId, value.toString()));
                    }
                    break;
                case "string":
                default:
                    if (isMultiValued) {
                        properties.addProperty(new PropertyStringImpl(propertyId, convertToStringList(value)));
                    } else {
                        properties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
                    }
                    break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> convertToStringList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item != null ? item.toString() : null);
            }
            return result;
        }
        return List.of(value.toString());
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Boolean> convertToBooleanList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Boolean> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBoolean(item));
            }
            return result;
        }
        return List.of(convertToBoolean(value));
    }

    private BigInteger convertToBigInteger(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return new BigInteger(value.toString());
    }

    @SuppressWarnings("unchecked")
    private List<BigInteger> convertToBigIntegerList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<BigInteger> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBigInteger(item));
            }
            return result;
        }
        return List.of(convertToBigInteger(value));
    }

    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    private List<BigDecimal> convertToBigDecimalList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<BigDecimal> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBigDecimal(item));
            }
            return result;
        }
        return List.of(convertToBigDecimal(value));
    }

    private GregorianCalendar convertToCalendar(Object value) {
        if (value instanceof GregorianCalendar) {
            return (GregorianCalendar) value;
        }
        String dateStr = value.toString();
        try {
            GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            synchronized (ISO_DATE_FORMAT) {
                calendar.setTime(ISO_DATE_FORMAT.parse(dateStr));
            }
            return calendar;
        } catch (ParseException e) {
            logger.warning("Failed to parse datetime: " + dateStr);
            throw ApiException.invalidArgument("Invalid datetime format: " + dateStr + ". Expected ISO 8601 format (e.g., 2024-01-15T10:30:00Z)");
        }
    }

    @SuppressWarnings("unchecked")
    private List<GregorianCalendar> convertToCalendarList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<GregorianCalendar> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToCalendar(item));
            }
            return result;
        }
        return List.of(convertToCalendar(value));
    }
    
    private RelationshipDirection parseRelationshipDirection(String direction) {
        if (direction == null || direction.isEmpty() || "either".equalsIgnoreCase(direction)) {
            return RelationshipDirection.EITHER;
        }
        try {
            return RelationshipDirection.valueOf(direction.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidArgument("Invalid relationship direction: " + direction + 
                    ". Valid values are: source, target, either");
        }
    }
    
    private RelationshipResponse mapToRelationshipResponse(ObjectData objectData, String repositoryId) {
        RelationshipResponse response = new RelationshipResponse();
        
        Properties properties = objectData.getProperties();
        if (properties != null) {
            response.setObjectId(getStringProperty(properties, PropertyIds.OBJECT_ID));
            response.setObjectTypeId(getStringProperty(properties, PropertyIds.OBJECT_TYPE_ID));
            response.setName(getStringProperty(properties, PropertyIds.NAME));
            response.setSourceId(getStringProperty(properties, PropertyIds.SOURCE_ID));
            response.setTargetId(getStringProperty(properties, PropertyIds.TARGET_ID));
            response.setCreatedBy(getStringProperty(properties, PropertyIds.CREATED_BY));
            response.setCreationDate(getDateProperty(properties, PropertyIds.CREATION_DATE));
            response.setLastModifiedBy(getStringProperty(properties, PropertyIds.LAST_MODIFIED_BY));
            response.setLastModificationDate(getDateProperty(properties, PropertyIds.LAST_MODIFICATION_DATE));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
        if (response.getSourceId() != null) {
            links.put("source", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + response.getSourceId()));
        }
        if (response.getTargetId() != null) {
            links.put("target", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + response.getTargetId()));
        }
        response.setLinks(links);
        
        return response;
    }
    
    private ObjectListResponse mapToObjectListResponse(ObjectList objectList, String repositoryId, 
            String objectId, int maxItems, int skipCount) {
        ObjectListResponse response = new ObjectListResponse();
        
        List<ObjectResponse> items = new ArrayList<>();
        if (objectList.getObjects() != null) {
            for (ObjectData obj : objectList.getObjects()) {
                items.add(mapToObjectResponse(obj, repositoryId));
            }
        }
        response.setObjects(items);
        response.setHasMoreItems(objectList.hasMoreItems());
        response.setNumItems(objectList.getNumItems() != null ? objectList.getNumItems().longValue() : null);
        response.setMaxItems((long) maxItems);
        response.setSkipCount((long) skipCount);
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/relationships/object/" + objectId + 
                "?maxItems=" + maxItems + "&skipCount=" + skipCount));
        if (objectList.hasMoreItems()) {
            links.put("next", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/relationships/object/" + objectId + 
                    "?maxItems=" + maxItems + "&skipCount=" + (skipCount + maxItems)));
        }
        response.setLinks(links);
        
        return response;
    }
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId) {
        ObjectResponse response = new ObjectResponse();
        
        Properties properties = objectData.getProperties();
        if (properties != null) {
            response.setObjectId(getStringProperty(properties, PropertyIds.OBJECT_ID));
            response.setObjectTypeId(getStringProperty(properties, PropertyIds.OBJECT_TYPE_ID));
            response.setBaseTypeId(getStringProperty(properties, PropertyIds.BASE_TYPE_ID));
            response.setName(getStringProperty(properties, PropertyIds.NAME));
            response.setCreatedBy(getStringProperty(properties, PropertyIds.CREATED_BY));
            response.setCreationDate(getDateProperty(properties, PropertyIds.CREATION_DATE));
            response.setLastModifiedBy(getStringProperty(properties, PropertyIds.LAST_MODIFIED_BY));
            response.setLastModificationDate(getDateProperty(properties, PropertyIds.LAST_MODIFICATION_DATE));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        return response;
    }
    
    private String getStringProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() != null) {
            return prop.getFirstValue().toString();
        }
        return null;
    }
    
    private String getDateProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() instanceof GregorianCalendar) {
            GregorianCalendar cal = (GregorianCalendar) prop.getFirstValue();
            synchronized (ISO_DATE_FORMAT) {
                return ISO_DATE_FORMAT.format(cal.getTime());
            }
        }
        return null;
    }
    
    private PropertyValue mapPropertyValue(PropertyData<?> prop) {
        Object value = prop.getValues() != null && prop.getValues().size() > 1 
                ? prop.getValues() 
                : prop.getFirstValue();
        
        String type = "string";
        if (prop.getFirstValue() instanceof Boolean) {
            type = "boolean";
        } else if (prop.getFirstValue() instanceof Long || prop.getFirstValue() instanceof Integer || prop.getFirstValue() instanceof BigInteger) {
            type = "integer";
        } else if (prop.getFirstValue() instanceof Double || prop.getFirstValue() instanceof Float || prop.getFirstValue() instanceof java.math.BigDecimal) {
            type = "decimal";
        } else if (prop.getFirstValue() instanceof GregorianCalendar) {
            GregorianCalendar cal = (GregorianCalendar) prop.getFirstValue();
            synchronized (ISO_DATE_FORMAT) {
                value = ISO_DATE_FORMAT.format(cal.getTime());
            }
            type = "datetime";
        }
        
        PropertyValue pv = new PropertyValue(value, type);
        pv.setPropertyDefinitionId(prop.getId());
        pv.setLocalName(prop.getLocalName());
        pv.setDisplayName(prop.getDisplayName());
        pv.setQueryName(prop.getQueryName());
        
        return pv;
    }
}
