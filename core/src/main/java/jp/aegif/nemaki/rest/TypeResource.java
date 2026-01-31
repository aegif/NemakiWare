package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.model.Choice;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import jp.aegif.nemaki.util.spring.SpringContext;

@Path("/repo/{repositoryId}/type")
public class TypeResource extends ResourceBase {

	private TypeService typeService;
	private TypeManager typeManager;
	private RepositoryService repositoryService;

	private final Log log = LogFactory.getLog(TypeResource.class);

	/**
	 * Ensure TypeService, TypeManager, and RepositoryService are initialized from Spring context
	 * This method supports lazy initialization for JAX-RS resources
	 */
	private void ensureServicesInitialized() {
		if (typeService == null) {
			try {
				typeService = (TypeService) SpringContext.getBean("typeService");
				log.info("TypeService initialized from Spring context");
			} catch (Exception e) {
				log.error("Failed to get TypeService from Spring context", e);
			}
		}

		if (typeManager == null) {
			try {
				typeManager = (TypeManager) SpringContext.getBean("typeManager");
				log.info("TypeManager initialized from Spring context");
			} catch (Exception e) {
				log.error("Failed to get TypeManager from Spring context", e);
			}
		}

		if (repositoryService == null) {
			try {
				repositoryService = (RepositoryService) SpringContext.getBean("repositoryService");
				log.info("RepositoryService initialized from Spring context");
			} catch (Exception e) {
				log.error("Failed to get RepositoryService from Spring context", e);
			}
		}
	}

	private final HashMap<String, NemakiTypeDefinition> typeMaps = new HashMap<String, NemakiTypeDefinition>();
	private final HashMap<String, NemakiPropertyDefinitionCore> coreMaps = new HashMap<String, NemakiPropertyDefinitionCore>();
	private final HashMap<String, NemakiPropertyDefinitionDetail> detailMaps = new HashMap<String, NemakiPropertyDefinitionDetail>();
	private final HashMap<String, List<String>> typeProperties = new HashMap<String, List<String>>();

	@GET
	@Path("/test")
	@Produces(MediaType.APPLICATION_JSON)
	public String test(@PathParam("repositoryId") String repositoryId) {
		log.info("TypeResource test endpoint called for repository: " + repositoryId);

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		JSONObject result = new JSONObject();
		result.put("status", "success");
		result.put("message", "TypeResource test endpoint is working");
		result.put("repositoryId", repositoryId);
		result.put("typeServiceNull", (typeService == null));
		result.put("typeManagerNull", (typeManager == null));

		return result.toJSONString();
	}

	// New CRUD endpoints for UI management

	/**
	 * Get all type definitions (including base CMIS types and custom types)
	 */
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response list(@PathParam("repositoryId") String repositoryId) {
		log.info("TypeResource.list() called for repository: " + repositoryId);

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		try {

			if (typeService == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "TypeService not available");
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorResult.toJSONString()).build();
			}

			JSONArray typesArray = new JSONArray();
			// CRITICAL FIX (2025-12-22): Track added type IDs to prevent duplicates
			// This prevents nemaki:parentChildRelationship and other types from appearing twice
			java.util.Set<String> addedTypeIds = new java.util.HashSet<>();

			// Get base CMIS types via RepositoryService
			if (repositoryService != null) {
				try {
					// Get all base types (typeId=null returns base types)
					org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList baseTypes =
						repositoryService.getTypeChildren(null, repositoryId, null, true, null, null, null);

					if (baseTypes != null && baseTypes.getList() != null) {
						log.info("Found " + baseTypes.getList().size() + " base CMIS types");
						for (org.apache.chemistry.opencmis.commons.definitions.TypeDefinition baseType : baseTypes.getList()) {
							String typeId = baseType.getId();
							if (!addedTypeIds.contains(typeId)) {
								JSONObject typeJson = convertBaseTypeToJson(baseType);
								typesArray.add(typeJson);
								addedTypeIds.add(typeId);
							}
						}
					}
				} catch (Exception e) {
					log.warn("Failed to retrieve base types, continuing with custom types only: " + e.getMessage());
				}
			} else {
				log.warn("RepositoryService not available, skipping base types");
			}

			// Get custom types from TypeService
			List<NemakiTypeDefinition> customTypes = typeService.getTypeDefinitions(repositoryId);
			log.info("Found " + customTypes.size() + " custom types");

			for (NemakiTypeDefinition nemakiType : customTypes) {
				String typeId = nemakiType.getTypeId();
				// CRITICAL FIX: Skip types already added from base types to prevent duplicates
				if (!addedTypeIds.contains(typeId)) {
					JSONObject typeJson = convertTypeToJson(repositoryId, nemakiType);
					typesArray.add(typeJson);
					addedTypeIds.add(typeId);
				} else {
					log.debug("Skipping duplicate type: " + typeId);
				}
			}

			JSONObject result = new JSONObject();
			result.put("types", typesArray);
			result.put("status", "success");
			log.info("Returning " + typesArray.size() + " total types (base + custom)");

