package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/items")
@Tag(name = "items", description = "CMIS Item operations (for non-file, non-folder objects)")
@Produces(MediaType.APPLICATION_JSON)
public class ItemResource {
    
    private static final Logger logger = Logger.getLogger(ItemResource.class.getName());
    private static final DateTimeFormatter ISO_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    
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
            description = "Creates a new CMIS item in the specified parent folder. Items are non-file, non-folder objects that can have custom properties."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Item created successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Parent folder not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent folder ID", required = true)
            @QueryParam("parentId") String parentId,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Creating item in repository " + repositoryId + " under parent " + parentId);
        
        try {
            validateRepository(repositoryId);
            
            if (properties == null || properties.isEmpty()) {
                throw ApiException.invalidArgument("Properties are required");
            }
            
            if (parentId == null || parentId.isEmpty()) {
                throw ApiException.invalidArgument("parentId is required");
            }
            
            CallContext callContext = getCallContext();
            
            ObjectData parentObject = objectService.getObject(callContext, repositoryId, parentId, 
                    "cmis:objectTypeId,cmis:baseTypeId", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            if (parentObject == null) {
                throw ApiException.objectNotFound(parentId, repositoryId, "Parent folder not found");
            }
            
            PropertyData<?> parentBaseTypeId = parentObject.getProperties().getProperties().get(PropertyIds.BASE_TYPE_ID);
            if (parentBaseTypeId == null || !"cmis:folder".equals(parentBaseTypeId.getFirstValue())) {
                throw ApiException.invalidArgument("Parent object " + parentId + " is not a folder. Items can only be created inside folders.");
            }
            
            PropertiesImpl cmisProperties = convertToProperties(properties);
            
            PropertyData<?> objectTypeId = cmisProperties.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
            if (objectTypeId == null || objectTypeId.getFirstValue() == null) {
                cmisProperties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:item"));
            }
            
            PropertyData<?> baseTypeId = cmisProperties.getProperties().get(PropertyIds.BASE_TYPE_ID);
            if (baseTypeId == null || baseTypeId.getFirstValue() == null) {
                cmisProperties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, "cmis:item"));
            }
            
            String itemId = objectService.createItem(callContext, repositoryId, cmisProperties, parentId,
                    null, null, null, null);
            
