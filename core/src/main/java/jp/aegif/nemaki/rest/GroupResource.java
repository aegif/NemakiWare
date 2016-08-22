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

import jp.aegif.nemaki.common.ErrorCode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

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
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@Path("/repo/{repositoryId}/group-deprecated")
public class GroupResource extends ResourceBase{

	PrincipalService principalService;

	@SuppressWarnings("unchecked")
	@GET
	@Path("/search")
	@Produces(MediaType.APPLICATION_JSON)
	public String search(@PathParam("repositoryId") String repositoryId, @QueryParam("query") String query){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		List<Group> groups = this.principalService.getGroups(repositoryId);
		JSONArray queriedGroups = new JSONArray();

		for(Group g : groups) {
			if ( g.getGroupId().startsWith(query)|| g.getName().startsWith(query)) {
				queriedGroups.add(this.convertGroupToJson(g));
			}
		}

		if( queriedGroups.size() == 0 ){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}
		else {
			result.put("result", queriedGroups);
		}

		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public String list(@PathParam("repositoryId") String repositoryId){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		List<Group> groupList;
		try{
			groupList = principalService.getGroups(repositoryId);
			for(Group group : groupList){
				JSONObject groupJSON = convertGroupToJson(group);
				listJSON.add(groupJSON);
			}
			result.put(ITEM_ALLGROUPS, listJSON);
		}catch(Exception ex){
			ex.printStackTrace();
			addErrMsg(errMsg, ITEM_ALLGROUPS, ErrorCode.ERR_LIST);
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("id") String groupId){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		Group group = principalService.getGroupById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}else{
			result.put("group", convertGroupToJson(group));
		}
		makeResult(status, result, errMsg);
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	@POST
	@Path("/create/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String create(@PathParam("repositoryId") String repositoryId,
						 @PathParam("id") String groupId,
						 @FormParam(FORM_GROUPNAME) String name,
						 @FormParam("users") String users,
						 @FormParam("groups") String groups,
						 @Context HttpServletRequest httpRequest
						 ){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Validation
		status = validateNewGroup(repositoryId, status, errMsg, groupId, name);

		//Edit group info
		JSONArray _users = parseJsonArray(users);
		JSONArray _groups = parseJsonArray(groups);
		Group group = new Group(groupId, name, _users, _groups);
		setFirstSignature(httpRequest, group);

		//Create a group
		if(status){
			try{
				principalService.createGroup(repositoryId, group);
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_CREATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@PUT
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("repositoryId") String repositoryId,
						 @PathParam("id") String groupId,
						 @FormParam(FORM_GROUPNAME) String name,
						 @FormParam("users") String users,
						 @FormParam("groups") String groups,
						 @Context HttpServletRequest httpRequest){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing group
		Group group = principalService.getGroupById(repositoryId, groupId);

		//Validation
		status = validateGroup(status, errMsg, groupId, name);

		//Edit & Update
		if(status){
			//Edit group info
			//if a parameter is not input, it won't be modified.
			group.setName(name);
			group.setUsers(parseJsonArray(users));
			group.setGroups(parseJsonArray(groups));
			setModifiedSignature(httpRequest, group);

			try{
				principalService.updateGroup(repositoryId, group);
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_UPDATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@DELETE
	@Path("/delete/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String delete(@PathParam("repositoryId") String repositoryId,
			@PathParam("id") String groupId){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing group
		Group group = principalService.getGroupById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Delete the group
		if(status){
			try{
				principalService.deleteGroup(repositoryId, group.getId());
			}catch(Exception ex){
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_DELETE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@PUT
	@Path("/{apiType: add|remove}/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String updateMembers(@PathParam("repositoryId") String repositoryId,
					  @PathParam("id") String groupId,
				      @PathParam("apiType") String apiType,
					  @FormParam(FORM_MEMBER_USERS) String users,
					  @FormParam(FORM_MEMBER_GROUPS) String groups,
					  @Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing Group
		Group group = principalService.getGroupById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Parse JSON string from input parameter
		JSONArray usersAry = new JSONArray();
		if(users != null){
			try{
				usersAry = (JSONArray)JSONValue.parseWithException(users);	//JSON parse validation
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_MEMBER_USERS, ErrorCode.ERR_PARSEJSON);
			}
		}

		JSONArray groupsAry = new JSONArray();
		if(groups != null){
			try{
				groupsAry = (JSONArray)JSONValue.parseWithException(groups); //JSON parse validation
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_MEMBER_GROUPS, ErrorCode.ERR_PARSEJSON);
			}
		}

		//Edit members info of the group
		if(status){
			//Group info
			setModifiedSignature(httpRequest, group);

			//Member(User) info
			List<String> usersList = editUserMembers(repositoryId, usersAry, errMsg, apiType, group);
			group.setUsers(usersList);

			//Member(Group) info
			List<String> groupsList = editGroupMembers(repositoryId, groupsAry, errMsg, apiType, group);
			group.setGroups(groupsList);

			//Update
			if(apiType.equals(API_ADD)){
				try{
					principalService.updateGroup(repositoryId, group);
				}catch(Exception ex){
					ex.printStackTrace();
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_UPDATEMEMBERS);
				}
			}else if(apiType.equals(API_REMOVE)){
				try{
					principalService.updateGroup(repositoryId, group);
				}catch(Exception ex){
					ex.printStackTrace();
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_UPDATEMEMBERS);
				}
			}
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 *if list is null, return true.
	 * @param errMsg
	 * @param id
	 * @param list
	 * @return
	 */
	private boolean isNewRecord(JSONArray errMsg, String id, List<String> list){
		boolean status = true;
		if(list != null){
			for (String s : list){
				if(id.equals(s)){
					status = false;
					break;
				}
			}
		}
		return status;
	}

	/**
	 * edit group's members(users)
	 * @param repositoryId TODO
	 * @param usersAry
	 * @param errMsg
	 * @param apiType
	 * @param group
	 * @return
	 */
	private List<String> editUserMembers(String repositoryId, JSONArray usersAry, JSONArray errMsg, String apiType, Group group){
		List<String> usersList = new ArrayList<String>();

		List<String> ul = group.getUsers();
		if(ul != null) usersList = ul;

		for(final Object obj : usersAry){
			boolean notSkip = true;
			JSONObject objJSON = (JSONObject)obj;
			String userId = (String)objJSON.get(FORM_ID);

			//check only when "add" API
			if(apiType.equals(API_ADD)){
				User existingUser = principalService.getUserById(repositoryId, userId);
				if(existingUser == null){
					notSkip = false;
					addErrMsg(errMsg, ITEM_USER + ":" + userId, ErrorCode.ERR_NOTFOUND);
				}
			}

			if(notSkip){
				//"add" method
				if(apiType.equals(API_ADD)){
					if(isNewRecord(errMsg, userId, usersList)){
						usersList.add(userId);
					}else{
						addErrMsg(errMsg, ITEM_USER + ":" + userId, ErrorCode.ERR_ALREADYMEMBER);
					}
				//"remove" method
				}else if(apiType.equals(API_REMOVE)){
					if(!isNewRecord(errMsg, userId, usersList)){
						usersList.remove(userId);
					}else{
						addErrMsg(errMsg, ITEM_USER + ":" + userId, ErrorCode.ERR_NOTMEMBER);
					}
				}
			}
		}
		return usersList;
	}


	/**
	 * edit group's members(groups)
	 * @param repositoryId TODO
	 * @param groupsAry
	 * @param errMsg
	 * @param apiType
	 * @param group
	 * @return
	 */
	private List<String>editGroupMembers(String repositoryId, JSONArray groupsAry, JSONArray errMsg, String apiType, Group group){	//check only when "add" API
		List<String>groupsList = new ArrayList<String>();

		List<String> gl = group.getGroups();
		if(gl != null) groupsList = gl;

		List<Group> allGroupsList = principalService.getGroups(repositoryId);
		List<String> allGroupsStringList = new ArrayList<String>();
		for(final Group g : allGroupsList){
			allGroupsStringList.add(g.getId());
		}

		for(final Object obj : groupsAry){
			JSONObject objJSON = (JSONObject)obj;
			String groupId = (String)objJSON.get(FORM_ID);
			boolean notSkip = true;

			//Existance check
			Group g = principalService.getGroupById(repositoryId, groupId);
			if(g == null && apiType.equals(API_ADD)){
				notSkip = false;
				addErrMsg(errMsg, ITEM_GROUP + ":" + groupId, ErrorCode.ERR_NOTFOUND);
			}

			if(notSkip){
				//"add" method
				if(apiType.equals(API_ADD)){
					if(isNewRecord(errMsg, groupId, groupsList)){
						if(groupId.equals(group.getId())){
							//skip and error when trying to add the group to itself
							addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_GROUPITSELF);
						}else{
							groupsList.add(groupId);
						}
					}else{
						//skip and message
						addErrMsg(errMsg, ITEM_GROUP + ":" + groupId, ErrorCode.ERR_ALREADYMEMBER);
					}
				//"remove" method
				}else if(apiType.equals(API_REMOVE)){
					if(!isNewRecord(errMsg, groupId, groupsList)){
						groupsList.remove(groupId);
					}else{
						//skip
						addErrMsg(errMsg, ITEM_GROUP + ":" + groupId, ErrorCode.ERR_NOTMEMBER);
					}
				}
			}
		}
		return groupsList;
	}

	boolean validateNewGroup(String repositoryId, boolean status, JSONArray errMsg, String groupId, String name){
		if(StringUtils.isBlank(groupId)){
			status = false;
			addErrMsg(errMsg, ITEM_GROUPID, ErrorCode.ERR_MANDATORY);
		}
		//groupID uniqueness
		Group group = principalService.getGroupById(repositoryId, groupId);
		if(group != null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUPID, ErrorCode.ERR_ALREADYEXISTS);
		}

		if(StringUtils.isBlank(name)){
			status = false;
			addErrMsg(errMsg, ITEM_GROUPNAME, ErrorCode.ERR_MANDATORY);
		}

		return status;
	}

	boolean validateGroup(boolean status, JSONArray errMsg, String groupId, String name){
		if(StringUtils.isBlank(name)){
			status = false;
			addErrMsg(errMsg, ITEM_GROUPNAME, ErrorCode.ERR_MANDATORY);
		}

		return status;
	}


	private JSONObject convertGroupToJson(Group group) {
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		String created = new String();
		try{
			created = sdf.format(group.getCreated().getTime());
		}catch(Exception ex){
			ex.printStackTrace();
		}
		String modified = new String();
		try{
			modified = sdf.format(group.getModified().getTime());
		}catch(Exception ex){
			ex.printStackTrace();
		}
		JSONObject groupJSON = new JSONObject();
		groupJSON.put(ITEM_GROUPID, group.getGroupId());
		groupJSON.put(ITEM_GROUPNAME, group.getName());
		groupJSON.put(ITEM_CREATOR, group.getCreator());
		groupJSON.put(ITEM_CREATED, created);
		groupJSON.put(ITEM_MODIFIER, group.getModifier());
		groupJSON.put(ITEM_MODIFIED, modified);
		groupJSON.put(ITEM_TYPE, group.getType());
		groupJSON.put(ITEM_MEMBER_USERS,  group.getUsers());
		groupJSON.put(ITEM_MEMBER_USERSSIZE,  group.getUsers().size());
		groupJSON.put(ITEM_MEMBER_GROUPS, group.getGroups());
		groupJSON.put(ITEM_MEMBER_GROUPSSIZE, group.getGroups().size());

		return groupJSON;
	}

	private JSONArray parseJsonArray(String str){
		JSONParser parser = new JSONParser();
		Object obj;
		try {
			obj = parser.parse(str);
			JSONArray result = (JSONArray)obj;
			return result;
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return new JSONArray();
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}