			return Response.status(Response.Status.OK)
					.entity(result.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();

		} catch (Exception e) {
			log.error("Exception occurred in list(): " + e.getMessage(), e);

			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to retrieve type list");
			errorResult.put("error", e.getMessage());

			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	/**
	 * Get specific type definition
	 */
	@GET
	@Path("/show/{typeId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response show(@PathParam("repositoryId") String repositoryId, @PathParam("typeId") String typeId) {
		log.info("TypeResource.show() called for repository: " + repositoryId + ", typeId: " + typeId);

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		try {

			if (typeService == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "TypeService not available");
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorResult.toJSONString()).build();
			}

			NemakiTypeDefinition nemakiType = typeService.getTypeDefinition(repositoryId, typeId);
			if (nemakiType == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Type definition not found: " + typeId);
				return Response.status(Response.Status.NOT_FOUND)
						.entity(errorResult.toJSONString()).build();
			}
			
			JSONObject result = new JSONObject();
			result.put("type", convertTypeToJson(repositoryId, nemakiType));
			result.put("status", "success");
			
			return Response.status(Response.Status.OK)
					.entity(result.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
			
		} catch (Exception e) {
			log.error("Exception occurred in show(): " + e.getMessage(), e);
			
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to retrieve type definition");
			errorResult.put("error", e.getMessage());
			
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	/**
	 * Create new type definition
	 */
	@POST
	@Path("/create")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response create(@PathParam("repositoryId") String repositoryId, String jsonInput) {
		log.info("TypeResource.create() called for repository: " + repositoryId);

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		try {

			if (typeService == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "TypeService not available");
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorResult.toJSONString()).build();
			}

			// Use existing JSON parsing logic
			parseJson(repositoryId, jsonInput);
			
			// Check if type already exists  
			for (String typeId : typeMaps.keySet()) {
				if (existType(repositoryId, typeId)) {
					JSONObject errorResult = new JSONObject();
					errorResult.put("status", "error");
					errorResult.put("message", "Type definition already exists: " + typeId);
					return Response.status(Response.Status.CONFLICT)
							.entity(errorResult.toJSONString()).build();
				}
			}
			
			// Create type definition
			create(repositoryId);
			
			// Refresh type manager
			if (typeManager != null) {
				typeManager.refreshTypes();
			}
			
			JSONObject result = new JSONObject();
			result.put("status", "success");
			result.put("message", "Type definition created successfully");
			
			return Response.status(Response.Status.OK)
					.entity(result.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
			
		} catch (Exception e) {
			log.error("Exception occurred in create(): " + e.getMessage(), e);
			
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to create type definition");
			errorResult.put("error", e.getMessage());
			
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	/**
	 * Update existing type definition
	 * NOTE: This is a NemakiWare-specific operation that goes beyond CMIS standard compliance.
	 * CMIS standard does not allow modification of type definitions after creation.
	 */
	@PUT
	@Path("/update/{typeId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response update(@PathParam("repositoryId") String repositoryId, @PathParam("typeId") String typeId, String jsonInput) {
		log.info("TypeResource.update() called for repository: " + repositoryId + ", typeId: " + typeId);
		log.warn("NOTE: Type update is a NemakiWare-specific operation that goes beyond CMIS standard compliance");

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		try {

			if (typeService == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "TypeService not available");
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorResult.toJSONString()).build();
			}

			// Check if type exists
			NemakiTypeDefinition existingType = typeService.getTypeDefinition(repositoryId, typeId);
			if (existingType == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Type definition not found: " + typeId);
				return Response.status(Response.Status.NOT_FOUND)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Check if type is a base type (cannot be updated)
			if (isBaseType(typeId)) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Cannot update base type: " + typeId);
				return Response.status(Response.Status.BAD_REQUEST)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Clear maps before parsing new data
			typeMaps.clear();
			coreMaps.clear();
			detailMaps.clear();
			typeProperties.clear();
			
			// Parse JSON input with update mode enabled
			parseJsonForUpdate(repositoryId, jsonInput);
			
			// Update type definition
			updateTypeDefinition(repositoryId, existingType);
			
			// Refresh type manager
			if (typeManager != null) {
				typeManager.refreshTypes();
			}
			
			JSONObject result = new JSONObject();
			result.put("status", "success");
			result.put("message", "Type definition updated successfully");
			result.put("typeId", typeId);
			result.put("warning", "This operation is NemakiWare-specific and not CMIS-compliant. Existing documents may be affected.");
			
			return Response.status(Response.Status.OK)
					.entity(result.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
			
		} catch (Exception e) {
			log.error("Exception occurred in update(): " + e.getMessage(), e);
			
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to update type definition");
			errorResult.put("error", e.getMessage());
			
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	/**
	 * Delete type definition
	 * NOTE: This is a NemakiWare-specific operation that goes beyond CMIS standard compliance.
	 * Subtypes must be deleted first before deleting a parent type.
	 * Relationship types referencing this type must be updated/deleted first.
	 */
	@DELETE
	@Path("/delete/{typeId}")
	@Produces(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public Response delete(@PathParam("repositoryId") String repositoryId, @PathParam("typeId") String typeId) {
		log.info("TypeResource.delete() called for repository: " + repositoryId + ", typeId: " + typeId);
		log.warn("NOTE: Type deletion is a NemakiWare-specific operation that goes beyond CMIS standard compliance");

		// Initialize services from Spring context if not already injected
		ensureServicesInitialized();

		try {

			if (typeService == null) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "TypeService not available");
				return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
						.entity(errorResult.toJSONString()).build();
			}

			// Check if type exists
			if (!existType(repositoryId, typeId)) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Type definition not found: " + typeId);
				return Response.status(Response.Status.NOT_FOUND)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Check if type is a base type (cannot be deleted)
			if (isBaseType(typeId)) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Cannot delete base type: " + typeId);
				return Response.status(Response.Status.BAD_REQUEST)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Check for subtypes - subtypes must be deleted first
			List<String> subtypeIds = getSubtypeIds(repositoryId, typeId);
			if (!subtypeIds.isEmpty()) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Cannot delete type '" + typeId + "' because it has subtypes. Delete the following subtypes first: " + String.join(", ", subtypeIds));
				JSONArray subtypesArray = new JSONArray();
				subtypesArray.addAll(subtypeIds);
				errorResult.put("subtypes", subtypesArray);
				return Response.status(Response.Status.CONFLICT)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Check for relationship types referencing this type
			List<String> referencingRelationshipTypes = getReferencingRelationshipTypes(repositoryId, typeId);
			if (!referencingRelationshipTypes.isEmpty()) {
				JSONObject errorResult = new JSONObject();
				errorResult.put("status", "error");
				errorResult.put("message", "Cannot delete type '" + typeId + "' because it is referenced by relationship types. Update or delete the following relationship types first: " + String.join(", ", referencingRelationshipTypes));
				JSONArray relationshipsArray = new JSONArray();
				relationshipsArray.addAll(referencingRelationshipTypes);
				errorResult.put("referencingRelationships", relationshipsArray);
				return Response.status(Response.Status.CONFLICT)
						.entity(errorResult.toJSONString()).build();
			}
			
			// Delete type definition
			typeService.deleteTypeDefinition(repositoryId, typeId);
			
			// Refresh type manager
			if (typeManager != null) {
				typeManager.refreshTypes();
			}
			
			JSONObject result = new JSONObject();
			result.put("status", "success");
			result.put("message", "Type definition deleted successfully");
			result.put("typeId", typeId);
			result.put("warning", "This operation is NemakiWare-specific and not CMIS-compliant. Existing documents with this type will fall back to base type behavior.");
			
			return Response.status(Response.Status.OK)
					.entity(result.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
			
		} catch (Exception e) {
			log.error("Exception occurred in delete(): " + e.getMessage(), e);
			
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to delete type definition");
			errorResult.put("error", e.getMessage());
			
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}
	
	/**
	 * Get list of subtype IDs for a given type
	 * This method fails closed - if there's an error checking subtypes, it throws an exception
	 * to prevent accidental deletion of types that may have subtypes.
	 * @param repositoryId Repository ID
	 * @param typeId Type ID to check for subtypes
	 * @return List of subtype IDs (empty if no subtypes)
	 * @throws Exception if unable to check for subtypes (fail closed for safety)
	 */
	private List<String> getSubtypeIds(String repositoryId, String typeId) throws Exception {
		List<String> subtypeIds = new ArrayList<String>();
		
		// Get all type definitions and check which ones have this type as parent
		// Note: This method intentionally does NOT catch exceptions - if we can't check
		// for subtypes, we should fail the deletion rather than risk deleting a parent type
		List<NemakiTypeDefinition> allTypes = typeService.getTypeDefinitions(repositoryId);
		if (allTypes == null) {
			throw new Exception("Unable to retrieve type definitions to check for subtypes");
		}
		for (NemakiTypeDefinition type : allTypes) {
			if (typeId.equals(type.getParentId())) {
				subtypeIds.add(type.getTypeId());
			}
		}
		
		return subtypeIds;
	}
	
	/**
	 * Get list of relationship type IDs that reference a given type in allowedSourceTypes or allowedTargetTypes
	 * This method fails closed - if there's an error checking references, it throws an exception
	 * to prevent accidental deletion of types that may be referenced.
	 * @param repositoryId Repository ID
	 * @param typeId Type ID to check for references
	 * @return List of relationship type IDs that reference this type (empty if none)
	 * @throws Exception if unable to check for references (fail closed for safety)
	 */
	private List<String> getReferencingRelationshipTypes(String repositoryId, String typeId) throws Exception {
		List<String> referencingTypes = new ArrayList<String>();
		
		// Get all type definitions and check relationship types
		// Note: This method intentionally does NOT catch exceptions - if we can't check
		// for references, we should fail the deletion rather than risk breaking relationships
		List<NemakiTypeDefinition> allTypes = typeService.getTypeDefinitions(repositoryId);
		if (allTypes == null) {
			throw new Exception("Unable to retrieve type definitions to check for relationship references");
		}
		for (NemakiTypeDefinition type : allTypes) {
			// Only check relationship types
			if (BaseTypeId.CMIS_RELATIONSHIP.equals(type.getBaseId())) {
				// Check allowedSourceTypes
				List<String> allowedSourceTypes = type.getAllowedSourceTypes();
				if (allowedSourceTypes != null && allowedSourceTypes.contains(typeId)) {
					referencingTypes.add(type.getTypeId());
					continue; // Don't add twice if both source and target reference the type
				}
				
				// Check allowedTargetTypes
				List<String> allowedTargetTypes = type.getAllowedTargetTypes();
				if (allowedTargetTypes != null && allowedTargetTypes.contains(typeId)) {
					referencingTypes.add(type.getTypeId());
				}
			}
		}
		
		return referencingTypes;
	}

	// Helper methods for JSON conversion

	@SuppressWarnings("unchecked")
	private JSONObject convertTypeToJson(String repositoryId, NemakiTypeDefinition nemakiType) {
		JSONObject typeJson = new JSONObject();
		
		typeJson.put("id", nemakiType.getTypeId());
		typeJson.put("localName", nemakiType.getLocalName());
		typeJson.put("displayName", nemakiType.getDisplayName());
		typeJson.put("description", nemakiType.getDescription());
		typeJson.put("baseTypeId", nemakiType.getBaseId() != null ? nemakiType.getBaseId().value() : null);
		typeJson.put("parentTypeId", nemakiType.getParentId());
		
		typeJson.put("creatable", nemakiType.isCreatable());
		typeJson.put("queryable", nemakiType.isQueryable());
		typeJson.put("controllableAcl", nemakiType.isControllableACL());
		typeJson.put("controllablePolicy", nemakiType.isControllablePolicy());
		typeJson.put("fulltextIndexed", nemakiType.isFulltextIndexed());
		typeJson.put("includedInSupertypeQuery", nemakiType.isIncludedInSupertypeQuery());
		
		// Property definitions
		JSONArray propertiesArray = new JSONArray();
		List<String> propertyIds = nemakiType.getProperties();
		if (propertyIds != null && typeService != null) {
			for (String propertyId : propertyIds) {
				try {
					NemakiPropertyDefinitionDetail detail = typeService.getPropertyDefinitionDetail(repositoryId, propertyId);
					if (detail != null) {
						NemakiPropertyDefinitionCore core = typeService.getPropertyDefinitionCore(repositoryId, detail.getCoreNodeId());
						if (core != null) {
							JSONObject propJson = convertPropertyToJson(core, detail);
							propertiesArray.add(propJson);
						}
					}
				} catch (Exception e) {
					log.warn("Could not convert property " + propertyId + " to JSON: " + e.getMessage());
				}
			}
		}
		typeJson.put("propertyDefinitions", propertiesArray);
		
		return typeJson;
	}

	@SuppressWarnings("unchecked")
	private JSONObject convertPropertyToJson(NemakiPropertyDefinitionCore core, NemakiPropertyDefinitionDetail detail) {
		JSONObject propJson = new JSONObject();

		propJson.put("id", core.getPropertyId());
		propJson.put("localName", core.getPropertyId());
		propJson.put("displayName", core.getPropertyId());
		propJson.put("description", "");
		propJson.put("propertyType", core.getPropertyType() != null ? core.getPropertyType().value() : null);
		propJson.put("cardinality", core.getCardinality() != null ? core.getCardinality().value() : null);
		propJson.put("updatability", detail.getUpdatability() != null ? detail.getUpdatability().value() : null);

		propJson.put("required", detail.isRequired());
		propJson.put("queryable", detail.isQueryable());
		propJson.put("orderable", false); // Not available in detail
		propJson.put("inherited", false);

		return propJson;
	}

	/**
	 * Convert base CMIS TypeDefinition to JSON format
	 * This handles base types returned from RepositoryService.getTypeChildren()
	 */
	@SuppressWarnings("unchecked")
	private JSONObject convertBaseTypeToJson(org.apache.chemistry.opencmis.commons.definitions.TypeDefinition typeDef) {
		JSONObject typeJson = new JSONObject();

		// Basic type information
		typeJson.put("id", typeDef.getId());
		typeJson.put("localName", typeDef.getLocalName());
		typeJson.put("displayName", typeDef.getDisplayName());
		typeJson.put("description", typeDef.getDescription());
		typeJson.put("baseTypeId", typeDef.getBaseTypeId() != null ? typeDef.getBaseTypeId().value() : null);
		typeJson.put("parentTypeId", typeDef.getParentTypeId());

		// Boolean attributes
		typeJson.put("creatable", typeDef.isCreatable());
		typeJson.put("queryable", typeDef.isQueryable());
		typeJson.put("controllableAcl", typeDef.isControllableAcl());
		typeJson.put("controllablePolicy", typeDef.isControllablePolicy());
		typeJson.put("fulltextIndexed", typeDef.isFulltextIndexed());
		typeJson.put("includedInSupertypeQuery", typeDef.isIncludedInSupertypeQuery());

		// Property definitions - base types have property definitions in the TypeDefinition object
		JSONArray propertiesArray = new JSONArray();
		if (typeDef.getPropertyDefinitions() != null) {
			for (org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef : typeDef.getPropertyDefinitions().values()) {
				JSONObject propJson = new JSONObject();

				propJson.put("id", propDef.getId());
				propJson.put("localName", propDef.getLocalName());
				propJson.put("displayName", propDef.getDisplayName());
				propJson.put("description", propDef.getDescription());
				propJson.put("propertyType", propDef.getPropertyType() != null ? propDef.getPropertyType().value() : null);
				propJson.put("cardinality", propDef.getCardinality() != null ? propDef.getCardinality().value() : null);
				propJson.put("updatability", propDef.getUpdatability() != null ? propDef.getUpdatability().value() : null);

				propJson.put("required", propDef.isRequired());
				propJson.put("queryable", propDef.isQueryable());
				propJson.put("orderable", propDef.isOrderable());
				propJson.put("inherited", propDef.isInherited());

				propertiesArray.add(propJson);
			}
		}
		typeJson.put("propertyDefinitions", propertiesArray);

		return typeJson;
	}

	@POST
	@Path("/register-json")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public String registerJson(@PathParam("repositoryId") String repositoryId, String jsonData) {
		log.info("registerJson method called for repository: " + repositoryId);
		log.debug("JSON Data received: " + (jsonData != null ? jsonData.length() + " characters" : "null"));

		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			if (jsonData == null || jsonData.trim().isEmpty()) {
				log.error("JSON data is null or empty");
				addErrMsg(errMsg, "types", "noDataReceived");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			
			if (typeService == null || typeManager == null) {
				log.error("CRITICAL: TypeService or TypeManager is null - dependency injection failed");
				addErrMsg(errMsg, "types", "dependencyInjectionFailed");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			try {
				log.info("Starting JSON parsing...");
				
				// JSONをパースして NemakiTypeDefinition に変換
				parseJson(repositoryId, jsonData);
				log.info("JSON parsing completed successfully");
				
				log.info("Starting type creation...");
				
				log.debug("About to call create method");
				try {
					create(repositoryId);
					log.debug("Create method completed");
				} catch (Exception createException) {
					log.error("Create exception: " + createException.getMessage(), createException);
					throw createException;
				}
				log.info("Type creation completed successfully");
				
				log.info("Refreshing type manager...");
				typeManager.refreshTypes();
				log.info("Type registration completed successfully");

				result = makeResult(true, result, errMsg);
				return result.toJSONString();
			} catch (Exception e) {
				log.warn("Type registrations fails - TypeService null: " + (typeService == null) + ", TypeManager null: " + (typeManager == null), e);
				addErrMsg(errMsg, "types", "failsToRegister");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
		} catch (Exception globalException) {
			log.error("Global exception in registerJson method", globalException);
			addErrMsg(errMsg, "types", "globalException");
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}
	}

	@POST
	@Path("/register-simple")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_XML)
	public String registerSimple(@PathParam("repositoryId") String repositoryId, String xmlData) {
		log.info("registerSimple method called for repository: " + repositoryId);
		log.debug("XML Data received: " + (xmlData != null ? xmlData.length() + " characters" : "null"));

		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			if (xmlData == null || xmlData.trim().isEmpty()) {
				log.error("XML data is null or empty");
				addErrMsg(errMsg, "types", "noDataReceived");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
			
			if (typeService == null || typeManager == null) {
				log.error("CRITICAL: TypeService or TypeManager is null - dependency injection failed");
				addErrMsg(errMsg, "types", "dependencyInjectionFailed");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			try {
				log.info("Starting XML parsing...");
				
				// XMLをInputStreamに変換
				java.io.InputStream is = new java.io.ByteArrayInputStream(xmlData.getBytes("UTF-8"));
				parse(repositoryId, is);
				log.info("XML parsing completed successfully");
				
				log.info("Starting type creation...");
				
				log.debug("About to call create method");
				try {
					create(repositoryId);
					log.debug("Create method completed");
				} catch (Exception createException) {
					log.error("Create method exception: " + createException.getMessage(), createException);
					throw createException;
				}
				log.info("Type creation completed successfully");
				
				log.info("Refreshing type manager...");
				typeManager.refreshTypes();
				log.info("Type registration completed successfully");

				result = makeResult(true, result, errMsg);
				return result.toJSONString();
			} catch (Exception e) {
				log.warn("Type registrations fails - TypeService null: " + (typeService == null) + ", TypeManager null: " + (typeManager == null), e);
				addErrMsg(errMsg, "types", "failsToRegister");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
		} catch (Exception globalException) {
			log.error("Global exception in registerSimple method", globalException);
			addErrMsg(errMsg, "types", "globalException");
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}
	}

	@POST
	@Path("/register")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public String register(@PathParam("repositoryId") String repositoryId, @FormDataParam("data") InputStream is) {
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			// Initialize services from Spring context before checking
			ensureServicesInitialized();

			log.debug("register method called for repository: " + repositoryId);

			if (is == null) {
				log.error("InputStream is null - multipart data not received");
				addErrMsg(errMsg, "types", "noDataReceived");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
		
			if (typeService == null || typeManager == null) {
				log.error("CRITICAL: TypeService or TypeManager is null - dependency injection failed");
				addErrMsg(errMsg, "types", "dependencyInjectionFailed");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}

			try {
				log.info("Starting XML parsing...");
				parse(repositoryId, is);
				log.info("XML parsing completed successfully");
				
				log.info("Starting type creation...");
				create(repositoryId);
				log.info("Type creation completed successfully");
				
				log.info("Refreshing type manager...");
				typeManager.refreshTypes();
				log.info("Type registration completed successfully");

				result = makeResult(true, result, errMsg);
				return result.toJSONString();
			} catch (Exception e) {
				log.warn("Type registrations fails - TypeService null: " + (typeService == null) + ", TypeManager null: " + (typeManager == null), e);
				addErrMsg(errMsg, "types", "failsToRegister");
				result = makeResult(false, result, errMsg);
				return result.toJSONString();
			}
		} catch (Exception globalException) {
			log.error("Global exception in register method", globalException);
			addErrMsg(errMsg, "types", "globalException");
			result = makeResult(false, result, errMsg);
			return result.toJSONString();
		}
	}

	/**
	 * Parse JSON type definition data (for restoring old functionality)
	 */
	private void parseJson(String repositoryId, String jsonData) throws Exception {
		log.debug("parseJson method called for repository: " + repositoryId);
		log.debug("JSON Data: " + jsonData);
		
		try {
			// Parse JSON using json-simple library (already available)
			org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
			Object parsed = parser.parse(jsonData);
			
			if (parsed instanceof JSONObject) {
				// Single type definition
				JSONObject typeJson = (JSONObject) parsed;
				log.debug("Parsing single type definition");
				parseJsonTypeDefinition(repositoryId, typeJson);
			} else if (parsed instanceof JSONArray) {
				// Multiple type definitions
				JSONArray typesArray = (JSONArray) parsed;
				log.debug("Parsing multiple type definitions: " + typesArray.size());
				for (Object typeObj : typesArray) {
					if (typeObj instanceof JSONObject) {
						parseJsonTypeDefinition(repositoryId, (JSONObject) typeObj);
					}
				}
			} else {
				throw new Exception("Invalid JSON format - expected object or array");
			}
			
			log.debug("JSON parsing completed - typeMaps: " + typeMaps.size() + ", coreMaps: " + coreMaps.size() + ", detailMaps: " + detailMaps.size());
			
		} catch (Exception e) {
			log.error("JSON parsing error: " + e.getMessage(), e);
			throw new Exception("Failed to parse JSON type definition: " + e.getMessage(), e);
		}
		
	}
	
	/**
	 * Parse JSON type definition data for update operation
	 * This method parses JSON with update mode enabled, allowing modification of existing types
	 */
	private void parseJsonForUpdate(String repositoryId, String jsonData) throws Exception {
		log.debug("parseJsonForUpdate method called for repository: " + repositoryId);
		
		try {
			org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
			Object parsed = parser.parse(jsonData);
			
			if (parsed instanceof JSONObject) {
				JSONObject typeJson = (JSONObject) parsed;
				log.debug("Parsing single type definition for update");
				parseJsonTypeDefinition(repositoryId, typeJson, true);
			} else if (parsed instanceof JSONArray) {
				JSONArray typesArray = (JSONArray) parsed;
				log.debug("Parsing multiple type definitions for update: " + typesArray.size());
				for (Object typeObj : typesArray) {
					if (typeObj instanceof JSONObject) {
						parseJsonTypeDefinition(repositoryId, (JSONObject) typeObj, true);
					}
				}
			} else {
				throw new Exception("Invalid JSON format - expected object or array");
			}
			
			log.debug("JSON parsing for update completed - typeMaps: " + typeMaps.size());
			
		} catch (Exception e) {
			log.error("JSON parsing error for update: " + e.getMessage(), e);
			throw new Exception("Failed to parse JSON type definition for update: " + e.getMessage(), e);
		}
	}

	/**
	 * Update an existing type definition with new data from typeMaps
	 * This method handles property definition updates (add new, update existing)
	 * @param repositoryId Repository ID
	 * @param existingType The existing type definition to update
	 */
	private void updateTypeDefinition(String repositoryId, NemakiTypeDefinition existingType) throws Exception {
		log.info("updateTypeDefinition called for type: " + existingType.getTypeId());
		
		if (typeMaps.isEmpty()) {
			throw new Exception("No type definition found in parsed data");
		}
		
		// Get the new type definition from typeMaps
		NemakiTypeDefinition newTypeDef = typeMaps.get(existingType.getTypeId());
		if (newTypeDef == null) {
			throw new Exception("Type definition not found in parsed data: " + existingType.getTypeId());
		}
		
		// Update basic properties on existing type (preserve ID and revision)
		existingType.setLocalName(newTypeDef.getLocalName());
		existingType.setLocalNameSpace(newTypeDef.getLocalNameSpace());
		existingType.setDisplayName(newTypeDef.getDisplayName());
		existingType.setDescription(newTypeDef.getDescription());
		existingType.setCreatable(newTypeDef.isCreatable());
		existingType.setQueryable(newTypeDef.isQueryable());
		existingType.setFulltextIndexed(newTypeDef.isFulltextIndexed());
		existingType.setIncludedInSupertypeQuery(newTypeDef.isIncludedInSupertypeQuery());
		existingType.setControllablePolicy(newTypeDef.isControllablePolicy());
		existingType.setControllableACL(newTypeDef.isControllableACL());
		
		// Update relationship-specific attributes if applicable
		if (BaseTypeId.CMIS_RELATIONSHIP.equals(existingType.getBaseId())) {
			existingType.setAllowedSourceTypes(newTypeDef.getAllowedSourceTypes());
			existingType.setAllowedTargetTypes(newTypeDef.getAllowedTargetTypes());
		}
		
		// Handle property definitions
		List<String> newPropertyDetailIds = new ArrayList<String>();
		List<String> existingPropertyDetailIds = existingType.getProperties();
		if (existingPropertyDetailIds == null) {
			existingPropertyDetailIds = new ArrayList<String>();
		}
		
		// Create new properties from coreMaps/detailMaps
		for (Entry<String, NemakiPropertyDefinitionCore> coreEntry : coreMaps.entrySet()) {
			String propertyId = coreEntry.getKey();
			NemakiPropertyDefinitionCore newCore = coreEntry.getValue();
			NemakiPropertyDefinitionDetail newDetail = detailMaps.get(propertyId);
			
			if (newDetail == null) {
				log.warn("No detail found for property: " + propertyId + ", skipping");
				continue;
			}
			
			// Check if property already exists
			NemakiPropertyDefinitionCore existingCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, propertyId);
			
			if (existingCore != null) {
					// Property exists - update both core and detail
					// Note: Core changes (propertyType, cardinality) are now allowed with failsafe mechanism
					// in CompileServiceImpl.addProperty() that handles type/cardinality mismatches gracefully
					log.debug("Updating existing property: " + propertyId);
				
					// Check for core property changes and log warnings
					boolean coreChanged = false;
					if (!existingCore.getPropertyType().equals(newCore.getPropertyType())) {
						log.warn("Property type change for " + propertyId + ": " + 
							existingCore.getPropertyType() + " -> " + newCore.getPropertyType() + 
							". Existing documents with incompatible values will be coerced or nullified on read.");
						existingCore.setPropertyType(newCore.getPropertyType());
						coreChanged = true;
					}
					if (!existingCore.getCardinality().equals(newCore.getCardinality())) {
						log.warn("Cardinality change for " + propertyId + ": " + 
							existingCore.getCardinality() + " -> " + newCore.getCardinality() + 
							". Existing documents with incompatible values will be normalized on read.");
						existingCore.setCardinality(newCore.getCardinality());
						coreChanged = true;
					}
				
					// Update queryName if changed
					if (newCore.getQueryName() != null && !newCore.getQueryName().equals(existingCore.getQueryName())) {
						existingCore.setQueryName(newCore.getQueryName());
						coreChanged = true;
					}
				
					// If core changed, we need to update it in the database
						// Note: Property cores may be globally shared - this change affects all types using this propertyId
						if (coreChanged) {
							log.info("Updating property core for " + propertyId + 
								". WARNING: This may affect other types that use the same propertyId.");
							// Persist the core changes to the database
							typeService.updatePropertyDefinitionCore(repositoryId, existingCore);
						}
				
					// Find and update the detail
					List<NemakiPropertyDefinitionDetail> existingDetails = 
						typeService.getPropertyDefinitionDetailByCoreNodeId(repositoryId, existingCore.getId());
				
					if (!existingDetails.isEmpty()) {
						NemakiPropertyDefinitionDetail existingDetail = existingDetails.get(0);
						existingDetail.setUpdatability(newDetail.getUpdatability());
						existingDetail.setRequired(newDetail.isRequired());
						existingDetail.setQueryable(newDetail.isQueryable());
						existingDetail.setOpenChoice(newDetail.isOpenChoice());
					
						// Update detail in database
						typeService.updatePropertyDefinitionDetail(repositoryId, existingDetail);
						newPropertyDetailIds.add(existingDetail.getId());
						log.debug("Updated property detail: " + existingDetail.getId());
					}
				} else {
				// Property doesn't exist - create it
				log.debug("Creating new property: " + propertyId);
				NemakiPropertyDefinition npd = new NemakiPropertyDefinition(newCore, newDetail);
				NemakiPropertyDefinitionDetail createdDetail = typeService.createPropertyDefinition(repositoryId, npd);
				if (createdDetail != null) {
					newPropertyDetailIds.add(createdDetail.getId());
					log.debug("Created new property detail: " + createdDetail.getId());
				}
			}
		}
		
		// Keep existing properties that are not in the new definition
		// (This preserves properties that weren't included in the update)
		for (String existingDetailId : existingPropertyDetailIds) {
			if (!newPropertyDetailIds.contains(existingDetailId)) {
				// Check if this property should be kept or removed
				// For now, we keep all existing properties not explicitly updated
				newPropertyDetailIds.add(existingDetailId);
				log.debug("Keeping existing property detail: " + existingDetailId);
			}
		}
		
		// Update the properties list
		existingType.setProperties(newPropertyDetailIds);
		
		// Save the updated type definition
		typeService.updateTypeDefinition(repositoryId, existingType);
		log.info("Type definition updated successfully: " + existingType.getTypeId());
	}

	/**
	 * Parse individual JSON type definition and convert to internal format
	 * Supports both creation of new types and update of existing types
	 */
	private void parseJsonTypeDefinition(String repositoryId, JSONObject typeJson) throws Exception {
		parseJsonTypeDefinition(repositoryId, typeJson, false);
	}
	
	/**
	 * Parse individual JSON type definition and convert to internal format
	 * @param repositoryId Repository ID
	 * @param typeJson JSON object containing type definition
	 * @param isUpdate If true, update existing type instead of skipping
	 */
	private void parseJsonTypeDefinition(String repositoryId, JSONObject typeJson, boolean isUpdate) throws Exception {
		
		// Extract basic type information
		String typeId = (String) typeJson.get("id");
		if (typeId == null) {
			typeId = (String) typeJson.get("localName"); // fallback
		}
		
		if (typeId == null || typeId.trim().isEmpty()) {
			log.warn("Type ID not found in JSON, skipping type definition");
			return;
		}
		
		log.debug("Processing type: " + typeId + ", isUpdate: " + isUpdate);
		
		// Check if type already exists
		boolean typeExists = existType(repositoryId, typeId);
		if (typeExists && !isUpdate) {
			log.warn("Type " + typeId + " already exists, skipping (use update mode to modify)");
			return;
		}
		
		// Create NemakiTypeDefinition
		NemakiTypeDefinition tdf = new NemakiTypeDefinition();
		
		// Basic properties
		tdf.setTypeId(typeId);
		tdf.setLocalName((String) typeJson.get("localName"));
		tdf.setLocalNameSpace((String) typeJson.get("localNamespace"));
		tdf.setDisplayName((String) typeJson.get("displayName"));
		tdf.setDescription((String) typeJson.get("description"));
		
		// Parent and base type
		String baseId = (String) typeJson.get("baseId");
		String parentId = (String) typeJson.get("parentId");
		
		if ("cmis:document".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_DOCUMENT);
		} else if ("cmis:folder".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_FOLDER);
		} else if ("cmis:relationship".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_RELATIONSHIP);
		} else if ("cmis:policy".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_POLICY);
		} else if ("cmis:item".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_ITEM);
		} else if ("cmis:secondary".equals(baseId)) {
			tdf.setBaseId(BaseTypeId.CMIS_SECONDARY);
		}
		
		if (parentId != null) {
			tdf.setParentId(parentId);
		}
		
		// Boolean properties
		if (typeJson.containsKey("creatable")) {
			tdf.setCreatable((Boolean) typeJson.get("creatable"));
		}
		// fileable method doesn't exist in NemakiTypeDefinition
		// if (typeJson.containsKey("fileable")) {
		//	tdf.setFileable((Boolean) typeJson.get("fileable"));
		// }
		if (typeJson.containsKey("queryable")) {
			tdf.setQueryable((Boolean) typeJson.get("queryable"));
		}
		if (typeJson.containsKey("fulltextIndexed")) {
			tdf.setFulltextIndexed((Boolean) typeJson.get("fulltextIndexed"));
		}
		if (typeJson.containsKey("includedInSupertypeQuery")) {
			tdf.setIncludedInSupertypeQuery((Boolean) typeJson.get("includedInSupertypeQuery"));
		}
		if (typeJson.containsKey("controllablePolicy")) {
			tdf.setControllablePolicy((Boolean) typeJson.get("controllablePolicy"));
		}
		if (typeJson.containsKey("controllableACL")) {
			tdf.setControllableACL((Boolean) typeJson.get("controllableACL"));
		}
		
		// Type mutability (currently not implemented in NemakiTypeDefinition)
		// JSONObject typeMutability = (JSONObject) typeJson.get("typeMutability");
		
		// Relationship-specific attributes
		if (BaseTypeId.CMIS_RELATIONSHIP.equals(tdf.getBaseId())) {
			// Parse allowedSourceTypes
			Object allowedSourceTypesObj = typeJson.get("allowedSourceTypes");
			if (allowedSourceTypesObj instanceof JSONArray) {
				JSONArray sourceTypesArray = (JSONArray) allowedSourceTypesObj;
				List<String> allowedSourceTypes = new ArrayList<String>();
				for (Object sourceType : sourceTypesArray) {
					if (sourceType instanceof String) {
						allowedSourceTypes.add((String) sourceType);
					}
				}
				if (!allowedSourceTypes.isEmpty()) {
					tdf.setAllowedSourceTypes(allowedSourceTypes);
					log.debug("Set allowedSourceTypes: " + allowedSourceTypes);
				}
			}
			
			// Parse allowedTargetTypes
			Object allowedTargetTypesObj = typeJson.get("allowedTargetTypes");
			if (allowedTargetTypesObj instanceof JSONArray) {
				JSONArray targetTypesArray = (JSONArray) allowedTargetTypesObj;
				List<String> allowedTargetTypes = new ArrayList<String>();
				for (Object targetType : targetTypesArray) {
					if (targetType instanceof String) {
						allowedTargetTypes.add((String) targetType);
					}
				}
				if (!allowedTargetTypes.isEmpty()) {
					tdf.setAllowedTargetTypes(allowedTargetTypes);
					log.debug("Set allowedTargetTypes: " + allowedTargetTypes);
				}
			}
		}
		
		// Process property definitions
		List<String> propertyIds = new ArrayList<String>();
		Object propertyDefinitionsObj = typeJson.get("propertyDefinitions");
		
		if (propertyDefinitionsObj != null) {
			if (propertyDefinitionsObj instanceof JSONArray) {
				// Handle array format from UI
				JSONArray propertyDefinitionsArray = (JSONArray) propertyDefinitionsObj;
				log.debug("Processing " + propertyDefinitionsArray.size() + " property definitions (array format) for type: " + typeId);
				propertyIds = parseJsonPropertyDefinitionsArray(repositoryId, typeId, propertyDefinitionsArray);
			} else if (propertyDefinitionsObj instanceof JSONObject) {
				// Handle object/map format
				JSONObject propertyDefinitions = (JSONObject) propertyDefinitionsObj;
				log.debug("Processing " + propertyDefinitions.size() + " property definitions (object format) for type: " + typeId);
				propertyIds = parseJsonPropertyDefinitions(repositoryId, typeId, propertyDefinitions);
			} else {
				log.warn("Unexpected propertyDefinitions type: " + propertyDefinitionsObj.getClass().getName());
			}
		}
		
		// Set properties list to NemakiTypeDefinition
		if (propertyIds != null && !propertyIds.isEmpty()) {
			tdf.setProperties(propertyIds);
			log.debug("Set " + propertyIds.size() + " properties to type: " + typeId);
		}
		
		// Add to type maps
		typeMaps.put(typeId, tdf);
		
		log.debug("Successfully parsed JSON type: " + typeId);
	}
	
	/**
	 * Parse JSON property definitions
	 */
	private List<String> parseJsonPropertyDefinitions(String repositoryId, String typeId, JSONObject propertyDefinitions) {
		List<String> propertyIds = new ArrayList<String>();
		
		for (Object keyObj : propertyDefinitions.keySet()) {
			String propertyId = (String) keyObj;
		JSONObject propertyJson = (JSONObject) propertyDefinitions.get(propertyId);
		
		if (log.isDebugEnabled()) {
			log.debug("Processing property: " + propertyId);
		}
			
			// Check if property already exists
			if (existProperty(repositoryId, propertyId)) {
				log.warn("Property " + propertyId + " already exists, skipping");
				continue;
			}
			
			propertyIds.add(propertyId);
			
			// Create core and detail property definitions
			NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
			NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
			
			// Core properties
			core.setPropertyId(propertyId);
			core.setQueryName(propertyId);
			
			// Property type conversion
			String propertyType = (String) propertyJson.get("propertyType");
			if ("string".equals(propertyType)) {
				core.setPropertyType(PropertyType.STRING);
			} else if ("integer".equals(propertyType)) {
				core.setPropertyType(PropertyType.INTEGER);
			} else if ("decimal".equals(propertyType)) {
				core.setPropertyType(PropertyType.DECIMAL);
			} else if ("datetime".equals(propertyType)) {
				core.setPropertyType(PropertyType.DATETIME);
			} else if ("boolean".equals(propertyType)) {
				core.setPropertyType(PropertyType.BOOLEAN);
			} else {
				// Default to string
				core.setPropertyType(PropertyType.STRING);
			}
			
			// Cardinality
			String cardinality = (String) propertyJson.get("cardinality");
			if ("multi".equals(cardinality)) {
				core.setCardinality(Cardinality.MULTI);
			} else {
				core.setCardinality(Cardinality.SINGLE);
			}
			
			coreMaps.put(propertyId, core);
			
			// Detail properties
			detail.setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());
			
			// Updatability
			String updatability = (String) propertyJson.get("updatability");
			if ("readonly".equals(updatability)) {
				detail.setUpdatability(Updatability.READONLY);
			} else if ("oncreate".equals(updatability)) {
				detail.setUpdatability(Updatability.ONCREATE);
			} else {
				detail.setUpdatability(Updatability.READWRITE);
			}
			
			// Required
			Boolean required = (Boolean) propertyJson.get("required");
			detail.setRequired(required != null ? required : false);
			
			// Queryable
			Boolean queryable = (Boolean) propertyJson.get("queryable");
			detail.setQueryable(queryable != null ? queryable : false);
			
			// Open choice
			Boolean openChoice = (Boolean) propertyJson.get("openChoice");
			detail.setOpenChoice(openChoice != null ? openChoice : false);
			
			detailMaps.put(propertyId, detail);
			
			if (log.isDebugEnabled()) {
				log.debug("Successfully parsed property: " + propertyId);
			}
		}
		
		typeProperties.put(typeId, propertyIds);
		if (log.isDebugEnabled()) {
			log.debug("Processed " + propertyIds.size() + " properties for type: " + typeId);
		}
		return propertyIds;
	}

	/**
	 * Parse JSON property definitions from array format (sent by UI)
	 */
	private List<String> parseJsonPropertyDefinitionsArray(String repositoryId, String typeId, JSONArray propertyDefinitionsArray) {
		List<String> propertyIds = new ArrayList<String>();
		
		for (Object propObj : propertyDefinitionsArray) {
			if (!(propObj instanceof JSONObject)) {
				log.warn("Skipping non-JSONObject property in array");
				continue;
			}
			
			JSONObject propertyJson = (JSONObject) propObj;
			String propertyId = (String) propertyJson.get("id");
			
			if (propertyId == null || propertyId.trim().isEmpty()) {
				log.warn("Property ID not found in array element, skipping");
				continue;
			}
			
			if (log.isDebugEnabled()) {
				log.debug("Processing property from array: " + propertyId);
			}
			
			// Check if property already exists
			if (existProperty(repositoryId, propertyId)) {
				log.warn("Property " + propertyId + " already exists, skipping");
				continue;
			}
			
			propertyIds.add(propertyId);
			
			// Create core and detail property definitions
			NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
			NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
			
			// Core properties
			core.setPropertyId(propertyId);
			core.setQueryName(propertyId);
			
			// Property type conversion
			String propertyType = (String) propertyJson.get("propertyType");
			if ("string".equals(propertyType)) {
				core.setPropertyType(PropertyType.STRING);
			} else if ("integer".equals(propertyType)) {
				core.setPropertyType(PropertyType.INTEGER);
			} else if ("decimal".equals(propertyType)) {
				core.setPropertyType(PropertyType.DECIMAL);
			} else if ("datetime".equals(propertyType)) {
				core.setPropertyType(PropertyType.DATETIME);
			} else if ("boolean".equals(propertyType)) {
				core.setPropertyType(PropertyType.BOOLEAN);
			} else {
				// Default to string
				core.setPropertyType(PropertyType.STRING);
			}
			
			// Cardinality
			String cardinality = (String) propertyJson.get("cardinality");
			if ("multi".equals(cardinality)) {
				core.setCardinality(Cardinality.MULTI);
			} else {
				core.setCardinality(Cardinality.SINGLE);
			}
			
			coreMaps.put(propertyId, core);
			
			// Detail properties
			detail.setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());
			
			// Updatability
			String updatability = (String) propertyJson.get("updatability");
			if ("readonly".equals(updatability)) {
				detail.setUpdatability(Updatability.READONLY);
			} else if ("oncreate".equals(updatability)) {
				detail.setUpdatability(Updatability.ONCREATE);
			} else {
				detail.setUpdatability(Updatability.READWRITE);
			}
			
			// Required
			Boolean required = (Boolean) propertyJson.get("required");
			detail.setRequired(required != null ? required : false);
			
			// Queryable
			Boolean queryable = (Boolean) propertyJson.get("queryable");
			detail.setQueryable(queryable != null ? queryable : false);
			
			// Open choice
			Boolean openChoice = (Boolean) propertyJson.get("openChoice");
			detail.setOpenChoice(openChoice != null ? openChoice : false);
			
			detailMaps.put(propertyId, detail);
			
			if (log.isDebugEnabled()) {
				log.debug("Successfully parsed property from array: " + propertyId);
			}
		}
		
		typeProperties.put(typeId, propertyIds);
		if (log.isDebugEnabled()) {
			log.debug("Processed " + propertyIds.size() + " properties from array for type: " + typeId);
		}
		return propertyIds;
	}

	private void parse(String repositoryId, InputStream is) throws DocumentException {
		if (log.isDebugEnabled()) {
			log.debug("Parse method called for repository: " + repositoryId);
		}
		
		SAXReader saxReader = new SAXReader();
		Document document = saxReader.read(is);
		Element model = document.getRootElement();
		
		if (log.isDebugEnabled()) {
			log.debug("Root element name: " + model.getName());
		}

		// Types
		Element _types = getElement(model, "types");
		if (log.isDebugEnabled()) {
			log.debug("Found _types element: " + (_types != null ? _types.getName() : "null"));
		}
		
		List<Element> types = getElements(_types, "type");
		if (log.isDebugEnabled()) {
			log.debug("Found types count: " + (types != null ? types.size() : "null"));
		}
		
		if (types != null && !types.isEmpty()) {
			for (int i = 0; i < types.size(); i++) {
				Element type = types.get(i);
				if (log.isDebugEnabled()) {
					log.debug("Type[" + i + "] name: " + type.getName());
				}
			}
		}
		
		parseTypes(repositoryId, types);

		// Aspects
		Element _aspects = getElement(model, "aspects");
		if (log.isDebugEnabled()) {
			log.debug("Found _aspects element: " + (_aspects != null ? _aspects.getName() : "null"));
		}
		
		List<Element> aspects = getElements(_aspects, "aspect");
		if (log.isDebugEnabled()) {
			log.debug("Found aspects count: " + (aspects != null ? aspects.size() : "null"));
		}
		
		parseTypes(repositoryId, aspects);
		
		if (log.isDebugEnabled()) {
			log.debug("Parse method completed");
		}
	}

	private void parseTypes(String repositoryId, List<Element> types) {
		for (Element type : types) {
			// Extract values
			// NOTE: "enabled" attribute parsing is not implemented; all types are assumed enabled

			// ////
			// type
			// ////
			NemakiTypeDefinition tdf = new NemakiTypeDefinition();

			// typeId
			String typeId = getAttributeValue(type, "name");
			if (StringUtils.isEmpty(typeId)) {
				log.warn("typeId should be specified. SKIP.");
				continue;
			} else if (existType(repositoryId, typeId)) {
				log.warn(MessageFormat.format("typeId:{0} already exists in DB! SKIP.",typeId));
				continue;
			}
			tdf.setTypeId(typeId);
			tdf.setLocalName(typeId);

			// title
			String title = getElementValue(type, "title");
			if (StringUtils.isEmpty(title)) {
				log.warn(MessageFormat.format("typeId:{0} 'title' is nos specified. Default to typeId.",typeId));
			}
			tdf.setLocalNameSpace("");
			tdf.setDisplayName(title);
			tdf.setDescription(title);

			// parent and baseType
			String parent = getElementValue(type, "parent");
			if ("type".equals(type.getName())) {
				if (StringUtils.isEmpty(parent)) {
					log.warn(MessageFormat.format("typeId:{0} 'parent' should be specified. SKIP.",typeId));
					continue;
				}

				if ("cm:content".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_DOCUMENT);
					tdf.setParentId(BaseTypeId.CMIS_DOCUMENT.value());
				} else if ("cm:folder".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_FOLDER);
					tdf.setParentId(BaseTypeId.CMIS_FOLDER.value());
				} else if ("cm:relationship".equals(parent)) {
					tdf.setBaseId(BaseTypeId.CMIS_RELATIONSHIP);
					tdf.setParentId(BaseTypeId.CMIS_RELATIONSHIP.value());
				}
			} else if ("aspect".equals(type.getName())) {
				tdf.setBaseId(BaseTypeId.CMIS_SECONDARY);
				if (StringUtils.isBlank(parent)) {
					tdf.setParentId(BaseTypeId.CMIS_SECONDARY.value());
				} else {
					tdf.setParentId(parent);
				}
			}

			// properties
			Element _properties = getElement(type, "properties");
			List<Element> properties = getElements(_properties, "property");
			if (CollectionUtils.isNotEmpty(properties)) {
				parseProperties(repositoryId, typeId, properties);
			}

			// Put to map
			typeMaps.put(typeId, tdf);
		}

	}

	private void parseProperties(String repositoryId, String typeId, List<Element> properties) {
		List<String> propertyIds = new ArrayList<String>();

		for (Element property : properties) {
			NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
			NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();

			// propertyId
			String propName = getAttributeValue(property, "name");
			// Check existing property definitions
			if (existProperty(repositoryId, propName)) {
				log.warn(MessageFormat.format("propertyId:{0} already exists in DB! SKIP.",propName));
				continue;
			}
			propertyIds.add(propName);

			// ////
			// core
			// ////
			// propertyId
			core.setPropertyId(propName);

			// queryName
			core.setQueryName(propName);

			// data type
			String dataType = getElementValue(property, "type");
			if ("d:text".equals(dataType) || "d:mltext".equals(dataType) || "d:content".equals(dataType)) {
				core.setPropertyType(PropertyType.STRING);
			} else if ("d:int".equals(dataType) || "d:long".equals(dataType)) {
				core.setPropertyType(PropertyType.INTEGER);
			} else if ("d:float".equals(dataType) || "d:double".equals(dataType)) {
				// FIXME is this mapping OK?
				core.setPropertyType(PropertyType.DECIMAL);
			} else if ("d:date".equals(dataType) || "d:datetime".equals(dataType)) {
				// NOTE: datePrecision not implemented; defaults to full datetime precision
				core.setPropertyType(PropertyType.DATETIME);
			} else if ("d:boolean".equals(dataType)) {
				core.setPropertyType(PropertyType.BOOLEAN);
			} else if ("d:any".equals(dataType)) {
				log.info(buildMsg(typeId, propName, "'d:any data' types is not allowed. Defaults to STRING."));
				core.setPropertyType(PropertyType.STRING);
			} else {
				log.info(buildMsg(typeId, propName, "'Unknown data type. Defaults to STRING."));
				core.setPropertyType(PropertyType.STRING);
			}

			// cardinality
			String multiple = getElementValue(property, "multiple");
			if ("true".equals(multiple)) {
				core.setCardinality(Cardinality.MULTI);
			} else {
				if (StringUtils.isBlank(multiple)) {
					log.info(buildMsg(typeId, propName, "'multiple' is not specified. Default to false"));
				}
				core.setCardinality(Cardinality.SINGLE);
			}

			coreMaps.put(propName, core);

			// //////
			// detail
			// //////
			detail.setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());

			// defaultValue
			String defaultValue = getElementValue(property, "default");
			// NOTE: CMIS spec allows multiple default values for multi-valued properties; currently only single default supported
			if (!StringUtils.isBlank(defaultValue)) {
				List<Object> defaults = new ArrayList<Object>();
				defaults.add(defaultValue);
				detail.setDefaultValue(defaults);
			} // if defaultValue not set, it should be null for WSConverter

			// constraints
			Element _constraints = getElement(property, "constraints");
			setConstraints(detail, _constraints);

			// updatability
			detail.setUpdatability(Updatability.READWRITE);

			// required
			if (existElement(property, "mandatory")) {
				detail.setRequired(true);
			} else {
				log.info(buildMsg(typeId, propName, "'mandatory' is not specified. Default to false"));
				detail.setRequired(false);
			}

			// queryable
			Element index = getElement(property, "index");
			String _indexEnabled = getAttributeValue(index, "enabled");

			boolean indexEnabled = ("true".equals(_indexEnabled)) ? true : false;

			if (indexEnabled) {
				detail.setQueryable(true);
			} else {
				log.info(buildMsg(typeId, propName, "'index' is not specified. Default to false"));

				detail.setQueryable(false);
			}

			// FIXME openChoice is default to false?
			detail.setOpenChoice(false);

			detailMaps.put(propName, detail);
		}

		typeProperties.put(typeId, propertyIds);
	}

	private void setConstraints(NemakiPropertyDefinitionDetail detail, Element _constraints) {
		List<Element> constraints = getElements(_constraints, "constraint");
		for (Element constraint : constraints) {
			String type = getAttributeValue(constraint, "type");
			if (type != null) {
				if ("LENGTH".equals(type)) {
					String minLength = getElementValue(constraint, "minLength");
					String maxLength = getElementValue(constraint, "maxLength");
					if (StringUtils.isNotBlank(maxLength)) {
						detail.setMaxLength(Long.valueOf(maxLength));
					}
				} else if ("MINMAX".equals(type)) {
					String minValue = getElementValue(constraint, "minValue");
					if (StringUtils.isNotBlank(minValue)) {
						detail.setMaxValue(Long.valueOf(minValue));
					}
					String maxValue = getElementValue(constraint, "maxValue");
					if (StringUtils.isNotBlank(maxValue)) {
						detail.setMaxValue(Long.valueOf(maxValue));
					}
				} else if ("LIST".equals(type)) {
					Element _allowed = getElement(constraint, "parameter");
					if (_allowed != null) {
						Element list = getElement(_allowed, "list");
						List<String> values = getElementsValues(_allowed, "value");

						Choice choice = new Choice();
						List<Object> _values = new ArrayList<Object>();
						for (String s : values) {
							_values.add(s);
						}
						choice.setValue(_values);
						List<Choice> choices = new ArrayList<Choice>();
						choices.add(choice);
						detail.setChoices(choices);
					}
				}
			}
		}
	}

	private void create(String repositoryId) {
		if (log.isDebugEnabled()) {
			log.debug("Create method called for repository: " + repositoryId);
			log.debug("coreMaps size: " + (coreMaps != null ? coreMaps.size() : "null"));
			log.debug("detailMaps size: " + (detailMaps != null ? detailMaps.size() : "null"));
			log.debug("typeMaps size: " + (typeMaps != null ? typeMaps.size() : "null"));
		}
		
		// First, create properties
		// SpotBugs: NP_NULL_ON_SOME_PATH - Add null/empty check before iteration
		if (coreMaps == null || coreMaps.isEmpty()) {
			if (log.isDebugEnabled()) {
				log.debug("No coreMaps found - skipping property creation");
			}
		} else {
			for (Entry<String, NemakiPropertyDefinitionCore> coreEntry : coreMaps.entrySet()) {
			String originalPropertyId = coreEntry.getKey();
			NemakiPropertyDefinition p = new NemakiPropertyDefinition(coreEntry.getValue(),
					detailMaps.get(coreEntry.getKey()));
			
			if (log.isDebugEnabled()) {
				log.debug("Creating property: " + originalPropertyId);
			}
			
			// プロパティ定義を作成
			NemakiPropertyDefinitionDetail createdDetail = typeService.createPropertyDefinition(repositoryId, p);
			
			if (createdDetail == null) {
				log.error("createPropertyDefinition returned null for: " + originalPropertyId);
				continue;
			}
			
			if (log.isDebugEnabled()) {
				log.debug("Created detail with ID: " + createdDetail.getId() + 
					", coreNodeId: " + createdDetail.getCoreNodeId());
			}
			
			// 元のコードのロジックに戻す - propertyIdで再度Coreを検索
			// これは、createPropertyDefinition内でpropertyIdが変更される可能性があるため
			NemakiPropertyDefinitionCore createdCore = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
					p.getPropertyId());
					
			if (createdCore != null) {
				coreEntry.getValue().setId(createdCore.getId());
				if (log.isDebugEnabled()) {
					log.debug("Found core with ID: " + createdCore.getId() + 
						" for property: " + p.getPropertyId());
				}
			} else {
				// createdCoreがnullの場合、DetailのcoreNodeIdを使用
				log.warn("Could not find core by propertyId, using detail's coreNodeId");
				coreEntry.getValue().setId(createdDetail.getCoreNodeId());
			}
			}
		}

		// Prepare types
		if (log.isDebugEnabled()) {
			log.debug("Preparing types");
		}
		if (typeMaps == null || typeMaps.isEmpty()) {
			log.warn("CRITICAL: No typeMaps found - no types to prepare!");
			return;
		}
		
		if (log.isDebugEnabled()) {
			log.debug("Found " + typeMaps.size() + " types to prepare");
		}
		for (Entry<String, NemakiTypeDefinition> typeEntry : typeMaps.entrySet()) {
			NemakiTypeDefinition t = typeEntry.getValue();

			if (log.isDebugEnabled()) {
				log.debug("Processing type: " + t.getTypeId());
			}
			
			// Set property detail ids using the coreNodeId from creation
			List<String> propertyNodeIds = new ArrayList<String>();
			List<String> propertyIds = typeProperties.get(t.getTypeId());
			
			if (log.isDebugEnabled()) {
				log.debug("Property IDs for type " + t.getTypeId() + ": " + propertyIds);
			}
			
			if (CollectionUtils.isNotEmpty(propertyIds)) {
			for (String propertyId : typeProperties.get(t.getTypeId())) {
				if (log.isDebugEnabled()) {
					log.debug("Processing property: " + propertyId);
				}
					
					// 元のpropertyIdでcoreMapsから取得（作成時にIDがセットされている）
					NemakiPropertyDefinitionCore coreFromMap = coreMaps.get(propertyId);
					
					if (log.isDebugEnabled()) {
						log.debug("Core from map for " + propertyId + ": " + 
							(coreFromMap != null ? coreFromMap.getId() : "null"));
					}
					
					if (coreFromMap != null && coreFromMap.getId() != null) {
						String coreNodeId = coreFromMap.getId();
						if (log.isDebugEnabled()) {
							log.debug("Querying details for coreNodeId: " + coreNodeId);
						}
						
						List<NemakiPropertyDefinitionDetail> details = typeService
								.getPropertyDefinitionDetailByCoreNodeId(repositoryId, coreNodeId);
								
						if (log.isDebugEnabled()) {
							log.debug("Found " + details.size() + " details for coreNodeId: " + coreNodeId);
						}
						
						if (CollectionUtils.isEmpty(details)) {
							log.warn(buildMsg(t.getTypeId(), propertyId,
									"Skipped to add this property because of incorrect data in DB."));
						} else {
							// Presuppose there is no multiple detail for each core
							NemakiPropertyDefinitionDetail detail = details.get(0);
							String detailId = detail.getId();
							
							if (log.isDebugEnabled()) {
								log.debug("Detail ID: " + detailId + " for property: " + propertyId);
							}
							
							if (detailId != null) {
								propertyNodeIds.add(detailId);
								if (log.isDebugEnabled()) {
									log.debug("Added property detail ID: " + detailId + " for property: " + propertyId);
								}
							} else {
								log.warn("Detail ID is null for property: " + propertyId);
							}
						}
					} else {
						log.warn(buildMsg(t.getTypeId(), propertyId,
								"Property core not found in local map"));
					}
				}
				
				if (log.isDebugEnabled()) {
					log.debug("Final propertyNodeIds for type " + t.getTypeId() + ": " + propertyNodeIds);
				}
				t.setProperties(propertyNodeIds);
			} else {
				if (log.isDebugEnabled()) {
					log.debug("No properties found for type: " + t.getTypeId());
				}
			}

			// Remove orphan types
			if (log.isDebugEnabled()) {
				log.debug("Checking parent type for " + t.getTypeId() +
					", parentId: " + t.getParentId() + ", isBaseType: " + isBaseType(t.getParentId()) +
					", parentInTypeMaps: " + (typeMaps.get(t.getParentId()) != null) +
					", existsInDB: " + existType(repositoryId, t.getParentId()));
			}
			// Allow creation if parent is:
			// 1. In the current batch (typeMaps), OR
			// 2. A CMIS base type (isBaseType), OR
			// 3. An existing type in the database (existType)
			if (typeMaps.get(t.getParentId()) == null && !isBaseType(t.getParentId()) && !existType(repositoryId, t.getParentId())) {
				log.warn(buildMsg(t.getId(), null,
						"Skipped to create this type because it has an unknown parent type: " + t.getParentId()));
				if (log.isDebugEnabled()) {
					log.debug("SKIPPED type creation for " + t.getTypeId() + " due to unknown parent: " + t.getParentId());
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("About to create type: " + t.getTypeId() + " with properties: " + t.getProperties());
				}
				typeService.createTypeDefinition(repositoryId, t);
			}
		}
	}

	private Element getElement(Element parent, String name) {
		Element result = null;

		if (existElement(parent, name)) {
			for (Iterator<Element> iterator = parent.elementIterator(name); iterator.hasNext();) {
				result = iterator.next();
			}
			return result;
		} else {
			log.info("Cannot parse " + "'" + name + "'.");
			return result;
		}
	}

	private List<Element> getElements(Element parent, String name) {
		List<Element> results = new ArrayList<Element>();

		if (existElement(parent, name)) {
			for (Iterator<Element> iterator = parent.elementIterator(name); iterator.hasNext();) {
				results.add(iterator.next());
			}
			return results;
		} else {
			log.info(MessageFormat.format("Cannot parse '{0}'.",name));
			return results;
		}
	}

	private String getElementValue(Element parent, String name) {
		Element elm = getElement(parent, name);
		if (elm != null) {
			return elm.getStringValue();
		} else {
			log.info(MessageFormat.format("Cannot parse '{0}'.",name));
			return null;
		}
	}

	private List<String> getElementsValues(Element parent, String name) {
		List<Element> elements = getElements(parent, name);

		List<String> result = new ArrayList<String>();
		for (Element element : elements) {
			result.add(element.getStringValue());
		}
		return result;
	}

	private String getAttributeValue(Element element, String name) {
		if (existAttribute(element, name)) {
			Attribute attr = element.attribute(name);
			return attr.getStringValue();
		} else {
			return null;
		}
	}

	private boolean isBaseType(String typeId) {
		if (BaseTypeId.CMIS_DOCUMENT.value().equals(typeId) || BaseTypeId.CMIS_FOLDER.value().equals(typeId)
				|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(typeId) || BaseTypeId.CMIS_POLICY.value().equals(typeId)
				|| BaseTypeId.CMIS_ITEM.value().equals(typeId) || BaseTypeId.CMIS_SECONDARY.value().equals(typeId)) {
			return true;
		} else {
			return false;
		}
	}

	private boolean existType(String repositoryId, String typeId) {
		NemakiTypeDefinition existing = typeService.getTypeDefinition(repositoryId, typeId);
		if (existing == null) {
			return false;
		} else {
			return true;
		}
	}

	private boolean existProperty(String repositoryId, String propertyId) {
		NemakiPropertyDefinitionCore existing = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId,
				propertyId);
		if (existing == null) {
			return false;
		} else {
			return true;
		}
	}

	private boolean existElement(Element parent, String name) {
		try {
			parent.element(name);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private boolean existAttribute(Element element, String name) {
		try {
			element.attribute(name);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private String buildMsg(String typeId, String propertyId, String msg) {
		List<String> header = new ArrayList<String>();

		String _typeId = "";
		if (StringUtils.isNotBlank(typeId)) {
			_typeId = "typeId=" + typeId;
			header.add(_typeId);
		}

		String _proeprtyId = "";
		if (StringUtils.isNotBlank(propertyId)) {
			_proeprtyId = "propertyId=" + propertyId;
			header.add(_proeprtyId);
		}

		String _header = StringUtils.join(header, ",");
		_header = "[" + _header + "]";

		return _header + msg;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}
}
