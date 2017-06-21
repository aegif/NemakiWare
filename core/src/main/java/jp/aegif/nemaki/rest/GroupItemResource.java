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

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.SystemConst;


@Path("/repo/{repositoryId}/group")
public class GroupItemResource extends ResourceBase{

	private ContentService contentService;

	@SuppressWarnings("unchecked")
	@GET
	@Path("/search")
	@Produces(MediaType.APPLICATION_JSON)
	public String search(@PathParam("repositoryId") String repositoryId, @QueryParam("query") String query){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		List<GroupItem> groups = contentService.getGroupItems(repositoryId);
		if(groups == null) groups = new ArrayList<>();
		JSONArray queriedGroups = new JSONArray();

		for(GroupItem g : groups) {
			if ( g.getGroupId().contains(query)|| g.getName().contains(query)) {
				if(queriedGroups.size()<50){
					queriedGroups.add(this.convertGroupToJson(g));
				}else{
					break;
				}
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

		List<GroupItem> groupList;
		try{
			groupList = contentService.getGroupItems(repositoryId);
			for(GroupItem group : groupList){
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

		GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
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
						 @FormParam(FORM_MEMBER_USERS) String users,
						 @FormParam(FORM_MEMBER_GROUPS) String groups,
						 @Context HttpServletRequest httpRequest
						 ){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Validation
		status = validateNewGroup(repositoryId, status, errMsg, groupId, name);

		//Create a group
		if(status){
			try{
				//Edit group info
				JSONArray _users = StringUtils.isBlank(users) ? new JSONArray() : parseJsonArray(users);
				JSONArray _groups = StringUtils.isBlank(groups) ? new JSONArray() :  parseJsonArray(groups);

				GroupItem group = new GroupItem(null, NemakiObjectType.nemakiGroup, groupId,  name, _users, _groups);
				final Folder groupsFolder = getOrCreateSystemSubFolder(repositoryId, "groups");
				group.setParentId(groupsFolder.getId());
				setFirstSignature(httpRequest, group);

				contentService.createGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
			}catch(Exception ex){
				ex.printStackTrace();
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_CREATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
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
	@Path("/update/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public String update(@PathParam("repositoryId") String repositoryId,
						 @PathParam("id") String groupId,
						 @FormParam(FORM_GROUPNAME) String name,
						 @FormParam(FORM_MEMBER_USERS) String users,
						 @FormParam(FORM_MEMBER_GROUPS) String groups,
						 @Context HttpServletRequest httpRequest){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		//Existing group
		GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
		if (group == null) {
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Validation
		status = validateGroup(status, errMsg, groupId, name);

		//Edit & Update
		if(status){
			//Edit group info
			//if a parameter is not input, it won't be modified.
			group.setGroupId(groupId);
			group.setName(name);
			group.setUsers(users == null ? new JSONArray() :  parseJsonArray(users));
			group.setGroups(groups  == null ? new JSONArray() : parseJsonArray(groups));
			setModifiedSignature(httpRequest, group);

			try{
				contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
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
		GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Delete the group
		if(status){
			try{
				contentService.delete(new SystemCallContext(repositoryId), repositoryId, group.getId(), false);
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
		GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Edit members info of the group
		if(status){
			JSONArray usersAry = users == null ? new JSONArray() :  parseJsonArray(users);
			JSONArray groupsAry = groups == null ? new JSONArray() :  parseJsonArray(groups);

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
					contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
				}catch(Exception ex){
					ex.printStackTrace();
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_UPDATEMEMBERS);
				}
			}else if(apiType.equals(API_REMOVE)){
				try{
					contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
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
	 * @param targetUserIds
	 * @param errMsg
	 * @param apiType
	 * @param group
	 * @return
	 */
	private List<String> editUserMembers(String repositoryId, JSONArray targetUserIds, JSONArray errMsg, String apiType, GroupItem group){
		List<String> usersList = new ArrayList<String>();
		List<String> ul = group.getUsers();
		if(ul != null) usersList = ul;

		for (int i = 0; i < targetUserIds.size(); i++) {
			String userId = targetUserIds.get(i).toString();
			boolean notSkip = true;

			//check only when "add" API
			if(apiType.equals(API_ADD)){
				UserItem existingUser = contentService.getUserItemById(repositoryId, userId);
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
	 * @param targetGroupIds
	 * @param errMsg
	 * @param apiType
	 * @param group
	 * @return
	 */
	private List<String>editGroupMembers(String repositoryId, JSONArray targetGroupIds, JSONArray errMsg, String apiType, GroupItem group){
		//check only when "add" API
		List<String>groupsList = new ArrayList<String>();

		List<String> gl = group.getGroups();
		if(gl != null) groupsList = gl;

		List<GroupItem> allGroupsList = contentService.getGroupItems(repositoryId);
		List<String> allGroupsStringList = new ArrayList<String>();
		for(final GroupItem g : allGroupsList){
			allGroupsStringList.add(g.getId());
		}

		for (int i = 0; i < targetGroupIds.size(); i++) {
			String groupId = targetGroupIds.get(i).toString();
			boolean notSkip = true;

			//Existance check
			GroupItem g = contentService.getGroupItemById(repositoryId, groupId);
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
		GroupItem group = contentService.getGroupItemById(repositoryId, groupId);
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

		if(StringUtils.isBlank(groupId)){
			status = false;
			addErrMsg(errMsg, ITEM_GROUPID, ErrorCode.ERR_MANDATORY);
		}

		return status;
	}


	private JSONObject convertGroupToJson(GroupItem group) {
		SimpleDateFormat sdf = new SimpleDateFormat(SystemConst.DATETIME_FORMAT);
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

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
}
