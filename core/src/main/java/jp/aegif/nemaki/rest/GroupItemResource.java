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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.UserGroupSearchService;
import jp.aegif.nemaki.businesslogic.UserGroupSearchService.SearchResult;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.DateUtil;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


@Path("/repo/{repositoryId}/group")
public class GroupItemResource extends ResourceBase{

	private static final Log log = LogFactory.getLog(GroupItemResource.class);
	private ContentService contentService;
	private UserGroupSearchService userGroupSearchService;

	private ContentService getContentService() {
		if (contentService != null) {
			return contentService;
		}
		// Fallback to manual Spring context lookup
		return SpringContext.getApplicationContext()
				.getBean("ContentService", ContentService.class);
	}

	private ContentService getContentServiceSafe() {
		ContentService service = getContentService();
		if (service == null) {
			throw new IllegalStateException(
					"ContentService not available - dependency injection failed");
		}
		return service;
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/search")
	@Produces(MediaType.APPLICATION_JSON)
	public String search(@PathParam("repositoryId") String repositoryId, @QueryParam("query") String query,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		if (StringUtils.isBlank(query)) {
			status = false;
			addErrMsg(errMsg, "query", ErrorCode.ERR_MANDATORY);
			return makeResult(status, result, errMsg).toJSONString();
		}

		JSONArray queriedGroups = new JSONArray();

		if (userGroupSearchService != null && userGroupSearchService.isSolrGroupSearchEffective(repositoryId)) {
			SearchResult sr = userGroupSearchService.searchGroupsCaseSensitive(repositoryId, query, 50);
			if (!sr.hasError()) {
				List<String> solrIds = sr.getObjectIds();
				Map<String, Content> contents = getContentServiceSafe().getContentsByIds(repositoryId, solrIds);
				int addedCount = 0;
				for (String oid : solrIds) {
					Content c = contents.get(oid);
					if (c instanceof GroupItem) {
						queriedGroups.add(this.convertGroupToJson((GroupItem) c));
						addedCount++;
					}
				}
				if (addedCount < solrIds.size()) {
					log.warn("Solr→CouchDB hydrate gap for groups in repository " + repositoryId
							+ ": solrIds=" + solrIds.size() + ", returned=" + addedCount
							+ ", missing=" + (solrIds.size() - addedCount)
							+ " — falling back to CouchDB");
					queriedGroups.clear();
					searchGroupsCaseSensitiveCouchDB(query, repositoryId, queriedGroups);
				}
			} else {
				log.warn("Solr group search failed, falling back to CouchDB: " + sr.getErrorMessage());
				searchGroupsCaseSensitiveCouchDB(query, repositoryId, queriedGroups);
			}
		} else {
			searchGroupsCaseSensitiveCouchDB(query, repositoryId, queriedGroups);
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
	public String list(@PathParam("repositoryId") String repositoryId,
					   @QueryParam("offset") @DefaultValue("-1") int offset,
					   @QueryParam("limit") @DefaultValue("-1") int limit,
					   @QueryParam("query") @DefaultValue("") String query,
					   @Context HttpServletRequest httpRequest) {
		if (log.isDebugEnabled()) {
			log.debug("GroupItemResource.list() called for repository: " + repositoryId);
		}
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray listJSON = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		try {
			boolean paginated = offset >= 0 && limit > 0;
			boolean hasQuery = query != null && !query.trim().isEmpty();
			int totalCount;

			if (hasQuery) {
				if (userGroupSearchService != null && userGroupSearchService.isSolrGroupSearchEffective(repositoryId)) {
					SearchResult sr = userGroupSearchService.searchGroups(repositoryId, query.trim(),
							paginated ? offset : 0, paginated ? limit : 0);
					if (!sr.hasError()) {
						totalCount = (int) sr.getTotalCount();
						List<String> solrIds = sr.getObjectIds();
						Map<String, Content> contents = getContentServiceSafe().getContentsByIds(repositoryId, solrIds);
						int addedCount = 0;
						for (String oid : solrIds) {
							Content c = contents.get(oid);
							if (c instanceof GroupItem) {
								listJSON.add(convertGroupToJson((GroupItem) c));
								addedCount++;
							}
						}
						if (addedCount < solrIds.size()) {
							log.warn("Solr→CouchDB hydrate gap for groups in repository " + repositoryId
									+ ": solrIds=" + solrIds.size() + ", returned=" + addedCount
									+ ", missing=" + (solrIds.size() - addedCount)
									+ " — falling back to CouchDB");
							listJSON.clear();
							totalCount = searchGroupsCouchDB(query, repositoryId, paginated, offset, limit, listJSON);
						}
					} else {
						log.warn("Solr group search failed, falling back to CouchDB: " + sr.getErrorMessage());
						totalCount = searchGroupsCouchDB(query, repositoryId, paginated, offset, limit, listJSON);
					}
				} else {
					totalCount = searchGroupsCouchDB(query, repositoryId, paginated, offset, limit, listJSON);
				}
			} else if (paginated) {
				totalCount = getContentService().getGroupItemCount(repositoryId);
				List<GroupItem> groupList = getContentService().getGroupItems(repositoryId, offset, limit);
				for (GroupItem group : groupList) {
					listJSON.add(convertGroupToJson(group));
				}
			} else {
				List<GroupItem> groupList = getContentService().getGroupItems(repositoryId);
				totalCount = groupList.size();
				for (GroupItem group : groupList) {
					listJSON.add(convertGroupToJson(group));
				}
			}

			result.put(ITEM_ALLGROUPS, listJSON);
			result.put("totalCount", totalCount);
			if (paginated) {
				result.put("offset", offset);
				result.put("limit", limit);
			}
		} catch (Exception ex) {
			log.error("Exception in GroupItemResource.list(): " + ex.getMessage(), ex);
			addErrMsg(errMsg, ITEM_ALLGROUPS, ErrorCode.ERR_LIST);
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("id") String groupId,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
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
		String csrfError = validateCsrfProtection(httpRequest);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toJSONString();
		}

		// Admin check
		status = checkAdmin(errMsg, httpRequest);

		//Validation
		if (status) {
			status = validateNewGroup(repositoryId, status, errMsg, groupId, name);
		}

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

				getContentService().createGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
			}catch(CmisObjectNotFoundException ex){
				log.warn("Object not found while creating group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
			}catch(CmisPermissionDeniedException ex){
				log.warn("Permission denied while creating group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_PERMISSIONDENIED);
			}catch(Exception ex){
				log.error("Failed to create group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_CREATE);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toString();
	}

	//TODO this is a copy & paste method.
	private Folder getOrCreateSystemSubFolder(String repositoryId, String name){
	if (log.isDebugEnabled()) {
		log.debug("getOrCreateSystemSubFolder called with repositoryId='" + repositoryId + "', name='" + name + "'");
		log.debug("About to call getContentService().getSystemFolder(repositoryId)");
	}
	
	Folder systemFolder = getContentService().getSystemFolder(repositoryId);
	
	if (log.isDebugEnabled()) {
		log.debug("getContentService().getSystemFolder returned: " + (systemFolder != null ? "NOT NULL (ID=" + systemFolder.getId() + ")" : "NULL"));
	}

	// Fallback: search for .system folder by path
	if (systemFolder == null) {
		log.warn("systemFolder is null - .system folder not found via PropertyManager, searching by path");
		try {
			Content content = getContentService().getContentByPath(repositoryId, "/.system");
			if (content instanceof Folder) {
				systemFolder = (Folder) content;
				log.info("Found .system folder via path fallback: ID=" + systemFolder.getId());
			}
		} catch (Exception e) {
			log.error("Failed to find .system folder via path fallback", e);
		}

		if (systemFolder == null) {
			throw new RuntimeException(".system folder not accessible - check system folder configuration and security settings");
		}
	}

	// check existing folder
	List<Content> children = getContentService().getChildren(repositoryId, systemFolder.getId());
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
	Folder _target = getContentService().createFolder(new SystemCallContext(repositoryId), repositoryId, properties, systemFolder, null, null, null, null);
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
		String csrfError = validateCsrfProtection(httpRequest);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toJSONString();
		}

		// Admin check
		status = checkAdmin(errMsg, httpRequest);

		//Existing group
		GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
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
				getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
			}catch(CmisObjectNotFoundException ex){
				log.warn("Object not found while updating group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
			}catch(CmisPermissionDeniedException ex){
				log.warn("Permission denied while updating group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_PERMISSIONDENIED);
			}catch(Exception ex){
				log.error("Failed to update group: " + groupId, ex);
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
			@PathParam("id") String groupId,
			@Context HttpServletRequest httpRequest){

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		String csrfError = validateCsrfProtection(httpRequest);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toJSONString();
		}

		// Admin check
		status = checkAdmin(errMsg, httpRequest);

		//Existing group
		GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
		if(group == null){
			status = false;
			addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
		}

		//Delete the group
		if(status){
			try{
				getContentService().delete(new SystemCallContext(repositoryId), repositoryId, group.getId(), false);
			}catch(CmisObjectNotFoundException ex){
				log.warn("Object not found while deleting group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
			}catch(CmisPermissionDeniedException ex){
				log.warn("Permission denied while deleting group: " + groupId, ex);
				status = false;
				addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_PERMISSIONDENIED);
			}catch(Exception ex){
				log.error("Failed to delete group: " + groupId, ex);
				status = false;
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
		String csrfError = validateCsrfProtection(httpRequest);
		if (csrfError != null) {
			addErrMsg(errMsg, "csrf", csrfError);
			return makeResult(false, result, errMsg).toJSONString();
		}

		// Admin check
		status = checkAdmin(errMsg, httpRequest);

		//Existing Group
		GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
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
					getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
				}catch(CmisObjectNotFoundException ex){
					log.warn("Object not found while adding members to group: " + groupId, ex);
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
				}catch(CmisPermissionDeniedException ex){
					log.warn("Permission denied while adding members to group: " + groupId, ex);
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_PERMISSIONDENIED);
				}catch(Exception ex){
					log.error("Failed to add members to group: " + groupId, ex);
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_UPDATEMEMBERS);
				}
			}else if(apiType.equals(API_REMOVE)){
				try{
					getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
				}catch(CmisObjectNotFoundException ex){
					log.warn("Object not found while removing members from group: " + groupId, ex);
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_NOTFOUND);
				}catch(CmisPermissionDeniedException ex){
					log.warn("Permission denied while removing members from group: " + groupId, ex);
					status = false;
					addErrMsg(errMsg, ITEM_GROUP, ErrorCode.ERR_PERMISSIONDENIED);
				}catch(Exception ex){
					log.error("Failed to remove members from group: " + groupId, ex);
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

		// Pre-fetch all user IDs once for existence validation (avoid N+1 queries)
		java.util.Set<String> allUserIds = null;
		if(apiType.equals(API_ADD)){
			List<jp.aegif.nemaki.model.UserItem> allUsers = getContentServiceSafe().getUserItems(repositoryId);
			allUserIds = new java.util.HashSet<>();
			for(jp.aegif.nemaki.model.UserItem u : allUsers){
				allUserIds.add(u.getUserId());
			}
		}

		for (int i = 0; i < targetUserIds.size(); i++) {
			String userId = targetUserIds.get(i).toString();
			boolean notSkip = true;

			//check only when "add" API using pre-fetched set
			if(apiType.equals(API_ADD)){
				if(!allUserIds.contains(userId)){
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

		// Fetch all groups once and build lookup set for existence checks
		List<GroupItem> allGroupsList = getContentService().getGroupItems(repositoryId);
		java.util.Set<String> allGroupIds = new java.util.HashSet<>();
		for(final GroupItem g : allGroupsList){
			allGroupIds.add(g.getGroupId());
		}

		for (int i = 0; i < targetGroupIds.size(); i++) {
			String groupId = targetGroupIds.get(i).toString();
			boolean notSkip = true;

			// Existence check using pre-fetched set (no individual DB query)
			if(apiType.equals(API_ADD) && !allGroupIds.contains(groupId)){
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
		GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
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



	/**
	 * CouchDB fallback for case-insensitive group search (list endpoint).
	 */
	@SuppressWarnings("unchecked")
	private int searchGroupsCouchDB(String query, String repositoryId, boolean paginated,
			int offset, int limit, JSONArray listJSON) {
		String queryLower = query.trim().toLowerCase();
		List<GroupItem> allGroups = getContentServiceSafe().getGroupItems(repositoryId);
		List<GroupItem> filtered = new java.util.ArrayList<>();
		for (GroupItem group : allGroups) {
			if (matchesGroupQuery(group, queryLower)) {
				filtered.add(group);
			}
		}
		int totalCount = filtered.size();
		int start = paginated ? Math.min(offset, totalCount) : 0;
		int end = paginated ? Math.min(start + limit, totalCount) : totalCount;
		for (int i = start; i < end; i++) {
			listJSON.add(convertGroupToJson(filtered.get(i)));
		}
		return totalCount;
	}

	/**
	 * CouchDB fallback for case-sensitive group search (search endpoint).
	 */
	@SuppressWarnings("unchecked")
	private void searchGroupsCaseSensitiveCouchDB(String query, String repositoryId, JSONArray queriedGroups) {
		List<GroupItem> groups = ObjectUtils.defaultIfNull(
				getContentServiceSafe().getGroupItems(repositoryId), Collections.emptyList());
		for (GroupItem g : groups) {
			String groupId = g.getGroupId();
			String groupName = g.getName();
			boolean matches = (StringUtils.isNotEmpty(groupId) && groupId.contains(query)) ||
			                  (StringUtils.isNotEmpty(groupName) && groupName.contains(query));
			if (matches) {
				if (queriedGroups.size() < 50) {
					queriedGroups.add(this.convertGroupToJson(g));
				} else {
					break;
				}
			}
		}
	}

	private boolean matchesGroupQuery(GroupItem group, String queryLower) {
		if (group.getGroupId() != null && group.getGroupId().toLowerCase().contains(queryLower)) return true;
		if (group.getName() != null && group.getName().toLowerCase().contains(queryLower)) return true;
		return false;
	}

	private JSONObject convertGroupToJson(GroupItem group) {
		String created = new String();
		try{
			created = DateUtil.formatSystemDateTime(group.getCreated());
		}catch(Exception ex){
			log.warn("Failed to format created date for group " + group.getGroupId(), ex);
		}
		String modified = new String();
		try{
			modified = DateUtil.formatSystemDateTime(group.getModified());
		}catch(Exception ex){
			log.warn("Failed to format modified date for group " + group.getGroupId(), ex);
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
			log.warn("Failed to parse JSON array: " + e.getMessage());
		}

		return new JSONArray();
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setUserGroupSearchService(UserGroupSearchService userGroupSearchService) {
		this.userGroupSearchService = userGroupSearchService;
	}

}
