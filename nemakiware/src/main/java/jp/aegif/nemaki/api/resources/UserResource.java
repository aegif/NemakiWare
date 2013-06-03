package jp.aegif.nemaki.api.resources;

import java.text.SimpleDateFormat;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
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

import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.PasswordHasher;

import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

@Component
@Path("/user")
public class UserResource extends ResourceBase {

	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public String list() {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Get all users list
		List<User> userList;
		try {
			userList = principalService.getUsers();
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
	public String show(@PathParam("id") String userId) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Validation
		if(StringUtils.isBlank(userId)){
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ERR_MANDATORY);
		}
			
		User user = principalService.getUserById(userId);
		
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
	public String search(@QueryParam("query") String query) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		List<User> users;
		JSONArray queriedUsers = new JSONArray();
		users = principalService.getUsers();
		for (User user : users) {
			if (user.getUserId().equals(query)) {
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
	public String create(@PathParam("id") String userId,
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
		status = validateNewUser(status, errMsg, userId, name, firstName, lastName, password);

		// Create a user
		if (status) {
			// initialize mandatory but space-allowed parameters
			if (StringUtils.isBlank(lastName))
				lastName = "";
			if (StringUtils.isBlank(email))
				email = "";

			// Generate a password hash
			String passwordHash = PasswordHasher.hash(password);

			User user = new User(userId, name, firstName, lastName, email,
					passwordHash);
			setSignature(getUserInfo(httpRequest), user);

			//TODO Error handling
			principalService.createUser(user);
			
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@PUT
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("id") String userId,
			@FormParam(FORM_USERNAME) String name,
			@FormParam(FORM_FIRSTNAME) String firstName,
			@FormParam(FORM_LASTNAME) String lastName,
			@FormParam(FORM_EMAIL) String email,
			@FormParam(FORM_NEWPASSWORD) String newPassword,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Existing user
		User user = principalService.getUserById(userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId);
		status = validateUser(status, errMsg, userId, name, firstName, lastName);
	
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
			setModifiedSignature(getUserInfo(httpRequest), user);

			try{
				principalService.updateUser(user);
			}catch(Exception e){
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_UPDATE);
			}
		}
		
		makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	@PUT
	@Path("/updatePassword/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String updatePassword(@PathParam("id") String userId,
			@FormParam(FORM_NEWPASSWORD) String newPassword,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing user
		User user = principalService.getUserById(userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}
		
		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId);
		status = validateUserPassword(status, errMsg, userId, newPassword);
		
		//TODO Error handling
		user = principalService.getUserById(userId);
	
		// Edit & Update
		if (status) {
			// Edit the user info
			String passwordHash = PasswordHasher.hash(newPassword);
			user.setPasswordHash(passwordHash);
			setModifiedSignature(getUserInfo(httpRequest), user);

			try{
				principalService.updateUser(user);
			}catch(Exception e){
				e.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_UPDATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@DELETE
	@Path("/delete/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("id") String userId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		//Existing user
		User user = principalService.getUserById(userId);
		if(user == null){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}

		// Validation
		status = checkAuthorityForUser(status, errMsg, httpRequest, userId);
		
		// Delete a user
		if (status) {
			try {
				principalService.deleteUser(userId);
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

		if(StringUtils.isBlank(firstName)){
			status = false;
			addErrMsg(errMsg, ITEM_FIRSTNAME, ERR_MANDATORY);
		}
		
		return status;
	}
	
	private boolean validateUserPassword(boolean status, JSONArray errMsg,
			String userId, String newPassword){
		if(StringUtils.isBlank(newPassword)){
			status = false;
			addErrMsg(errMsg, ITEM_PASSWORD, newPassword);
		}
		return status;
	}
	
	private boolean validateNewUser(boolean status, JSONArray errMsg,
			String userId, String userName, String firstName, String lastName, String password) {
		status = validateUser(status, errMsg, userId, userName, firstName, lastName);
		
		//userID uniqueness
		User user = principalService.getUserById(userId);
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

		return userJSON;
	}
	
	private boolean checkAuthorityForUser(boolean status, JSONArray errMsg, HttpServletRequest httpRequest, String resoureId){
		UserInfo userInfo = AuthenticationFilter.getUserInfo(httpRequest);
		
		if(!userInfo.getUserId().equals(resoureId) && 
		   !isAdmin(userInfo.getUserId(), userInfo.getPassword()) ){
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTAUTHENTICATED);
		}
		return status;
	}
}
