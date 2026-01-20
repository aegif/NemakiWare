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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
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
import org.apache.chemistry.opencmis.commons.spi.Holder;
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
@Path("/repositories/{repositoryId}/items")
@Tag(name = "items", description = "CMIS Item operations")
@Produces(MediaType.APPLICATION_JSON)
public class ItemResource {
    
    private static final Logger logger = Logger.getLogger(ItemResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
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
            summary = "Create item",
            description = "Creates a new CMIS item object in the specified folder. Items are independent objects that are not documents, folders, relationships, or policies."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Item created successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Parent folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent folder ID (optional for unfiled items)")
            @QueryParam("folderId") String folderId,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Creating item in repository " + repositoryId + " under folder " + folderId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Properties cmisProperties = convertToProperties(properties);
            
            String itemId = objectService.createItem(
                    callContext, repositoryId, cmisProperties, folderId,
                    null, null, null, null);
            
            ObjectData createdItem = objectService.getObject(
                    callContext, repositoryId, itemId, null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(createdItem, repositoryId, true);
            
            String baseUri = uriInfo.getBaseUri().toString();
            return Response.created(java.net.URI.create(baseUri + "repositories/" + repositoryId + "/items/" + itemId))
                    .entity(response)
                    .build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating item: " + e.getMessage());
            throw ApiException.internalError("Failed to create item: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{itemId}")
    @Operation(
            summary = "Get item",
            description = "Gets the specified item information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Item information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Item ID", required = true)
            @PathParam("itemId") String itemId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting item " + itemId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObject(
                    callContext, repositoryId, itemId, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, false, false, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound(itemId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting item " + itemId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get item: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{itemId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Update item properties",
            description = "Updates the properties of the specified item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Item updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response updateItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Item ID", required = true)
            @PathParam("itemId") String itemId,
            @Parameter(description = "Change token for optimistic locking")
            @QueryParam("changeToken") String changeToken,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Updating item " + itemId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Properties cmisProperties = convertToProperties(properties);
            Holder<String> objectIdHolder = new Holder<>(itemId);
            Holder<String> changeTokenHolder = changeToken != null ? new Holder<>(changeToken) : null;
            
            objectService.updateProperties(callContext, repositoryId, objectIdHolder, cmisProperties, changeTokenHolder, null);
            
            ObjectData updatedItem = objectService.getObject(
                    callContext, repositoryId, objectIdHolder.getValue(), null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(updatedItem, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error updating item " + itemId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to update item: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{itemId}")
    @Operation(
            summary = "Delete item",
            description = "Deletes the specified item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Item deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Item ID", required = true)
            @PathParam("itemId") String itemId) {
        
        logger.info("API v1: Deleting item " + itemId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            objectService.deleteObject(callContext, repositoryId, itemId, true, null);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting item " + itemId + ": " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                throw ApiException.objectNotFound(itemId, repositoryId);
            }
            throw ApiException.internalError("Failed to delete item: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (!repositoryService.hasThisRepositoryId(repositoryId)) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }
    
    private CallContext getCallContext() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("callContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return callContext;
    }
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId, boolean includeAllowableActions) {
        ObjectResponse response = new ObjectResponse();
        response.setObjectId(getStringProperty(objectData, PropertyIds.OBJECT_ID));
        response.setName(getStringProperty(objectData, PropertyIds.NAME));
        response.setObjectTypeId(getStringProperty(objectData, PropertyIds.OBJECT_TYPE_ID));
        response.setBaseTypeId(getStringProperty(objectData, PropertyIds.BASE_TYPE_ID));
        response.setCreatedBy(getStringProperty(objectData, PropertyIds.CREATED_BY));
        response.setCreationDate(getDateProperty(objectData, PropertyIds.CREATION_DATE));
        response.setLastModifiedBy(getStringProperty(objectData, PropertyIds.LAST_MODIFIED_BY));
        response.setLastModificationDate(getDateProperty(objectData, PropertyIds.LAST_MODIFICATION_DATE));
        response.setChangeToken(getStringProperty(objectData, PropertyIds.CHANGE_TOKEN));
        
        Map<String, Object> properties = new HashMap<>();
        if (objectData.getProperties() != null && objectData.getProperties().getProperties() != null) {
            for (Map.Entry<String, PropertyData<?>> entry : objectData.getProperties().getProperties().entrySet()) {
                properties.put(entry.getKey(), mapPropertyValue(entry.getValue()));
            }
        }
        response.setProperties(properties);
        
        if (includeAllowableActions && objectData.getAllowableActions() != null) {
            Map<String, Boolean> actions = new HashMap<>();
            for (org.apache.chemistry.opencmis.commons.enums.Action action : objectData.getAllowableActions().getAllowableActions()) {
                actions.put(action.value(), true);
            }
            response.setAllowableActions(actions);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/items/" + response.getObjectId()));
        links.put("repository", LinkInfo.of(baseUri + "repositories/" + repositoryId));
        response.setLinks(links);
        
        return response;
    }
    
    private String getStringProperty(ObjectData objectData, String propertyId) {
        PropertyData<?> prop = objectData.getProperties().getProperties().get(propertyId);
        return prop != null && prop.getFirstValue() != null ? prop.getFirstValue().toString() : null;
    }
    
    private String getDateProperty(ObjectData objectData, String propertyId) {
        PropertyData<?> prop = objectData.getProperties().getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() instanceof GregorianCalendar) {
            return ISO_DATE_FORMAT.format(((GregorianCalendar) prop.getFirstValue()).getTime());
        }
        return null;
    }
    
    private Object mapPropertyValue(PropertyData<?> propertyData) {
        Object value = propertyData.getValues() != null && propertyData.getValues().size() > 1 
                ? propertyData.getValues() 
                : propertyData.getFirstValue();
        if (value instanceof GregorianCalendar) {
            return ISO_DATE_FORMAT.format(((GregorianCalendar) value).getTime());
        }
        return value;
    }
    
    private Properties convertToProperties(Map<String, PropertyValue> properties) {
        PropertiesImpl cmisProperties = new PropertiesImpl();
        
        if (properties == null || properties.isEmpty()) {
            return cmisProperties;
        }
        
        for (Map.Entry<String, PropertyValue> entry : properties.entrySet()) {
            String propertyId = entry.getKey();
            PropertyValue propertyValue = entry.getValue();
            
            if (propertyValue == null || propertyValue.getValue() == null) {
                continue;
            }
            
            String type = propertyValue.getType() != null ? propertyValue.getType().toLowerCase() : "string";
            Object value = propertyValue.getValue();
            
            switch (type) {
                case "string":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyStringImpl(propertyId, convertToStringList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
                    }
                    break;
                case "id":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyIdImpl(propertyId, convertToStringList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyIdImpl(propertyId, value.toString()));
                    }
                    break;
                case "boolean":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyBooleanImpl(propertyId, convertToBooleanList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyBooleanImpl(propertyId, convertToBoolean(value)));
                    }
                    break;
                case "integer":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigIntegerList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigInteger(value)));
                    }
                    break;
                case "decimal":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimalList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimal(value)));
                    }
                    break;
                case "datetime":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendarList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendar(value)));
                    }
                    break;
                case "html":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyHtmlImpl(propertyId, convertToStringList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyHtmlImpl(propertyId, value.toString()));
                    }
                    break;
                case "uri":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyUriImpl(propertyId, convertToStringList(value)));
                    } else {
                        cmisProperties.addProperty(new PropertyUriImpl(propertyId, value.toString()));
                    }
                    break;
                default:
                    cmisProperties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
            }
        }
        
        return cmisProperties;
    }
    
    @SuppressWarnings("unchecked")
    private List<String> convertToStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                result.add(item != null ? item.toString() : null);
            }
        }
        return result;
    }
    
    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<Boolean> convertToBooleanList(Object value) {
        List<Boolean> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                result.add(convertToBoolean(item));
            }
        }
        return result;
    }
    
    private BigInteger convertToBigInteger(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        } else if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return new BigInteger(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<BigInteger> convertToBigIntegerList(Object value) {
        List<BigInteger> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                result.add(convertToBigInteger(item));
            }
        }
        return result;
    }
    
    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        } else if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<BigDecimal> convertToBigDecimalList(Object value) {
        List<BigDecimal> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                result.add(convertToBigDecimal(item));
            }
        }
        return result;
    }
    
    private GregorianCalendar convertToCalendar(Object value) {
        if (value instanceof GregorianCalendar) {
            return (GregorianCalendar) value;
        }
        try {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTime(ISO_DATE_FORMAT.parse(value.toString()));
            return cal;
        } catch (ParseException e) {
            throw ApiException.invalidArgument("Invalid date format: " + value);
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<GregorianCalendar> convertToCalendarList(Object value) {
        List<GregorianCalendar> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                result.add(convertToCalendar(item));
            }
        }
        return result;
    }
}
