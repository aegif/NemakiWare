/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import java.text.SimpleDateFormat;
import jp.aegif.nemaki.util.DateUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.support.query.CmisQlExtParser_CmisBaseGrammar.null_predicate_return;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mindrot.jbcrypt.BCrypt;
import jp.aegif.nemaki.util.spring.SpringContext;

import com.fasterxml.jackson.core.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

@Path("/repo/{repositoryId}/user/")
public class UserItemResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(UserItemResource.class);

	private ContentService contentService;

	private PropertyManager propertyManager;

	private ThreadLockService threadLockService;

	public UserItemResource() {
		super();
		// TODO Auto-generated constructor stub
	}

	private ContentService getContentService() {
		if (contentService != null) {
			return contentService;
		}
		// Fallback to manual Spring context lookup
		try {
			ContentService service = SpringContext.getApplicationContext()
					.getBean("ContentService", ContentService.class);
			if (service != null) {
				log.debug("ContentService retrieved from SpringContext successfully");
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get ContentService from SpringContext: " + e.getMessage(), e);
		}
		
		// Final fallback - try different bean name patterns
		try {
			ContentService service = SpringContext.getApplicationContext()
					.getBean("contentService", ContentService.class);
			if (service != null) {
				log.debug("ContentService retrieved from SpringContext with lowercase name");
				return service;
			}
		} catch (Exception e) {
			log.debug("Could not find contentService with lowercase name: " + e.getMessage());
		}
		
		log.error("ContentService is null and SpringContext fallback failed - dependency injection issue");
		return null;
	}
	
private ContentService getContentServiceSafe() {
		ContentService service = getContentService();
		if (service == null) {
			throw new RuntimeException("ContentService not available - dependency injection failed");
		}
		return service;
	}
	
	private PropertyManager getPropertyManager() {
		if (propertyManager != null) {
			return propertyManager;
		}
		// Fallback to manual Spring context lookup
		try {
			PropertyManager service = SpringContext.getApplicationContext()
					.getBean("propertyManager", PropertyManager.class);
			if (service != null) {
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get PropertyManager from SpringContext: " + e.getMessage(), e);
		}

		log.error("PropertyManager is null and SpringContext fallback failed");
		return null;
	}

	private ThreadLockService getThreadLockService() {
		if (threadLockService != null) {
			return threadLockService;
		}
		// Fallback to manual Spring context lookup
		try {
			ThreadLockService service = SpringContext.getApplicationContext()
					.getBean("threadLockService", ThreadLockService.class);
			if (service != null) {
				log.debug("ThreadLockService retrieved from SpringContext successfully");
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get ThreadLockService from SpringContext: " + e.getMessage(), e);
		}

		log.error("ThreadLockService is null and SpringContext fallback failed");
		return null;
	}

	@SuppressWarnings("unchecked")
	
	@GET
	@Path("/debug-systemfolder")
	@Produces(MediaType.APPLICATION_JSON)
	public Response debugSystemFolder(@PathParam("repositoryId") String repositoryId) {
		JSONObject result = new JSONObject();
		
		try {
			// Test PropertyManager
			PropertyManager pm = getPropertyManager();
			if (pm == null) {
				result.put("propertyManager", "NULL");
			} else {
				result.put("propertyManager", "OK");
				String systemFolderValue = pm.readValue(repositoryId, PropertyKey.SYSTEM_FOLDER);
				result.put("systemFolderValue", systemFolderValue != null ? systemFolderValue : "NULL");
			}
			
			// Test ContentService
			ContentService cs = getContentService();
			if (cs == null) {
				result.put("contentService", "NULL");
			} else {
				result.put("contentService", "OK");
				try {
					Folder systemFolder = cs.getSystemFolder(repositoryId);
					result.put("systemFolder", systemFolder != null ? systemFolder.getId() : "NULL");
				} catch (Exception e) {
					result.put("systemFolderException", e.getMessage());
				}
			}
			
			return Response.ok(result.toJSONString()).build();
		} catch (Exception e) {
			result.put("error", e.getMessage());
			return Response.status(500).entity(result.toJSONString()).build();
		}
	}

	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public Response list(@PathParam("repositoryId") String repositoryId) {
		// CRITICAL DIAGNOSTIC: Test if ERROR level logs appear
		log.error("!!! ERROR LEVEL TEST - list() ENTRY POINT for repository: " + repositoryId + " !!!");
		log.info("UserItemResource.list() called for repository: " + repositoryId);
		System.err.println("### SYSTEM.ERR TEST - list() called for repository: " + repositoryId + " ###");
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Get ContentService from Spring context
		ContentService contentService = getContentService();
		
		if (contentService == null) {
			log.error("ContentService not found in Spring context");
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "ContentService not available");
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString()).build();
		}

		// Get all users list
		List<UserItem> userList;
		try {
			log.debug("About to call contentService.getUserItems()");
			userList = contentService.getUserItems(repositoryId);
			log.debug("contentService.getUserItems() returned " + userList.size() + " users");
			for (UserItem user : userList) {
				log.debug("Processing user: " + user.getUserId());
				JSONObject userJSON = convertUserToJson(user, repositoryId);
				listJSON.add(userJSON);
			}
			result.put("users", listJSON);
			result.put("status", "success");
			log.info("Returning result with " + listJSON.size() + " users");
			return Response.ok(result.toJSONString()).build();
		} catch (Exception e) {
			log.error("Exception occurred: " + e.getClass().getName() + ": " + e.getMessage(), e);
			
			// エラー情報をJSONで返す
			JSONObject errorResult = new JSONObject();
			errorResult.put("status", "error");
			errorResult.put("message", "Failed to retrieve user list");
			errorResult.put("error", e.getMessage());
			errorResult.put("errorType", e.getClass().getName());
			
			// HTTP 500 Internal Server Error を返す
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
					.entity(errorResult.toJSONString())
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Validation
		if (StringUtils.isBlank(userId)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ErrorCode.ERR_MANDATORY);
		}

		UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);

		if (user == null) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		} else {
			result.put("user", convertUserToJson(user, repositoryId));
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Search user by id TODO Use Solr
	 *
	 * @param query
	 * @return
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/search")
	@Produces(MediaType.APPLICATION_JSON)
	public String search(@PathParam("repositoryId") String repositoryId, @QueryParam("query") String query) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		List<UserItem> users;
		JSONArray queriedUsers = new JSONArray();
		users = getContentService().getUserItems(repositoryId);
		for (UserItem user : users) {
			if (user.getUserId().contains(query) || user.getName().contains(query)) {
				JSONObject userJSON = convertUserToJson(user, repositoryId);
				if(queriedUsers.size() < 50){
					queriedUsers.add(userJSON);
				}else{
					break;
				}
			}
		}

		if (queriedUsers.isEmpty()) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		} else {
			result.put("result", queriedUsers);
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@POST
	@Path("/create/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String create(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			@FormParam(FORM_USERNAME) String name, @FormParam(FORM_PASSWORD) String password,
			@FormParam(FORM_FIRSTNAME) String firstName, @FormParam(FORM_LASTNAME) String lastName,
			@FormParam(FORM_EMAIL) String email, @FormParam("groups") String groupsJson,
			@Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Validation
		status = validateNewUser(status, errMsg, userId, name, firstName, lastName, password, repositoryId);

		// Create a user
		if (status) {
			ContentService service = getContentService();
			if (service == null) {
				status = false;
				addErrMsg(errMsg, "system", "ContentService not available");
			} else {
				// Generate a password hash
				String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

				// parent
				System.err.println("### BEFORE getOrCreateSystemSubFolder for userId: " + userId);
				final Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");
				System.err.println("### AFTER getOrCreateSystemSubFolder for userId: " + userId + ", usersFolder=" + (usersFolder != null ? usersFolder.getId() : "NULL"));

				UserItem user = new UserItem(null, NemakiObjectType.nemakiUser, userId, name, passwordHash, false, usersFolder.getId());

				Map<String, Object> map = new HashMap<>();
				if (firstName != null) map.put("nemaki:firstName", firstName);
				if (lastName != null) map.put("nemaki:lastName", lastName);
				if (email != null) map.put("nemaki:email", email);
				List<Property> properties = new ArrayList<>();
				for(String key : map.keySet()) properties.add(new Property(key, map.get(key)));
				user.setSubTypeProperties(properties);

				setFirstSignature(httpRequest, user);

				service.createUserItem(new SystemCallContext(repositoryId), repositoryId, user);

				// Process groups assignment
				log.info("Groups parameter received: '" + groupsJson + "' (isBlank=" + StringUtils.isBlank(groupsJson) + ")");
				if (StringUtils.isNotBlank(groupsJson)) {
					try {
						log.info("Parsing groups JSON: " + groupsJson);
						JSONParser parser = new JSONParser();
						JSONArray groups = (JSONArray) parser.parse(groupsJson);
						log.info("Parsed groups array with " + groups.size() + " elements");
						updateUserGroups(repositoryId, userId, groups, service);
						log.info("Finished updateUserGroups for user " + userId);
					} catch (Exception e) {
						log.error("Failed to parse or apply groups for user " + userId + ": " + e.getMessage(), e);
					}
				} else {
					log.info("Groups parameter is blank, skipping group assignment");
				}
			}

		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Create user with JSON input (for UI compatibility)
	 */
	@POST
	@Path("/create-json/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String createJson(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			String jsonInput, @Context HttpServletRequest httpRequest) {

		log.info("=== createJson() CALLED for userId: " + userId + ", repositoryId: " + repositoryId + " ===");
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			// Parse JSON input
			JSONParser parser = new JSONParser();
			JSONObject userJson = (JSONObject) parser.parse(jsonInput);

			String name = (String) userJson.get("name");
			String password = (String) userJson.get("password");
			String firstName = (String) userJson.get("firstName");
			String lastName = (String) userJson.get("lastName");
			String email = (String) userJson.get("email");

			// Validation
			log.info("[" + userId + "] Starting validation");
			status = validateNewUser(status, errMsg, userId, name, firstName, lastName, password, repositoryId);
			log.info("[" + userId + "] Validation complete: status=" + status);

			// Create a user
			if (status) {
				ContentService service = getContentService();
				if (service == null) {
					status = false;
					addErrMsg(errMsg, "system", "ContentService not available");
				} else {
					// Generate a password hash
					log.info("[" + userId + "] Hashing password");
					String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

					// parent
					log.info("[" + userId + "] Getting users folder");
					final Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");
					log.info("[" + userId + "] Users folder: " + (usersFolder != null ? usersFolder.getId() : "NULL"));

					UserItem user = new UserItem(null, NemakiObjectType.nemakiUser, userId, name, passwordHash, false, usersFolder.getId());

					Map<String, Object> map = new HashMap<>();
					if (firstName != null) map.put("nemaki:firstName", firstName);
					if (lastName != null) map.put("nemaki:lastName", lastName);
					if (email != null) map.put("nemaki:email", email);
					List<Property> properties = new ArrayList<>();
					for(String key : map.keySet()) properties.add(new Property(key, map.get(key)));
					user.setSubTypeProperties(properties);

					setFirstSignature(httpRequest, user);

					log.info("[" + userId + "] Creating user item in repository");
					service.createUserItem(new SystemCallContext(repositoryId), repositoryId, user);
					log.info("[" + userId + "] User creation completed successfully");

					// CRITICAL FIX (2025-10-13): Process groups assignment (was missing in createJson)
					// Extract groups array from JSON input
					JSONArray groups = (JSONArray) userJson.get("groups");
					log.error("!!! [" + userId + "] Groups extracted: " + (groups != null ? groups.toString() : "NULL"));
					if (groups != null && !groups.isEmpty()) {
						log.error("!!! [" + userId + "] Starting group assignment with " + groups.size() + " groups");
						updateUserGroups(repositoryId, userId, groups, service);
						log.error("!!! [" + userId + "] Group assignment completed");
					} else {
						log.error("!!! [" + userId + "] No groups specified");
					}
				}
			}
		} catch (ParseException e) {
			log.error("JSON parsing error: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "json", "Invalid JSON format");
		} catch (Exception e) {
			log.error("User creation error: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "system", "Failed to create user");
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Update user with JSON input (for UI compatibility)
	 */
	@PUT
	@Path("/update-json/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	@SuppressWarnings("unchecked")
	public String updateJson(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			String jsonInput, @Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		try {
			// Parse JSON input
			JSONParser parser = new JSONParser();
			JSONObject userJson = (JSONObject) parser.parse(jsonInput);
			
			String name = (String) userJson.get("name");
			String firstName = (String) userJson.get("firstName");
			String lastName = (String) userJson.get("lastName");
			String email = (String) userJson.get("email");
			String password = (String) userJson.get("password");

			// Existing user
			UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);
			if(user == null){
				status = false;
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
			}

			// Validation
			status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

			// Edit & Update
			if (status) {
				ContentService service = getContentService();
				if (service == null) {
					status = false;
					addErrMsg(errMsg, "system", "ContentService not available");
				} else {
					// Edit user properties
					if (name != null)
						user.setName(name);

					Map<String, Object> map = new HashMap<>();
					for(Property prop : user.getSubTypeProperties()) map.put(prop.getKey(), prop.getValue());
					
					if (firstName != null)
						map.put("nemaki:firstName", firstName);
					if (lastName != null)
						map.put("nemaki:lastName", lastName);
					if (email != null)
						map.put("nemaki:email", email);
						
					List<Property> properties = new ArrayList<>();
					for(String key : map.keySet()) properties.add(new Property(key, map.get(key)));
					user.setSubTypeProperties(properties);

					// Update password if provided
					if (StringUtils.isNotBlank(password)) {
						String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
						user.setPassowrd(passwordHash);
					}

					setModifiedSignature(httpRequest, user);

					try {
						service.update(new SystemCallContext(repositoryId), repositoryId, user);
					} catch (Exception e) {
						log.error("User update error: " + e.getMessage(), e);
						status = false;
						addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
					}
				}
			}
		} catch (ParseException e) {
			log.error("JSON parsing error: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "json", "Invalid JSON format");
		} catch (Exception e) {
			log.error("User update error: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, "system", "Failed to update user");
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	//TODO this is a copy & paste method.
	private Folder getOrCreateSystemSubFolder(String repositoryId, String name){
		log.debug("getOrCreateSystemSubFolder called with repositoryId=" + repositoryId + ", name=" + name);
		ContentService service = getContentService();
		if (service == null) {
			throw new RuntimeException("ContentService not available - dependency injection failed");
		}
		
		// DEBUG: Check PropertyManager configuration reading
		PropertyManager pm = getPropertyManager();
		if (pm != null) {
			String systemFolderConfig = pm.readValue(repositoryId, PropertyKey.SYSTEM_FOLDER);
			log.info("DEBUG: PropertyManager.readValue(repositoryId=" + repositoryId + ", 'system.folder') returned: '" + systemFolderConfig + "'");
			
			// Test if folder exists with this ID
			if (systemFolderConfig != null && !systemFolderConfig.trim().isEmpty()) {
				try {
					Folder testFolder = service.getFolder(repositoryId, systemFolderConfig);
					log.info("DEBUG: ContentService.getFolder() with ID '" + systemFolderConfig + "' returned: " + (testFolder != null ? testFolder.getName() : "NULL"));
				} catch (Exception e) {
					log.debug("DEBUG: Exception getting folder with ID '" + systemFolderConfig + "': " + e.getMessage());
				}
			}
		} else {
			log.debug("DEBUG: PropertyManager is null - cannot read configuration");
		}
		
		Folder systemFolder = service.getSystemFolder(repositoryId);
		log.info("DEBUG: getSystemFolder() returned: " + (systemFolder != null ? "ID=" + systemFolder.getId() + ", name=" + systemFolder.getName() : "NULL"));
		
		// CRITICAL FIX: If systemFolder is null, try to find .system folder directly before creating
		if (systemFolder == null) {
			log.warn("SystemFolder not found via PropertyManager, searching for .system folder directly in root");
			systemFolder = findExistingSystemFolderInRoot(repositoryId, service);
			
			if (systemFolder == null) {
				log.info("No .system folder found, attempting to create it for repository: " + repositoryId);
				systemFolder = createSystemFolder(repositoryId, service);
				if (systemFolder == null) {
					throw new RuntimeException("Failed to create .system folder for repository: " + repositoryId);
				}
			} else {
				log.info("Found existing .system folder directly: " + systemFolder.getId());
			}
		}

		// check existing folder
		List<Content> children = service.getChildren(repositoryId, systemFolder.getId());
		if(CollectionUtils.isNotEmpty(children)){
			for(Content child : children){
				if(ObjectUtils.equals(name, child.getName())){
					return (Folder)child;
				}
			}
		}

		// create
		PropertiesImpl properties = new PropertiesImpl();
		properties.addProperty(new PropertyStringImpl("cmis:name", name));
		properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
		properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
		Folder _target = service.createFolder(new SystemCallContext(repositoryId), repositoryId, properties, systemFolder, null, null, null, null);
		return _target;
	}

	/**
	 * Find existing .system folder directly in the root folder
	 * This is needed when PropertyManager cannot find the .system folder configuration
	 */
	private Folder findExistingSystemFolderInRoot(String repositoryId, ContentService service) {
		try {
			log.info("Searching for existing .system folder in root of repository: " + repositoryId);
			
			// Get root folder ID - repository-specific root folder lookup
			String rootFolderId = getRootFolderIdForRepository(repositoryId);
			if (rootFolderId == null) {
				log.error("Cannot determine root folder ID for repository: " + repositoryId);
				return null;
			}
			
			// Get root folder using the ID
			Folder rootFolder = service.getFolder(repositoryId, rootFolderId);
			if (rootFolder == null) {
				log.error("Root folder not found for repository: " + repositoryId);
				return null;
			}
			
			// Search for .system folder in root children
			List<Content> rootChildren = service.getChildren(repositoryId, rootFolderId);
			if (rootChildren != null) {
				for (Content child : rootChildren) {
					if (child instanceof Folder && ".system".equals(child.getName())) {
						log.info("Found existing .system folder in root: " + child.getId());
						return (Folder) child;
					}
				}
			}
			
			log.info("No .system folder found in root of repository: " + repositoryId);
			return null;
			
		} catch (Exception e) {
			log.error("Error searching for .system folder in root for repository: " + repositoryId, e);
			return null;
		}
	}

	/**
	 * Create the .system folder in the repository root
	 * This is needed when the system folder doesn't exist yet
	 */
	private Folder createSystemFolder(String repositoryId, ContentService service) {
		try {
			log.info("Creating .system folder for repository: " + repositoryId);
			
			// Get root folder ID - repository-specific root folder lookup
			String rootFolderId = getRootFolderIdForRepository(repositoryId);
			if (rootFolderId == null) {
				log.error("Cannot determine root folder ID for repository: " + repositoryId);
				return null;
			}
			
			// Get root folder using the ID
			Folder rootFolder = service.getFolder(repositoryId, rootFolderId);
			if (rootFolder == null) {
				log.error("Root folder not found for repository: " + repositoryId);
				return null;
			}
			
			// Create .system folder properties (SECURITY: Use .system with system-only access)
			PropertiesImpl properties = new PropertiesImpl();
			properties.addProperty(new PropertyStringImpl("cmis:name", ".system"));
			properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
			properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
			
			// Create the .system folder
			Folder systemFolder = service.createFolder(
				new SystemCallContext(repositoryId), 
				repositoryId, 
				properties, 
				rootFolder, 
				null, null, null, null
			);
			
			log.info(".system folder created successfully with ID: " + 
				(systemFolder != null ? systemFolder.getId() : "null"));
			
			return systemFolder;
			
		} catch (Exception e) {
			log.error("Failed to create .system folder for repository: " + repositoryId, e);
			return null;
		}
	}

	/**
	 * Get root folder ID for the specified repository
	 * Centralizes repository-specific root folder ID mapping
	 */
	private String getRootFolderIdForRepository(String repositoryId) {
		// Known root folder IDs for supported repositories
		switch (repositoryId) {
			case "bedroom":
				return "e02f784f8360a02cc14d1314c10038ff";
			case "canopy":
				return "ddd70e3ed8b847c2a364be81117c57ae";
			default:
				log.warn("Unknown repository ID: " + repositoryId + ". Add mapping if this is a valid repository.");
				return null;
		}
	}

	@PUT
	@Path("/changePassword/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String changePassword(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			@FormParam(FORM_OLDPASSWORD) String oldPassword, @FormParam(FORM_NEWPASSWORD) String newPassword,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		UserItem userItem = getContentServiceSafe().getUserItemById(repositoryId, userId);

		//TODO checkAuthorityForUser

		//password match
		if(AuthenticationUtil.passwordMatches(oldPassword, userItem.getPassowrd())){
			String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
			userItem.setPassowrd(hash);
			try{
				getContentService().update(new SystemCallContext(repositoryId), repositoryId, userItem);
			}catch(Exception e){
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
			}
		}else{
			// wrong previous password!
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_WRONGPASSWORD);
		}

		makeResult(status, result, errMsg);
		return result.toJSONString();

		/*User user = principalService.getUserById(repositoryId, userId);

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);
		if (status) {

			if (!AuthenticationUtil.passwordMatches(oldPassword, user.getPasswordHash())) {
				status = false;
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_WRONGPASSWORD);
			}

			// Edit & Update
			if (status) {
				// Edit the user info
				String passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
				user.setPasswordHash(passwordHash);
				setModifiedSignature(httpRequest, user);

				try {
					principalService.updateUser(repositoryId, user);
				} catch (Exception e) {
					e.printStackTrace();
					status = false;
					addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
				}

				setModifiedSignature(httpRequest, user);
				try {
					principalService.updateUser(repositoryId, user);
				} catch (Exception e) {
					e.printStackTrace();
					status = false;
					addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
				}

				if(status){
					String solrUserId = getPropertyManager().readValue(PropertyKey.SOLR_NEMAKI_USERID);
					if(user.getUserId().equals(solrUserId)){
						JSONObject capResult = solrResource.changeAdminPasswordImpl(repositoryId, newPassword, oldPassword, httpRequest);
						if (capResult.get(ITEM_STATUS).toString()  != SUCCESS){
							// TODO: Error handling
							status = false;
							addErrMsg(errMsg, ITEM_USER, capResult.get(ITEM_ERROR).toString());
						}
					}
				}
			}
		}
		makeResult(status, result, errMsg);
		return result.toJSONString();*/

	}

	@PUT
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			@FormParam(FORM_USERNAME) String name, @FormParam(FORM_FIRSTNAME) String firstName,
			@FormParam(FORM_LASTNAME) String lastName, @FormParam(FORM_EMAIL) String email,
			@FormParam("addFavorites") String addFavorites, @FormParam("removeFavorites") String removeFavorites,
			@FormParam(FORM_PASSWORD) String password, @FormParam("groups") String groupsJson,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Existing user
		UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		}


		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

		// Edit & Update
		if (status) {
			ContentService service = getContentService();

			// edit
			if (userId != null)
				user.setUserId(userId);
			if (name != null)
				user.setName(name);

			Map<String, Object> map = new HashMap<>();
			for(Property prop : user.getSubTypeProperties()) map.put(prop.getKey(), prop.getValue());
			JSONParser parser = new JSONParser();
			if (firstName != null)
				map.put("nemaki:firstName", firstName);
			if (lastName != null)
				map.put("nemaki:lastName", lastName);
			if (email != null)
				map.put("nemaki:email", email);
			if (addFavorites != null) {
				try {
					JSONArray adds = (JSONArray) (parser.parse(addFavorites));
					Object favs = map.get("nemaki:favorites");
					if(favs == null) favs = new ArrayList<String>();
					((List)favs).addAll(adds);
					map.put("nemaki:favorites", favs);
				} catch (ParseException e) {
					e.printStackTrace();
				}
			}
			if (removeFavorites != null) {
				try {
					JSONArray removes = (JSONArray) (parser.parse(removeFavorites));
					Object favs = map.get("nemaki:favorites");
					if(favs == null) favs = new ArrayList<String>();
					((List)favs).removeAll(removes);
					map.put("nemaki:favorites", favs);
				} catch (ParseException e) {
					e.printStackTrace();
				}
			}
			List<Property> properties = new ArrayList<>();
			for(String key : map.keySet()) properties.add(new Property(key, map.get(key)));
			user.setSubTypeProperties(properties);


			// update
			if (StringUtils.isNotBlank(password)) {
				// TODO Error handling
				user = getContentServiceSafe().getUserItemById(repositoryId, userId);

				// Edit & Update
				if (status) {
					// Edit the user info
					String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
					user.setPassowrd(passwordHash);
					setModifiedSignature(httpRequest, user);

					try {
						service.update(new SystemCallContext(repositoryId), repositoryId, user);
					} catch (Exception e) {
						e.printStackTrace();
						status = false;
						addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
					}
				}
			}
			setModifiedSignature(httpRequest, user);

			try {
				service.update(new SystemCallContext(repositoryId), repositoryId, user);
			} catch (Exception e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
			}

			// Process groups assignment
			if (groupsJson != null) {
				try {
					JSONArray groups = (JSONArray) parser.parse(groupsJson);
					updateUserGroups(repositoryId, userId, groups, service);
				} catch (Exception e) {
					log.warn("Failed to parse or apply groups for user " + userId + ": " + e.getMessage());
				}
			}
		}

		makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@DELETE
	@Path("/delete/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Existing user
		UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);
		if (user == null) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		}

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

		// Delete a user
		if (status) {
			try {
				log.debug("Attempting to delete user with ID: " + user.getId());
				getContentService().delete(new SystemCallContext(repositoryId), repositoryId, user.getId(), false);
				log.debug("User deletion completed successfully");
			} catch (Exception ex) {
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_DELETE);
			}
		} else {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_DELETE);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private boolean validateUser(boolean status, JSONArray errMsg, String userId, String userName, String firstName,
			String lastName) {
		if (StringUtils.isBlank(userId)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ErrorCode.ERR_MANDATORY);
		}

		if (StringUtils.isBlank(userName)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERNAME, ErrorCode.ERR_MANDATORY);
		}

		return status;
	}

	private boolean validateNewUser(boolean status, JSONArray errMsg, String userId, String userName, String firstName,
			String lastName, String password, String repositoryId) {
		log.debug("=== validateNewUser called for userId: " + userId + " ===");
		status = validateUser(status, errMsg, userId, userName, firstName, lastName);

		// userID uniqueness
		log.debug("=== About to call getContentService() ===");
		ContentService service = getContentService();
		log.debug("=== getContentService() returned: " + (service != null ? "SUCCESS" : "NULL") + " ===");
		
		if (service == null) {
			log.error("ContentService is null in validateNewUser - cannot validate user uniqueness");
			status = false;
			addErrMsg(errMsg, "system", "ContentService not available");
			return status;
		}
		
		UserItem user = service.getUserItemById(repositoryId, userId);
		if (user != null) {
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ErrorCode.ERR_ALREADYEXISTS);
		}

		if (StringUtils.isBlank(password)) {
			status = false;
			addErrMsg(errMsg, ITEM_PASSWORD, ErrorCode.ERR_MANDATORY);
		}
		return status;
	}

	@SuppressWarnings("unchecked")
	private JSONObject convertUserToJson(UserItem user, String repositoryId) {
		String created = new String();
		try {
			if (user.getCreated() != null) {
				created = DateUtil.formatSystemDateTime(user.getCreated());
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		String modified = new String();
		try {
			if (user.getModified() != null) {
				modified = DateUtil.formatSystemDateTime(user.getModified());
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		JSONObject userJSON = new JSONObject();
		userJSON.clear();
		userJSON.put(ITEM_USERID, user.getUserId());
		userJSON.put(ITEM_USERNAME, user.getName());
		userJSON.put(ITEM_TYPE, user.getType());
		userJSON.put(ITEM_CREATOR, user.getCreator());
		userJSON.put(ITEM_CREATED, created);
		userJSON.put(ITEM_MODIFIER, user.getModifier());
		userJSON.put(ITEM_MODIFIED, modified);

		Map<String, Object> kvMap = new HashMap<>();
		for(Property p : user.getSubTypeProperties()) kvMap.put(p.getKey(), p.getValue());

		userJSON.put(ITEM_FIRSTNAME, MapUtils.getObject(kvMap, "nemaki:firstName", ""));
		userJSON.put(ITEM_LASTNAME, MapUtils.getObject(kvMap, "nemaki:lastName", ""));
		userJSON.put(ITEM_EMAIL, MapUtils.getObject(kvMap, "nemaki:email", ""));
		userJSON.put("favorites", MapUtils.getObject(kvMap, "nemaki:favorites", new JSONArray()));

		boolean isAdmin = (user.isAdmin() == null) ? false : user.isAdmin();
		userJSON.put(ITEM_IS_ADMIN, isAdmin);

		// Add user's groups
		JSONArray userGroups = new JSONArray();
		try {
			List<jp.aegif.nemaki.model.GroupItem> allGroups = getContentService().getGroupItems(repositoryId);
			for (jp.aegif.nemaki.model.GroupItem group : allGroups) {
				// Check if this user is a member of this group
				List<String> members = group.getUsers();
				if (members != null && members.contains(user.getUserId())) {
					userGroups.add(group.getGroupId());
				}
			}
		} catch (Exception e) {
			log.error("Failed to retrieve groups for user " + user.getUserId() + ": " + e.getMessage());
			// Return empty array on error rather than failing the entire request
		}
		userJSON.put("groups", userGroups);

		return userJSON;
	}

	private boolean checkAuthorityForUser(boolean status, JSONArray errMsg, HttpServletRequest httpRequest,
			String resoureId, String repositoryId) {
		CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");

		String userId = callContext.getUsername();
		String password = callContext.getPassword();
		if (!userId.equals(resoureId) && !isAdminOperaiton(repositoryId, userId, password) && !isSystemUser(repositoryId, resoureId)) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTAUTHENTICATED);
		}
		return status;
	}

	private boolean isSystemUser(String repositoryId, String userId){
		boolean result = false;
		UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);

		result = user.isAdmin();
		if(result) return true;

		String solrUserId = getPropertyManager().readValue(PropertyKey.SOLR_NEMAKI_USERID);
		result = user.getUserId().equals(solrUserId);
		if(result) return true;

		return result;
	}

	private boolean isAdminOperaiton(String repositoryId, String userId, String password) {
		if (StringUtils.isBlank(userId) || StringUtils.isBlank(password)) {
			return false;
		}

		UserItem user = getContentServiceSafe().getUserItemById(repositoryId, userId);
		boolean isAdmin = (user.isAdmin() == null) ? false : user.isAdmin();
		if (isAdmin) {
			// password check
			boolean match = BCrypt.checkpw(password, user.getPassowrd());
			if (match)
				return true;
		}
		return false;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	/**
	 * Update user's group memberships
	 * Adds user to specified groups and removes from groups not in the list
	 *
	 * CRITICAL FIX: Fetches each group individually to get proper _rev field for CouchDB updates
	 * RETRY LOGIC: Handles CouchDB optimistic locking conflicts with automatic retry
	 */
	@SuppressWarnings("unchecked")
	private void updateUserGroups(String repositoryId, String userId, JSONArray newGroupIds, ContentService service) {
		if (service == null) {
			log.error("ContentService is null, cannot update user groups");
			return;
		}

		try {
			// Get all groups (for group IDs only)
			List<jp.aegif.nemaki.model.GroupItem> allGroups = service.getGroupItems(repositoryId);

			// Convert JSONArray to Set for easier comparison
			Set<String> newGroupSet = new java.util.HashSet<>();
			for (Object groupId : newGroupIds) {
				newGroupSet.add(groupId.toString());
			}

			// Process each group
			for (jp.aegif.nemaki.model.GroupItem groupListItem : allGroups) {
				String groupId = groupListItem.getGroupId();
				boolean shouldBeMember = newGroupSet.contains(groupId);

				// CRITICAL FIX: Fetch group individually to get proper _rev field
				jp.aegif.nemaki.model.GroupItem group = service.getGroupItemById(repositoryId, groupId);
				log.info("Fetched group '" + groupId + "' for user " + userId + ": " +
					(group != null ?
						"ID=" + group.getId() + ", Revision=" + group.getRevision() :
						"NULL"));

				if (group == null) {
					log.warn("Group " + groupId + " not found, skipping");
					continue;
				}

				if (group.getId() == null || group.getRevision() == null) {
					log.error("Group " + groupId + " has null ID or revision - ID=" + group.getId() + ", revision=" + group.getRevision());
					continue;
				}

				List<String> currentMembers = group.getUsers();
				if (currentMembers == null) {
					currentMembers = new ArrayList<>();
				}

				boolean isCurrentlyMember = currentMembers.contains(userId);

				if (shouldBeMember && !isCurrentlyMember) {
					// Add user to group with retry logic
					updateGroupMembershipWithRetry(repositoryId, userId, groupId, true, service);
				} else if (!shouldBeMember && isCurrentlyMember) {
					// Remove user from group with retry logic
					updateGroupMembershipWithRetry(repositoryId, userId, groupId, false, service);
				}
			}
		} catch (Exception e) {
			log.error("Failed to update groups for user " + userId + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Update group membership with ThreadLockService for proper concurrency control
	 *
	 * CRITICAL FIX (2025-10-13): Implements same locking pattern as CMIS core (ObjectServiceImpl)
	 * Pattern: Lock → Fetch fresh content → Update → Unlock
	 * This prevents race conditions that were causing revision conflicts
	 *
	 * @param repositoryId Repository ID
	 * @param userId User ID to add/remove
	 * @param groupId Group ID to modify
	 * @param addMember true to add user, false to remove user
	 * @param service ContentService instance
	 */
	private void updateGroupMembershipWithRetry(String repositoryId, String userId, String groupId,
											 boolean addMember, ContentService service) {
		// Get ThreadLockService - required for proper concurrency control
		ThreadLockService lockService = getThreadLockService();
		if (lockService == null) {
			log.error("!!! ThreadLockService not available - cannot safely update group membership for user " +
					  userId + " in group " + groupId);
			return;
		}

		// CRITICAL: Acquire write lock for the group document (same pattern as CMIS core)
		Lock lock = lockService.getWriteLock(repositoryId, groupId);
		try {
			lock.lock();
			log.error("!!! Acquired write lock for group " + groupId + " (user " + userId + " membership update)");

			// RETRY LOOP: Handle CouchDB optimistic locking conflicts
			// If the group document was modified before we acquired the lock, retry with fresh revision
			int maxRetries = 3;
			for (int attempt = 1; attempt <= maxRetries; attempt++) {
				try {
					log.error("!!! [" + userId + "] Attempt " + attempt + " to update group " + groupId);

					// CACHE BYPASS: Fetch group with fresh revision directly from database (bypassing cache)
					// This ensures we get the latest _rev from CouchDB on each retry attempt
					jp.aegif.nemaki.model.GroupItem group = service.getGroupItemByIdFresh(repositoryId, groupId);

					if (group == null) {
						log.error("Group " + groupId + " not found");
						return;
					}

					if (group.getId() == null || group.getRevision() == null) {
						log.error("Group " + groupId + " has null ID or revision - ID=" + group.getId() +
								  ", revision=" + group.getRevision());
						return;
					}

					log.error("!!! [" + userId + "] Group fetched: ID=" + group.getId() + ", Rev=" + group.getRevision());

					// Get current members
					List<String> currentMembers = group.getUsers();
					if (currentMembers == null) {
						currentMembers = new ArrayList<>();
					}

					// Apply membership change
					boolean modified = false;
					if (addMember) {
						if (!currentMembers.contains(userId)) {
							currentMembers.add(userId);
							modified = true;
						}
					} else {
						if (currentMembers.contains(userId)) {
							currentMembers.remove(userId);
							modified = true;
						}
					}

					// Only update if there's actually a change
					if (!modified) {
						log.info("No membership change needed for user " + userId + " in group " + groupId);
						return;
					}

					// Update the group (lock held - atomic operation)
					log.error("!!! [" + userId + "] Attempting CouchDB update with revision " + group.getRevision());
					group.setUsers(currentMembers);
					service.update(new SystemCallContext(repositoryId), repositoryId, group);

					// Success!
					String action = addMember ? "Added" : "Removed";
					log.error("!!! SUCCESS: " + action + " user " + userId + " " + (addMember ? "to" : "from") +
							  " group " + groupId + " on attempt " + attempt);
					return; // Exit retry loop on success

				} catch (RuntimeException e) {
					// Check if this is a CouchDB revision conflict
					if (e.getMessage() != null && e.getMessage().contains("revision conflict")) {
						if (attempt < maxRetries) {
							log.error("!!! Revision conflict on attempt " + attempt + " for user " + userId +
									  " in group " + groupId + ", retrying with fresh revision...");
							// Continue to next retry iteration
							continue;
						} else {
							log.error("!!! Max retries (" + maxRetries + ") exceeded for user " + userId +
									  " in group " + groupId + ": " + e.getMessage(), e);
							throw e; // Rethrow after max retries
						}
					} else {
						// Not a revision conflict, don't retry
						log.error("!!! Non-conflict error for user " + userId + " in group " + groupId +
								  ": " + e.getMessage(), e);
						throw e;
					}
				}
			}

		} catch (Exception e) {
			String action = addMember ? "add" : "remove";
			log.error("Failed to " + action + " user " + userId + " " + (addMember ? "to" : "from") +
					  " group " + groupId + " after all retries: " + e.getMessage(), e);
		} finally {
			lock.unlock();
			log.error("!!! Released write lock for group " + groupId);
		}
	}


}