            ObjectData createdItem = objectService.getObject(callContext, repositoryId, itemId, 
                    "*", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            
            ObjectResponse response = mapToObjectResponse(createdItem, repositoryId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
            
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
            description = "Returns the specified CMIS item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Item retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Item ID", required = true)
            @PathParam("itemId") String itemId) {
        
        logger.info("API v1: Getting item " + itemId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObject(callContext, repositoryId, itemId, 
                    "*", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound(itemId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId);
            
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
            description = "Updates the properties of the specified CMIS item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Item updated successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
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
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Updating item " + itemId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            if (properties == null || properties.isEmpty()) {
                throw ApiException.invalidArgument("Properties are required");
            }
            
            CallContext callContext = getCallContext();
            
            ObjectData existingObject = objectService.getObject(callContext, repositoryId, itemId, 
                    "*", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            
            if (existingObject == null) {
                throw ApiException.objectNotFound(itemId, repositoryId);
            }
            
            PropertiesImpl cmisProperties = convertToProperties(properties);
            
            Holder<String> objectIdHolder = new Holder<>(itemId);
            Holder<String> changeTokenHolder = new Holder<>();
            
            objectService.updateProperties(callContext, repositoryId, objectIdHolder, cmisProperties, 
                    changeTokenHolder, null);
            
            ObjectData updatedItem = objectService.getObject(callContext, repositoryId, objectIdHolder.getValue(), 
                    "*", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            
            ObjectResponse response = mapToObjectResponse(updatedItem, repositoryId);
            
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
            description = "Deletes the specified CMIS item"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Item deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Item not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteItem(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Item ID", required = true)
            @PathParam("itemId") String itemId,
            @Parameter(description = "Delete all versions")
            @QueryParam("allVersions") @DefaultValue("true") boolean allVersions) {
        
        logger.info("API v1: Deleting item " + itemId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            CallContext callContext = getCallContext();
            
            ObjectData existingObject = objectService.getObject(callContext, repositoryId, itemId, 
                    "*", false, IncludeRelationships.NONE, "cmis:none", false, false, null);
            
            if (existingObject == null) {
                throw ApiException.objectNotFound(itemId, repositoryId);
            }
            
            objectService.deleteObject(callContext, repositoryId, itemId, allVersions, null);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting item " + itemId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete item: " + e.getMessage(), e);
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
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId) {
        ObjectResponse response = new ObjectResponse();
        Properties props = objectData.getProperties();
        
        response.setObjectId(getStringProperty(props, PropertyIds.OBJECT_ID));
        response.setName(getStringProperty(props, PropertyIds.NAME));
        response.setObjectTypeId(getStringProperty(props, PropertyIds.OBJECT_TYPE_ID));
        response.setBaseTypeId(getStringProperty(props, PropertyIds.BASE_TYPE_ID));
        response.setCreatedBy(getStringProperty(props, PropertyIds.CREATED_BY));
        response.setLastModifiedBy(getStringProperty(props, PropertyIds.LAST_MODIFIED_BY));
        response.setCreationDate(getDateProperty(props, PropertyIds.CREATION_DATE));
        response.setLastModificationDate(getDateProperty(props, PropertyIds.LAST_MODIFICATION_DATE));
        
        Map<String, PropertyValue> additionalProperties = new HashMap<>();
        for (PropertyData<?> prop : props.getPropertyList()) {
            String propId = prop.getId();
            if (!propId.startsWith("cmis:")) {
                additionalProperties.put(propId, mapToPropertyValue(prop));
            }
        }
        response.setProperties(additionalProperties);
        
        Map<String, LinkInfo> links = new HashMap<>();
        String objectId = response.getObjectId();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/items/" + objectId));
        links.put("object", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + objectId));
        response.setLinks(links);
        
        return response;
    }
    
    private String getStringProperty(Properties props, String propertyId) {
        PropertyData<?> prop = props.getProperties().get(propertyId);
        return prop != null && prop.getFirstValue() != null ? prop.getFirstValue().toString() : null;
    }
    
    private String getDateProperty(Properties props, String propertyId) {
        PropertyData<?> prop = props.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() instanceof GregorianCalendar) {
            return ISO_DATE_FORMAT.format(((GregorianCalendar) prop.getFirstValue()).toInstant());
        }
        return null;
    }

    private PropertyValue mapToPropertyValue(PropertyData<?> prop) {
        Object value = prop.getValues() != null && prop.getValues().size() > 1
                ? prop.getValues()
                : prop.getFirstValue();
        if (value instanceof GregorianCalendar) {
            value = ISO_DATE_FORMAT.format(((GregorianCalendar) value).toInstant());
        }
        String type = inferPropertyType(prop);
        return new PropertyValue(value, type);
    }
    
    private String inferPropertyType(PropertyData<?> prop) {
        Object firstValue = prop.getFirstValue();
        if (firstValue == null) return "string";
        if (firstValue instanceof String) return "string";
        if (firstValue instanceof Boolean) return "boolean";
        if (firstValue instanceof BigInteger || firstValue instanceof Long || firstValue instanceof Integer) return "integer";
        if (firstValue instanceof BigDecimal || firstValue instanceof Double || firstValue instanceof Float) return "decimal";
        if (firstValue instanceof GregorianCalendar) return "datetime";
        return "string";
    }
    
    private PropertiesImpl convertToProperties(Map<String, PropertyValue> properties) {
        PropertiesImpl cmisProperties = new PropertiesImpl();
        
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
                        cmisProperties.addProperty(new PropertyStringImpl(propertyId, convertToStringList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
                    }
                    break;
                case "id":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyIdImpl(propertyId, convertToStringList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyIdImpl(propertyId, value.toString()));
                    }
                    break;
                case "boolean":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyBooleanImpl(propertyId, convertToBooleanList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyBooleanImpl(propertyId, convertToBoolean(value)));
                    }
                    break;
                case "integer":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigIntegerList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigInteger(value)));
                    }
                    break;
                case "decimal":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimalList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimal(value)));
                    }
                    break;
                case "datetime":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendarList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendar(value)));
                    }
                    break;
                case "uri":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyUriImpl(propertyId, convertToStringList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyUriImpl(propertyId, value.toString()));
                    }
                    break;
                case "html":
                    if (value instanceof List) {
                        cmisProperties.addProperty(new PropertyHtmlImpl(propertyId, convertToStringList((List<?>) value)));
                    } else {
                        cmisProperties.addProperty(new PropertyHtmlImpl(propertyId, value.toString()));
                    }
                    break;
                default:
                    cmisProperties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
            }
        }
        
        return cmisProperties;
    }
    
    private List<String> convertToStringList(List<?> values) {
        List<String> result = new ArrayList<>();
        for (Object v : values) {
            result.add(v != null ? v.toString() : null);
        }
        return result;
    }
    
    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
    
    private List<Boolean> convertToBooleanList(List<?> values) {
        List<Boolean> result = new ArrayList<>();
        for (Object v : values) {
            result.add(convertToBoolean(v));
        }
        return result;
    }
    
    private BigInteger convertToBigInteger(Object value) {
        if (value instanceof BigInteger) return (BigInteger) value;
        if (value instanceof Number) return BigInteger.valueOf(((Number) value).longValue());
        return new BigInteger(value.toString());
    }
    
    private List<BigInteger> convertToBigIntegerList(List<?> values) {
        List<BigInteger> result = new ArrayList<>();
        for (Object v : values) {
            result.add(convertToBigInteger(v));
        }
        return result;
    }
    
    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        return new BigDecimal(value.toString());
    }
    
    private List<BigDecimal> convertToBigDecimalList(List<?> values) {
        List<BigDecimal> result = new ArrayList<>();
        for (Object v : values) {
            result.add(convertToBigDecimal(v));
        }
        return result;
    }
    
    private GregorianCalendar convertToCalendar(Object value) {
        if (value instanceof GregorianCalendar) return (GregorianCalendar) value;
        try {
            Instant instant = Instant.from(ISO_DATE_FORMAT.parse(value.toString()));
            GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            cal.setTimeInMillis(instant.toEpochMilli());
            return cal;
        } catch (Exception e) {
            throw ApiException.invalidArgument("Invalid date format: " + value);
        }
    }
    
    private List<GregorianCalendar> convertToCalendarList(List<?> values) {
        List<GregorianCalendar> result = new ArrayList<>();
        for (Object v : values) {
            result.add(convertToCalendar(v));
        }
        return result;
    }
}
