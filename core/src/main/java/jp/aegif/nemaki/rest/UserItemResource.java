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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;

@Path("/repo/{repositoryId}/user/")
public class UserItemResource extends ResourceBase {

	private ContentService contentService;
	private PropertyManager propertyManager;

	public UserItemResource() {
		super();
		// TODO Auto-generated constructor stub
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public Response list(@PathParam("repositoryId") String repositoryId) {
		System.out.println("=== UserItemResource.list() called for repository: " + repositoryId + " ===");
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Get all users list
		List<UserItem> userList;
		try {
			System.out.println("=== UserItemResource: About to call contentService.getUserItems() ===");
			userList = contentService.getUserItems(repositoryId);
			System.out.println("=== UserItemResource: contentService.getUserItems() returned " + userList.size() + " users ===");
			for (UserItem user : userList) {
				System.out.println("=== UserItemResource: Processing user: " + user.getUserId() + " ===");
				JSONObject userJSON = convertUserToJson(user);
				listJSON.add(userJSON);
			}
			result.put("users", listJSON);
			result.put("status", "success");
			System.out.println("=== UserItemResource: Returning result with " + listJSON.size() + " users ===");
			return Response.ok(result.toJSONString()).build();
		} catch (Exception e) {
			System.out.println("=== UserItemResource: Exception occurred: " + e.getClass().getName() + ": " + e.getMessage() + " ===");
			e.printStackTrace();
			
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

		UserItem user = contentService.getUserItemById(repositoryId, userId);

		if (user == null) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		} else {
			result.put("user", convertUserToJson(user));
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
		users = contentService.getUserItems(repositoryId);
		for (UserItem user : users) {
			if (user.getUserId().contains(query) || user.getName().contains(query)) {
				JSONObject userJSON = convertUserToJson(user);
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
			@FormParam(FORM_EMAIL) String email, @Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Validation
		status = validateNewUser(status, errMsg, userId, name, firstName, lastName, password, repositoryId);

		// Create a user
		if (status) {
			// Generate a password hash
			String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

			// parent
			final Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");

			UserItem user = new UserItem(null, NemakiObjectType.nemakiUser, userId, name, passwordHash, false, usersFolder.getId());

			Map<String, Object> map = new HashMap<>();
			if (firstName != null) map.put("nemaki:firstName", firstName);
			if (lastName != null) map.put("nemaki:lastName", lastName);
			if (email != null) map.put("nemaki:email", email);
			List<Property> properties = new ArrayList<>();
			for(String key : map.keySet()) properties.add(new Property(key, map.get(key)));
			user.setSubTypeProperties(properties);

			setFirstSignature(httpRequest, user);

			contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, user);

		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	//TODO this is a copy & paste method.
	private Folder getOrCreateSystemSubFolder(String repositoryId, String name){
		Folder systemFolder = contentService.getSystemFolder(repositoryId);

		// check existing folder
		List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
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
		Folder _target = contentService.createFolder(new SystemCallContext(repositoryId), repositoryId, properties, systemFolder, null, null, null, null);
		return _target;
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

		UserItem userItem = contentService.getUserItemById(repositoryId, userId);

		//TODO checkAuthorityForUser

		//password match
		if(AuthenticationUtil.passwordMatches(oldPassword, userItem.getPassowrd())){
			String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
			userItem.setPassowrd(hash);
			try{
				contentService.update(new SystemCallContext(repositoryId), repositoryId, userItem);
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
					String solrUserId = propertyManager.readValue(PropertyKey.SOLR_NEMAKI_USERID);
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
			@FormParam(FORM_PASSWORD) String password, @Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Existing user
		UserItem user = contentService.getUserItemById(repositoryId, userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		}


		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

		// Edit & Update
		if (status) {
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
				user = contentService.getUserItemById(repositoryId, userId);

				// Edit & Update
				if (status) {
					// Edit the user info
					String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
					user.setPassowrd(passwordHash);
					setModifiedSignature(httpRequest, user);

					try {
						contentService.update(new SystemCallContext(repositoryId), repositoryId, user);
					} catch (Exception e) {
						e.printStackTrace();
						status = false;
						addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
					}
				}
			}
			setModifiedSignature(httpRequest, user);

			try {
				contentService.update(new SystemCallContext(repositoryId), repositoryId, user);
			} catch (Exception e) {
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_UPDATE);
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
		UserItem user = contentService.getUserItemById(repositoryId, userId);
		if (user == null) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ErrorCode.ERR_NOTFOUND);
		}

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

		// Delete a user
		if (status) {
			try {
				contentService.delete(new SystemCallContext(repositoryId), repositoryId, user.getId(), false);
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
		status = validateUser(status, errMsg, userId, userName, firstName, lastName);

		// userID uniqueness
		UserItem user = contentService.getUserItemById(repositoryId, userId);
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
	private JSONObject convertUserToJson(UserItem user) {
		SimpleDateFormat sdf = new SimpleDateFormat(SystemConst.DATETIME_FORMAT);
		String created = new String();
		try {
			if (user.getCreated() != null) {
				created = sdf.format(user.getCreated().getTime());
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		String modified = new String();
		try {
			if (user.getModified() != null) {
				modified = sdf.format(user.getModified().getTime());
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
		UserItem user = contentService.getUserItemById(repositoryId, userId);

		result = user.isAdmin();
		if(result) return true;

		String solrUserId = propertyManager.readValue(PropertyKey.SOLR_NEMAKI_USERID);
		result = user.getUserId().equals(solrUserId);
		if(result) return true;

		return result;
	}

	private boolean isAdminOperaiton(String repositoryId, String userId, String password) {
		if (StringUtils.isBlank(userId) || StringUtils.isBlank(password)) {
			return false;
		}

		UserItem user = contentService.getUserItemById(repositoryId, userId);
		boolean isAdmin = (user.isAdmin() == null) ? false : user.isAdmin();
		if (isAdmin) {
			// password check
			boolean match = BCrypt.checkpw(password, user.getPassowrd());
			if (match)
				return true;
		}
		return false;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
}
