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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

@Component
@Path("/repo/{repositoryId}/user/")
public class UserResource extends ResourceBase {

	PrincipalService principalService;
	
	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public String list(@PathParam("repositoryId") String repositoryId) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Get all users list
		List<User> userList;
		try {
			userList = principalService.getUsers(repositoryId);
			for (User user : userList) {
				JSONObject userJSON = convertUserToJson(user);
				listJSON.add(userJSON);
			}
			result.put("users", listJSON);
		} catch (Exception e) {
			status = false;
			e.printStackTrace();
			addErrMsg(errMsg, ITEM_ALLUSERS, ERR_LIST);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Validation
		if(StringUtils.isBlank(userId)){
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ERR_MANDATORY);
		}

		User user = principalService.getUserById(repositoryId, userId);

		if (user == null) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		} else {
			result.put("user",  convertUserToJson(user));
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Search user by id
	 * TODO Use Solr
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

		List<User> users;
		JSONArray queriedUsers = new JSONArray();
		users = principalService.getUsers(repositoryId);
		for (User user : users) {
			if (user.getUserId().startsWith(query) || user.getName().startsWith(query)) {
				JSONObject userJSON = convertUserToJson(user);
				queriedUsers.add(userJSON);
			}
		}

		if (queriedUsers.isEmpty()) {
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
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
			@FormParam(FORM_USERNAME) String name,
			@FormParam(FORM_PASSWORD) String password,
			@FormParam(FORM_FIRSTNAME) String firstName,
			@FormParam(FORM_LASTNAME) String lastName,
			@FormParam(FORM_EMAIL) String email,
			@Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Validation
		status = validateNewUser(status, errMsg, userId, name, firstName, lastName, password, repositoryId);

		// Create a user
		if (status) {
			// initialize mandatory but space-allowed parameters
			if (StringUtils.isBlank(lastName))
				lastName = "";
			if (StringUtils.isBlank(email))
				email = "";

			// Generate a password hash
			String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());

			User user = new User(userId, name, firstName, lastName, email,
					passwordHash);
			setSignature(getUserInfo(httpRequest), user);

			//TODO Error handling
			principalService.createUser(repositoryId, user);

		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@PUT
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("repositoryId") String repositoryId, @PathParam("id") String userId,
			@FormParam(FORM_USERNAME) String name,
			@FormParam(FORM_FIRSTNAME) String firstName,
			@FormParam(FORM_LASTNAME) String lastName,
			@FormParam(FORM_EMAIL) String email,
			@FormParam("addFavorites") String addFavorites,
			@FormParam("removeFavorites") String removeFavorites,
			@FormParam(FORM_PASSWORD) String password,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing user
		User user = principalService.getUserById(repositoryId, userId);

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);
		//status = validateUser(status, errMsg, userId, name, firstName, lastName);

		// Edit & Update
		if (status) {
			// Edit the user info
			// if a parameter is not input, it won't be modified.
			if (userId != null)
				user.setUserId(userId);
			if (name != null)
				user.setName(name);
			if (firstName != null)
				user.setFirstName(firstName);
			if (lastName != null)
				user.setLastName(lastName);
			if (email != null)
				user.setEmail(email);
			if(addFavorites != null){
				try {
					JSONArray l = (JSONArray)(new JSONParser().parse(addFavorites));
					Set<String>fs = user.getFavorites();
					if(CollectionUtils.isEmpty(fs)){
						fs = new HashSet<String>();
					}
					fs.addAll(l);
					user.setFavorites(fs);
					System.out.println();
					//fs.addAll(l);
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if(removeFavorites != null){
				try {
					JSONArray l = (JSONArray)(new JSONParser().parse(removeFavorites));
					user.getFavorites().removeAll(l);
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if (StringUtils.isNotBlank(password)){
				//TODO Error handling
				user = principalService.getUserById(repositoryId, userId);

				// Edit & Update
				if (status) {
					// Edit the user info
					String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
					user.setPasswordHash(passwordHash);
					setModifiedSignature(getUserInfo(httpRequest), user);

					try{
						principalService.updateUser(repositoryId, user);
					}catch(Exception e){
						e.printStackTrace();
						status = false;
						addErrMsg(errMsg, ITEM_USER, ERR_UPDATE);
					}
				}
			}
			setModifiedSignature(getUserInfo(httpRequest), user);

			try{
				principalService.updateUser(repositoryId, user);
			}catch(Exception e){
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_UPDATE);
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

		//Existing user
		User user = principalService.getUserById(repositoryId, userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId, repositoryId);

		// Delete a user
		if (status) {
			try {
				principalService.deleteUser(repositoryId, user.getId());
			} catch (Exception ex) {
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_DELETE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private boolean validateUser(boolean status, JSONArray errMsg,
			String userId, String userName, String firstName, String lastName) {
		if (StringUtils.isBlank(userId)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ERR_MANDATORY);
		}

		if (StringUtils.isBlank(userName)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERNAME, ERR_MANDATORY);
		}

		return status;
	}

	private boolean validateNewUser(boolean status, JSONArray errMsg,
			String userId, String userName, String firstName, String lastName, String password, String repositoryId) {
		status = validateUser(status, errMsg, userId, userName, firstName, lastName);

		//userID uniqueness
		User user = principalService.getUserById(repositoryId, userId);
		if(user != null){
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ERR_ALREADYEXISTS);
		}

		if (StringUtils.isBlank(password)) {
			status = false;
			addErrMsg(errMsg, ITEM_PASSWORD, ERR_MANDATORY);
		}
		return status;
	}

	@SuppressWarnings("unchecked")
	private JSONObject convertUserToJson(User user) {
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
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
		userJSON.put(ITEM_FIRSTNAME, user.getFirstName());
		userJSON.put(ITEM_LASTNAME, user.getLastName());
		userJSON.put(ITEM_EMAIL, user.getEmail());
		userJSON.put(ITEM_TYPE, user.getType());
		userJSON.put(ITEM_CREATOR, user.getCreator());
		userJSON.put(ITEM_CREATED, created);
		userJSON.put(ITEM_MODIFIER, user.getModifier());
		userJSON.put(ITEM_MODIFIED, modified);
		
		boolean isAdmin = (user.isAdmin() == null) ? false : true; 
		userJSON.put(ITEM_IS_ADMIN, isAdmin);

		JSONArray jfs = new JSONArray();
		Set<String>ufs = user.getFavorites();
		if(CollectionUtils.isNotEmpty(ufs)){
			Iterator<String> ufsItr = ufs.iterator();
			while(ufsItr.hasNext()){
				jfs.add(ufsItr.next());
			}
		}
		userJSON.put("favorites", jfs);
		
		return userJSON;
	}

	private boolean checkAuthorityForUser(boolean status, JSONArray errMsg, HttpServletRequest httpRequest, String resoureId, String repositoryId){
		UserInfo userInfo = AuthenticationFilter.getUserInfo(httpRequest);

		if(!userInfo.getUserId().equals(resoureId) &&
				!isAdmin(repositoryId, userInfo.getUserId(), userInfo.getPassword()) ){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTAUTHENTICATED);
		}
		return status;
	}

	private boolean isAdmin(String repositoryId, String userId, String password) {
		if(StringUtils.isBlank(userId) || StringUtils.isBlank(password)){
			return false;
		}

		User user = principalService.getUserById(repositoryId, userId);
		boolean isAdmin = (user.isAdmin() == null) ? false : user.isAdmin();
		if(isAdmin){
			//password check
			boolean match = BCrypt.checkpw(password, user.getPasswordHash());
			if(match) return true;
		}
		return false;
	}
	
	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}
