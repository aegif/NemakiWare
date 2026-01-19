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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectListResponse;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/query")
@Tag(name = "query", description = "CMIS query and discovery operations")
@Produces(MediaType.APPLICATION_JSON)
public class QueryResource {
    
    private static final Logger logger = Logger.getLogger(QueryResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Autowired
    private DiscoveryService discoveryService;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Execute CMIS query",
            description = "Executes a CMIS query statement against the contents of the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Query results",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid query",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response query(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Search all versions")
            @QueryParam("searchAllVersions") @DefaultValue("false") Boolean searchAllVersions,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount,
            Map<String, Object> requestBody) {
        
        logger.info("API v1: Executing query in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            String statement = null;
            if (requestBody != null && requestBody.containsKey("statement")) {
                statement = (String) requestBody.get("statement");
            }
            
            if (statement == null || statement.trim().isEmpty()) {
                throw ApiException.invalidArgument("Query statement is required");
            }
            
            ObjectList results = discoveryService.query(
                    callContext, repositoryId, statement, searchAllVersions,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            List<ObjectResponse> objectResponses = new ArrayList<>();
            if (results != null && results.getObjects() != null) {
                for (ObjectData obj : results.getObjects()) {
                    objectResponses.add(mapToObjectResponse(obj, repositoryId, includeAllowableActions));
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(objectResponses);
            response.setNumItems(results != null && results.getNumItems() != null 
                    ? results.getNumItems().longValue() : (long) objectResponses.size());
            response.setHasMoreItems(results != null && results.hasMoreItems() != null 
                    ? results.hasMoreItems() : false);
            response.setSkipCount((long) skipCount);
            response.setMaxItems((long) maxItems);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query"));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error executing query: " + e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("parse") || e.getMessage().contains("syntax"))) {
                throw ApiException.invalidArgument("Invalid query syntax: " + e.getMessage());
            }
            throw ApiException.internalError("Failed to execute query: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Operation(
            summary = "Execute CMIS query (GET)",
            description = "Executes a CMIS query statement against the contents of the repository using GET method"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Query results",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid query",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response queryGet(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "CMIS query statement", required = true, example = "SELECT * FROM cmis:document")
            @QueryParam("statement") String statement,
            @Parameter(description = "Search all versions")
            @QueryParam("searchAllVersions") @DefaultValue("false") Boolean searchAllVersions,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        logger.info("API v1: Executing query (GET) in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (statement == null || statement.trim().isEmpty()) {
                throw ApiException.invalidArgument("Query statement is required");
            }
            
            ObjectList results = discoveryService.query(
                    callContext, repositoryId, statement, searchAllVersions,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            List<ObjectResponse> objectResponses = new ArrayList<>();
            if (results != null && results.getObjects() != null) {
                for (ObjectData obj : results.getObjects()) {
                    objectResponses.add(mapToObjectResponse(obj, repositoryId, includeAllowableActions));
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(objectResponses);
            response.setNumItems(results != null && results.getNumItems() != null 
                    ? results.getNumItems().longValue() : (long) objectResponses.size());
            response.setHasMoreItems(results != null && results.hasMoreItems() != null 
                    ? results.hasMoreItems() : false);
            response.setSkipCount((long) skipCount);
            response.setMaxItems((long) maxItems);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query?statement=" + java.net.URLEncoder.encode(statement, "UTF-8") + "&maxItems=" + maxItems + "&skipCount=" + skipCount));
            
            if (response.getHasMoreItems()) {
                int nextSkip = skipCount + maxItems;
                links.put("next", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query?statement=" + java.net.URLEncoder.encode(statement, "UTF-8") + "&maxItems=" + maxItems + "&skipCount=" + nextSkip));
            }
            if (skipCount > 0) {
                int prevSkip = Math.max(0, skipCount - maxItems);
                links.put("prev", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query?statement=" + java.net.URLEncoder.encode(statement, "UTF-8") + "&maxItems=" + maxItems + "&skipCount=" + prevSkip));
            }
            
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error executing query: " + e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("parse") || e.getMessage().contains("syntax"))) {
                throw ApiException.invalidArgument("Invalid query syntax: " + e.getMessage());
            }
            throw ApiException.internalError("Failed to execute query: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/changes")
    @Operation(
            summary = "Get content changes",
            description = "Gets the list of objects that have changed since a given point in the past"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content changes",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            )
    })
    public Response getContentChanges(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Change log token (null for first call)")
            @QueryParam("changeLogToken") String changeLogToken,
            @Parameter(description = "Include properties")
            @QueryParam("includeProperties") @DefaultValue("false") Boolean includeProperties,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include policy IDs")
            @QueryParam("includePolicyIds") @DefaultValue("false") Boolean includePolicyIds,
            @Parameter(description = "Include ACL")
            @QueryParam("includeAcl") @DefaultValue("false") Boolean includeAcl,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems) {
        
        logger.info("API v1: Getting content changes for repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<String> changeLogTokenHolder = new Holder<>(changeLogToken);
            
            ObjectList changes = discoveryService.getContentChanges(
                    callContext, repositoryId, changeLogTokenHolder,
                    includeProperties, filter, includePolicyIds, includeAcl,
                    BigInteger.valueOf(maxItems), null);
            
            List<Map<String, Object>> changeEntries = new ArrayList<>();
            if (changes != null && changes.getObjects() != null) {
                for (ObjectData obj : changes.getObjects()) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("object", mapToObjectResponse(obj, repositoryId, false));
                    if (obj.getChangeEventInfo() != null) {
                        Map<String, Object> changeInfo = new HashMap<>();
                        changeInfo.put("changeType", obj.getChangeEventInfo().getChangeType() != null 
                                ? obj.getChangeEventInfo().getChangeType().value() : null);
                        changeInfo.put("changeTime", obj.getChangeEventInfo().getChangeTime() != null 
                                ? formatDate(obj.getChangeEventInfo().getChangeTime()) : null);
                        entry.put("changeEventInfo", changeInfo);
                    }
                    changeEntries.add(entry);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("changes", changeEntries);
            response.put("changeLogToken", changeLogTokenHolder.getValue());
            response.put("numItems", changes != null && changes.getNumItems() != null 
                    ? changes.getNumItems().longValue() : (long) changeEntries.size());
            response.put("hasMoreItems", changes != null && changes.hasMoreItems() != null 
                    ? changes.hasMoreItems() : false);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query/changes"));
            
            if (changeLogTokenHolder.getValue() != null) {
                links.put("next", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/query/changes?changeLogToken=" + changeLogTokenHolder.getValue() + "&maxItems=" + maxItems));
            }
            
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting content changes: " + e.getMessage());
            throw ApiException.internalError("Failed to get content changes: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (!repositoryService.hasThisRepositoryId(repositoryId)) {
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
    
    private String formatDate(GregorianCalendar cal) {
        if (cal == null) return null;
        synchronized (ISO_DATE_FORMAT) {
            return ISO_DATE_FORMAT.format(cal.getTime());
        }
    }
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId, Boolean includeAllowableActions) {
        ObjectResponse response = new ObjectResponse();
        
        Properties properties = objectData.getProperties();
        if (properties != null) {
            response.setObjectId(getStringProperty(properties, PropertyIds.OBJECT_ID));
            response.setBaseTypeId(getStringProperty(properties, PropertyIds.BASE_TYPE_ID));
            response.setObjectTypeId(getStringProperty(properties, PropertyIds.OBJECT_TYPE_ID));
            response.setName(getStringProperty(properties, PropertyIds.NAME));
            response.setDescription(getStringProperty(properties, PropertyIds.DESCRIPTION));
            response.setCreatedBy(getStringProperty(properties, PropertyIds.CREATED_BY));
            response.setCreationDate(getDateProperty(properties, PropertyIds.CREATION_DATE));
            response.setLastModifiedBy(getStringProperty(properties, PropertyIds.LAST_MODIFIED_BY));
            response.setLastModificationDate(getDateProperty(properties, PropertyIds.LAST_MODIFICATION_DATE));
            response.setChangeToken(getStringProperty(properties, PropertyIds.CHANGE_TOKEN));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        if (objectId != null) {
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
            response.setLinks(links);
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
        } else if (prop.getFirstValue() instanceof Long || prop.getFirstValue() instanceof Integer) {
            type = "integer";
        } else if (prop.getFirstValue() instanceof Double || prop.getFirstValue() instanceof Float) {
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
