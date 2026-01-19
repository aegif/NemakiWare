package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/types")
@Tag(name = "types", description = "CMIS type definition operations")
@Produces(MediaType.APPLICATION_JSON)
public class TypeResource {
    
    private static final Logger logger = Logger.getLogger(TypeResource.class.getName());
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Operation(
            summary = "Get type children",
            description = "Gets the list of child types for the specified type (or root types if no typeId specified)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of type definitions",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Type not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getTypeChildren(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent type ID (null for root types)")
            @QueryParam("typeId") String typeId,
            @Parameter(description = "Include property definitions")
            @QueryParam("includePropertyDefinitions") @DefaultValue("false") Boolean includePropertyDefinitions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        logger.info("API v1: Getting type children for repository " + repositoryId + ", typeId=" + typeId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            TypeDefinitionList typeList = repositoryService.getTypeChildren(
                    callContext, repositoryId, typeId, includePropertyDefinitions,
                    BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            List<Map<String, Object>> types = new ArrayList<>();
            if (typeList != null && typeList.getList() != null) {
                for (TypeDefinition typeDef : typeList.getList()) {
                    types.add(mapTypeDefinition(typeDef, repositoryId, includePropertyDefinitions));
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("types", types);
            response.put("numItems", typeList != null && typeList.getNumItems() != null 
                    ? typeList.getNumItems().longValue() : (long) types.size());
            response.put("hasMoreItems", typeList != null && typeList.hasMoreItems() != null 
                    ? typeList.hasMoreItems() : false);
            response.put("skipCount", skipCount);
            response.put("maxItems", maxItems);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            String selfUrl = baseUri + "repositories/" + repositoryId + "/types?maxItems=" + maxItems + "&skipCount=" + skipCount;
            if (typeId != null) {
                selfUrl += "&typeId=" + typeId;
            }
            links.put("self", LinkInfo.of(selfUrl));
            
            if (typeList != null && typeList.hasMoreItems() != null && typeList.hasMoreItems()) {
                int nextSkip = skipCount + maxItems;
                String nextUrl = baseUri + "repositories/" + repositoryId + "/types?maxItems=" + maxItems + "&skipCount=" + nextSkip;
                if (typeId != null) {
                    nextUrl += "&typeId=" + typeId;
                }
                links.put("next", LinkInfo.of(nextUrl));
            }
            
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting type children: " + e.getMessage());
            throw ApiException.internalError("Failed to get type children: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{typeId}")
    @Operation(
            summary = "Get type definition",
            description = "Gets the definition of the specified type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Type definition",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Type not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getTypeDefinition(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Type ID", required = true, example = "cmis:document")
            @PathParam("typeId") String typeId) {
        
        logger.info("API v1: Getting type definition for " + typeId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            TypeDefinition typeDef = repositoryService.getTypeDefinition(
                    callContext, repositoryId, typeId, null);
            
            if (typeDef == null) {
                throw ApiException.typeNotFound(typeId, repositoryId);
            }
            
            Map<String, Object> response = mapTypeDefinition(typeDef, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting type definition for " + typeId + ": " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                throw ApiException.typeNotFound(typeId, repositoryId);
            }
            throw ApiException.internalError("Failed to get type definition: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{typeId}/children")
    @Operation(
            summary = "Get type children",
            description = "Gets the list of child types for the specified type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of child type definitions",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Type not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getTypeChildrenByTypeId(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Type ID", required = true, example = "cmis:document")
            @PathParam("typeId") String typeId,
            @Parameter(description = "Include property definitions")
            @QueryParam("includePropertyDefinitions") @DefaultValue("false") Boolean includePropertyDefinitions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        logger.info("API v1: Getting children of type " + typeId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            TypeDefinitionList typeList = repositoryService.getTypeChildren(
                    callContext, repositoryId, typeId, includePropertyDefinitions,
                    BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            List<Map<String, Object>> types = new ArrayList<>();
            if (typeList != null && typeList.getList() != null) {
                for (TypeDefinition typeDef : typeList.getList()) {
                    types.add(mapTypeDefinition(typeDef, repositoryId, includePropertyDefinitions));
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("parentTypeId", typeId);
            response.put("types", types);
            response.put("numItems", typeList != null && typeList.getNumItems() != null 
                    ? typeList.getNumItems().longValue() : (long) types.size());
            response.put("hasMoreItems", typeList != null && typeList.hasMoreItems() != null 
                    ? typeList.hasMoreItems() : false);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeId + "/children?maxItems=" + maxItems + "&skipCount=" + skipCount));
            links.put("type", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeId));
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting children of type " + typeId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get type children: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{typeId}/descendants")
    @Operation(
            summary = "Get type descendants",
            description = "Gets the set of descendant types for the specified type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Tree of descendant type definitions",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Type not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getTypeDescendants(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Type ID", required = true, example = "cmis:document")
            @PathParam("typeId") String typeId,
            @Parameter(description = "Depth of descendants to return (-1 for all)")
            @QueryParam("depth") @DefaultValue("-1") Integer depth,
            @Parameter(description = "Include property definitions")
            @QueryParam("includePropertyDefinitions") @DefaultValue("false") Boolean includePropertyDefinitions) {
        
        logger.info("API v1: Getting descendants of type " + typeId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            List<TypeDefinitionContainer> descendants = repositoryService.getTypeDescendants(
                    callContext, repositoryId, typeId, BigInteger.valueOf(depth),
                    includePropertyDefinitions, null);
            
            List<Map<String, Object>> descendantTree = mapTypeDescendants(descendants, repositoryId, includePropertyDefinitions);
            
            Map<String, Object> response = new HashMap<>();
            response.put("typeId", typeId);
            response.put("depth", depth);
            response.put("descendants", descendantTree);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeId + "/descendants?depth=" + depth));
            links.put("type", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeId));
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting descendants of type " + typeId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get type descendants: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{typeId}/schema")
    @Operation(
            summary = "Get OpenAPI schema for type",
            description = "Generates an OpenAPI-compatible JSON Schema for the specified CMIS type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "JSON Schema for the type",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Type not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getTypeSchema(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Type ID", required = true, example = "cmis:document")
            @PathParam("typeId") String typeId) {
        
        logger.info("API v1: Getting schema for type " + typeId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            TypeDefinition typeDef = repositoryService.getTypeDefinition(
                    callContext, repositoryId, typeId, null);
            
            if (typeDef == null) {
                throw ApiException.typeNotFound(typeId, repositoryId);
            }
            
            Map<String, Object> schema = generateJsonSchema(typeDef, repositoryId);
            return Response.ok(schema).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting schema for type " + typeId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get type schema: " + e.getMessage(), e);
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
    
    private Map<String, Object> mapTypeDefinition(TypeDefinition typeDef, String repositoryId, Boolean includePropertyDefinitions) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("id", typeDef.getId());
        result.put("localName", typeDef.getLocalName());
        result.put("localNamespace", typeDef.getLocalNamespace());
        result.put("displayName", typeDef.getDisplayName());
        result.put("queryName", typeDef.getQueryName());
        result.put("description", typeDef.getDescription());
        result.put("baseId", typeDef.getBaseTypeId() != null ? typeDef.getBaseTypeId().value() : null);
        result.put("parentId", typeDef.getParentTypeId());
        result.put("isCreatable", typeDef.isCreatable());
        result.put("isFileable", typeDef.isFileable());
        result.put("isQueryable", typeDef.isQueryable());
        result.put("isFulltextIndexed", typeDef.isFulltextIndexed());
        result.put("isIncludedInSupertypeQuery", typeDef.isIncludedInSupertypeQuery());
        result.put("isControllablePolicy", typeDef.isControllablePolicy());
        result.put("isControllableAcl", typeDef.isControllableAcl());
        
        if (includePropertyDefinitions != null && includePropertyDefinitions && typeDef.getPropertyDefinitions() != null) {
            Map<String, Object> propertyDefinitions = new HashMap<>();
            for (Map.Entry<String, PropertyDefinition<?>> entry : typeDef.getPropertyDefinitions().entrySet()) {
                propertyDefinitions.put(entry.getKey(), mapPropertyDefinition(entry.getValue()));
            }
            result.put("propertyDefinitions", propertyDefinitions);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeDef.getId()));
        links.put("children", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeDef.getId() + "/children"));
        links.put("descendants", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeDef.getId() + "/descendants"));
        links.put("schema", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeDef.getId() + "/schema"));
        if (typeDef.getParentTypeId() != null) {
            links.put("parent", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/types/" + typeDef.getParentTypeId()));
        }
        result.put("_links", links);
        
        return result;
    }
    
    private Map<String, Object> mapPropertyDefinition(PropertyDefinition<?> propDef) {
        Map<String, Object> result = new HashMap<>();
        
        result.put("id", propDef.getId());
        result.put("localName", propDef.getLocalName());
        result.put("localNamespace", propDef.getLocalNamespace());
        result.put("displayName", propDef.getDisplayName());
        result.put("queryName", propDef.getQueryName());
        result.put("description", propDef.getDescription());
        result.put("propertyType", propDef.getPropertyType() != null ? propDef.getPropertyType().value() : null);
        result.put("cardinality", propDef.getCardinality() != null ? propDef.getCardinality().value() : null);
        result.put("updatability", propDef.getUpdatability() != null ? propDef.getUpdatability().value() : null);
        result.put("isInherited", propDef.isInherited());
        result.put("isRequired", propDef.isRequired());
        result.put("isQueryable", propDef.isQueryable());
        result.put("isOrderable", propDef.isOrderable());
        result.put("isOpenChoice", propDef.isOpenChoice());
        
        return result;
    }
    
    private List<Map<String, Object>> mapTypeDescendants(List<TypeDefinitionContainer> containers, 
            String repositoryId, Boolean includePropertyDefinitions) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        if (containers != null) {
            for (TypeDefinitionContainer container : containers) {
                Map<String, Object> item = new HashMap<>();
                
                if (container.getTypeDefinition() != null) {
                    item.put("type", mapTypeDefinition(container.getTypeDefinition(), repositoryId, includePropertyDefinitions));
                }
                
                if (container.getChildren() != null && !container.getChildren().isEmpty()) {
                    item.put("children", mapTypeDescendants(container.getChildren(), repositoryId, includePropertyDefinitions));
                }
                
                result.add(item);
            }
        }
        
        return result;
    }
    
    private Map<String, Object> generateJsonSchema(TypeDefinition typeDef, String repositoryId) {
        Map<String, Object> schema = new HashMap<>();
        
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("$id", uriInfo.getBaseUri().toString() + "repositories/" + repositoryId + "/types/" + typeDef.getId() + "/schema");
        schema.put("title", typeDef.getDisplayName() != null ? typeDef.getDisplayName() : typeDef.getId());
        schema.put("description", typeDef.getDescription());
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        properties.put("objectId", Map.of("type", "string", "description", "Object ID"));
        properties.put("objectTypeId", Map.of("type", "string", "description", "Object type ID", "const", typeDef.getId()));
        properties.put("baseTypeId", Map.of("type", "string", "description", "Base type ID"));
        
        if (typeDef.getPropertyDefinitions() != null) {
            Map<String, Object> propertiesSchema = new HashMap<>();
            
            for (Map.Entry<String, PropertyDefinition<?>> entry : typeDef.getPropertyDefinitions().entrySet()) {
                PropertyDefinition<?> propDef = entry.getValue();
                Map<String, Object> propSchema = new HashMap<>();
                
                propSchema.put("type", "object");
                propSchema.put("description", propDef.getDescription() != null ? propDef.getDescription() : propDef.getDisplayName());
                
                Map<String, Object> propProperties = new HashMap<>();
                
                String jsonType = "string";
                if (propDef.getPropertyType() != null) {
                    switch (propDef.getPropertyType()) {
                        case BOOLEAN:
                            jsonType = "boolean";
                            break;
                        case INTEGER:
                            jsonType = "integer";
                            break;
                        case DECIMAL:
                            jsonType = "number";
                            break;
                        case DATETIME:
                            jsonType = "string";
                            propProperties.put("format", "date-time");
                            break;
                        default:
                            jsonType = "string";
                    }
                }
                
                if (propDef.getCardinality() != null && "multi".equals(propDef.getCardinality().value())) {
                    propProperties.put("value", Map.of("type", "array", "items", Map.of("type", jsonType)));
                } else {
                    propProperties.put("value", Map.of("type", jsonType));
                }
                
                propProperties.put("type", Map.of(
                        "type", "string",
                        "enum", List.of("string", "boolean", "integer", "decimal", "datetime", "uri", "id", "html")
                ));
                
                propSchema.put("properties", propProperties);
                propSchema.put("required", List.of("value", "type"));
                
                propertiesSchema.put(propDef.getId(), propSchema);
                
                if (propDef.isRequired() != null && propDef.isRequired()) {
                    required.add(propDef.getId());
                }
            }
            
            properties.put("properties", Map.of(
                    "type", "object",
                    "description", "Object properties with 2-layer structure (value/type)",
                    "properties", propertiesSchema,
                    "required", required
            ));
        }
        
        schema.put("properties", properties);
        schema.put("required", List.of("objectTypeId"));
        
        return schema;
    }
}
