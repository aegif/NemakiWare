package jp.aegif.nemaki.api.resources;

import java.text.SimpleDateFormat;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
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

import jp.aegif.nemaki.api.resources.AuthenticationFilter.UserInfo;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.PasswordHasher;

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
			e.printStackTrace();
			addErrMsg(errMsg, ITEM_ALLUSERS, ERR_LIST);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
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

	/**
	 * Search user by name TODO Use Solr
	 * 
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
		status = newUserValidation(errMsg, userId, name, password);

		// Create a user
		if (status) {
			// initialize mandatory but space-allowed parameters
			if (firstName == null)
				firstName = "";
			if (lastName == null)
				lastName = "";
			if (email == null)
				email = "";

			// Generate a password hash
			String passwordHash = PasswordHasher.hash(password);

			User user = new User(userId, name, firstName, lastName, email,
					passwordHash);
			setSignature(getUserInfo(httpRequest), user);

			try {
				principalService.createUser(user);
			} catch (Exception ex) {
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_CREATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@PUT
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("id") String userId,
			@FormParam(FORM_PASSWORD) String password,
			@FormParam(FORM_NEWPASSWORD) String newPassword,
			@FormParam(FORM_USERNAME) String name,
			@FormParam(FORM_FIRSTNAME) String firstName,
			@FormParam(FORM_LASTNAME) String lastName,
			@FormParam(FORM_EMAIL) String email,
			@FormParam("admin") String admin,
			@FormParam("adminpass") String adminpass,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		boolean cmpPass = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		User user = new User();

		// Validation
		if (!nonZeroString(name)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERNAME, ERR_MANDATORY);
		}
		try {
			user = principalService.getUserById(userId);
		} catch (Exception ex) {
			ex.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}

		if (status) {
			cmpPass = PasswordHasher.isCompared(password,
					user.getPasswordHash());
			if (isAdmin(admin, adminpass))
				cmpPass = true; // Admin permission check
			if (!cmpPass) {
				status = false;
				addErrMsg(errMsg, ITEM_PASSWORD, ERR_WRONGPASSWORD);
			}
		}

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
			if (nonZeroString(newPassword)) {
				String passwordHash = PasswordHasher.hash(newPassword);
				user.setPasswordHash(passwordHash);
			}
			setModifiedSignature(getUserInfo(httpRequest), user);

			try {
				principalService.updateUser(user);
			} catch (Exception ex) {
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@DELETE
	@Path("/delete/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("id") String userId,
			@FormParam(FORM_PASSWORD) String password,
			@FormParam("admin") String admin,
			@FormParam("adminpass") String adminpass) {
		boolean status = true;
		boolean cmpPass = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		User user = new User();

		// Validation
		try {
			user = principalService.getUserById(userId);
		} catch (Exception ex) {
			ex.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_USER, ERR_NOTFOUND);
		}

		if (status) {
			cmpPass = PasswordHasher.isCompared(password,
					user.getPasswordHash());
			if (isAdmin(admin, adminpass))
				cmpPass = true; // Admin permission check
			if (!cmpPass) {
				status = false;
				addErrMsg(errMsg, ITEM_PASSWORD, ERR_WRONGPASSWORD);
			}
		}

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

	private boolean newUserValidation(JSONArray errMsg, String userId,
			String userName, String password) {
		boolean status = true;
		if (!nonZeroString(userId)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERID, ERR_MANDATORY);
		}

		if (!nonZeroString(userName)) {
			status = false;
			addErrMsg(errMsg, ITEM_USERNAME, ERR_MANDATORY);
		}

		if (!nonZeroString(password)) {
			status = false;
			addErrMsg(errMsg, ITEM_PASSWORD, ERR_MANDATORY);
		}
		return status;
	}
}
