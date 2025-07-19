/*****************************************************************************
 Copyright (c) 2013 aegif.

 This file is part of NemakiWare.

 NemakiWare is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 NemakiWare is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with NemakiWare.
 If not, see <http://www.gnu.org/licenses/>.

 Contributors:
 linzhixing(https://github.com/linzhixing) - initial API and implementation
 */
package jp.aegif.nemaki.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.util.GregorianCalendar;
import java.util.TimeZone;

@Component
public class ResourceBase {

	private static final ObjectMapper mapper = new ObjectMapper();

	static final String TYPE_GROUP = "group";
	static final String PARENTID = "/";
	static final String SUCCESS = "success";
	static final String FAILURE = "failure";
	static final String DOCNAME_VIEW="_design/_repo";
	static final String VIEW_ALL = "_all_dbs";
	static final String SPACE = "";

	static final String FILEPATH_VIEW = "views.json";

	static final String API_ADD = "add";
	static final String API_REMOVE = "remove";

	static final String FORM_USERNAME = "name";
	static final String FORM_PASSWORD = "password";
	static final String FORM_FIRSTNAME = "firstName";
	static final String FORM_LASTNAME = "lastName";
	static final String FORM_EMAIL = "email";
	static final String FORM_CREATOR = "creator";
	static final String FORM_MODIFIER = "modifier";
	static final String FORM_GROUPNAME = "name";
	static final String FORM_MEMBER_USERS = "users";
	static final String FORM_MEMBER_GROUPS = "groups";
	static final String FORM_ID = "id";

	static final String FORM_NEWPASSWORD = "newPassword";
	static final String FORM_OLDPASSWORD = "oldPassword";

	static final String ITEM_USERID = "userId";
	static final String ITEM_USER = "user";
	static final String ITEM_USERNAME = "userName";
	static final String ITEM_PASSWORD = "password";
	static final String ITEM_NEWPASSWORD = "newPassword";
	static final String ITEM_FIRSTNAME = "firstName";
	static final String ITEM_LASTNAME = "lastName";
	static final String ITEM_EMAIL = "email";
	static final String ITEM_PARENTID = "parentId";
	static final String ITEM_PATH = "path";
	static final String ITEM_TYPE = "type";
	static final String ITEM_CREATOR = "creator";
	static final String ITEM_CREATED = "created";
	static final String ITEM_MODIFIER = "modifier";
	static final String ITEM_MODIFIED = "modified";
	static final String ITEM_IS_ADMIN = "isAdmin";
	static final String ITEM_GROUPID = "groupId";
	static final String ITEM_GROUP = "group";
	static final String ITEM_GROUPNAME = "groupName";
	static final String ITEM_MEMBER_USERS = "users";
	static final String ITEM_MEMBER_GROUPS = "groups";
	static final String ITEM_MEMBER_USERSSIZE = "usersSize";
	static final String ITEM_MEMBER_GROUPSSIZE = "groupsSize";
	static final String ITEM_ALLUSERS = "users";
	static final String ITEM_ALLGROUPS = "groups";
	static final String ITEM_STATUS = "status";
	static final String ITEM_ERROR = "error";
	static final String ITEM_COUCHDBRESPONSE = "couchDbResponse";
	static final String ITEM_COUCHDBRESTURL = "couchDBRestTUrl";
	static final String ITEM_DATABASE = "database";
	static final String ITEM_URL = "url";
	static final String ITEM_DATABASES = "databases";
	static final String ITEM_VIEW = "view";
	static final String ITEM_PROPERTIESFILE = "propertiesFile";
	static final String ITEM_ARCHIVE = "archive";



	//Set daoService
	public ResourceBase(){

	}

	//Utility methods
	@SuppressWarnings("unchecked")
	protected JSONArray addErrMsg(JSONArray errMsg, String item, String msg){
		JSONObject obj = new JSONObject();
		obj.put(item, msg);
		errMsg.add(obj);
		return errMsg;
	}
	@SuppressWarnings("unchecked")
	protected JSONObject makeResult(boolean status, JSONObject result, JSONArray errMsg){
		if(status && errMsg.size() == 0){
			result.put(ITEM_STATUS, SUCCESS);
		}else{
			result.put(ITEM_STATUS, FAILURE);
			result.put(ITEM_ERROR, errMsg);
		}
		return result;
	}

	protected boolean checkAdmin(JSONArray errMsg, HttpServletRequest request){
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		
		// DEBUG: Add detailed logging to diagnose admin check issue
		try {
			java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-admin-debug.log", true);
			debugWriter.write("=== ADMIN CHECK DEBUG ===\n");
			debugWriter.write("Timestamp: " + new java.util.Date() + "\n");
			debugWriter.write("CallContext: " + (callContext != null ? "not null" : "null") + "\n");
			if (callContext != null) {
				debugWriter.write("Username: " + callContext.getUsername() + "\n");
				debugWriter.write("Repository: " + callContext.getRepositoryId() + "\n");
				Boolean _isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
				debugWriter.write("IS_ADMIN flag: " + _isAdmin + "\n");
				debugWriter.write("CallContextKey.IS_ADMIN constant: " + CallContextKey.IS_ADMIN + "\n");
			}
			debugWriter.write("========================\n");
			debugWriter.close();
		} catch (Exception e) {
			// Ignore debug errors
		}
		
		Boolean _isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
		boolean isAdmin = _isAdmin != null && _isAdmin;
		if(!isAdmin){
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, callContext.getRepositoryId());
		}
		return isAdmin;
	}

	protected ArrayNode addErrMsg(ArrayNode errMsg, String item, String msg){
		ObjectNode obj = mapper.createObjectNode();
		obj.put(item, msg);
		errMsg.add(obj);
		return errMsg;
	}

	protected ObjectNode makeResult(boolean status, ObjectNode result, ArrayNode errMsg){
		if(status && errMsg.size() == 0){
			result.put(ITEM_STATUS, SUCCESS);
		}else{
			result.put(ITEM_STATUS, FAILURE);
			result.set(ITEM_ERROR, errMsg);
		}
		return result;
	}

	protected boolean checkAdmin(ArrayNode errMsg, HttpServletRequest request){
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		Boolean _isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
		boolean isAdmin = _isAdmin != null && _isAdmin;
		if(!isAdmin){
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, callContext.getRepositoryId());
		}
		return isAdmin;
	}

	protected boolean nonZeroString(String param){
		return param != null && !param.equals("");
	}
	protected GregorianCalendar millisToCalendar(long millis) {
		GregorianCalendar calendar = new GregorianCalendar();
		calendar.setTimeZone(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(millis);
		return calendar;
	}

	protected void setFirstSignature(HttpServletRequest request, NodeBase nodeBase){
		CallContext callContext = (CallContext)request.getAttribute("CallContext");
		nodeBase.setCreator(callContext.getUsername());
		nodeBase.setCreated(millisToCalendar(System.currentTimeMillis()));
		nodeBase.setModifier(callContext.getUsername());
		nodeBase.setModified(millisToCalendar(System.currentTimeMillis()));
	}

	protected void setModifiedSignature(HttpServletRequest request, NodeBase nodeBase){
		CallContext callContext = (CallContext)request.getAttribute("CallContext");
		nodeBase.setModifier(callContext.getUsername());
		nodeBase.setModified(millisToCalendar(System.currentTimeMillis()));
	}
}
