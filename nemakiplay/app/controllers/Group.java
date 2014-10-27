package controllers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import model.Principal;

import org.apache.commons.collections.CollectionUtils;

import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;
import views.html.group.index;

import com.fasterxml.jackson.databind.JsonNode;

@Authenticated(Secured.class)
public class Group extends Controller {

	public static Result index(){
		List<model.Group>emptyList = new ArrayList<model.Group>();
	    return ok(views.html.group.index.render(emptyList));
	}

	public static Result search(String term){
    	JsonNode result = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/group/search?query=" + term);

    	List<model.Group> list = new ArrayList<>();
    	
    	//TODO check status
    	JsonNode groups = result.get("result");
    	if(groups == null){
    		groups = Json.parse("[]");
    	}else{
    		Iterator<JsonNode>itr = groups.elements();
        	while(itr.hasNext()){
        		JsonNode node = itr.next();
        		
        		model.Group group = new model.Group();
        		group.id = node.get("groupId").asText();
        		group.name = node.get("groupName").asText();
        		//member(user)
        		group.usersSize = node.get("usersSize").asInt();
        		if(!node.get("users").isNull()){
        			List<String> memberUsers = new ArrayList<>();
            		for(final JsonNode userId : node.get("users")){
            			memberUsers.add(userId.asText());
            		}
            		group.users = memberUsers;
        		}
        		//member(group)
        		group.groupsSize = node.get("groupsSize").asInt();
        		if(!node.get("groups").isNull()){
        			List<String> memberGroups = new ArrayList<>();
            		for(final JsonNode groupId : node.get("groups")){
            			memberGroups.add(groupId.asText());
            		}
            		group.groups = memberGroups;
        		}
        		    		
        		list.add(group);
        				
        	}
    	}
    	

    	//render
    	if(Util.dataTypeIsHtml(request().acceptedTypes())){
    		return ok(index.render(list));
    	}else{
    		return ok(groups);
    		
    	}
    	
    }

	public static Result newEdit(){
		
		return ok(views.html.group.newEdit.render());
	}

	public static Result create(){
    	Map<String, String>params = buildParams();
    	JsonNode result = Util.postJsonResponse("http://localhost:8080/nemakiware/rest/group/create/" + params.get("id"), params);

    	if(isSuccess(result)){
    		flash("flash message");

    		
    		return redirect(routes.Group.index());
    	}else{
    		//TODO error
    		return ok();
    	}
	}

	public static Result delete(String id){
		String url = "http://localhost:8080/nemakiware/rest/group/delete/" + id;
		JsonNode result = Util.deleteJsonResponse(url);
		
		//TODO error
		return redirect(routes.User.index());
	}

	public static Result show(String id){
		JsonNode result = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/user/show/" + id);
		
		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);
			return ok(views.html.user.show.render(user));
		}else{
			//TODO
			return ok();
		}
	}
	
	public static Result edit(String id){
		JsonNode result = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/group/show/" + id);
		
		if(isSuccess(result)){
			JsonNode _group = result.get("group");
			model.Group group = new model.Group(_group);
			
			
			//List of (userId,userName)
			List<Principal> users = new ArrayList<>();
			List<String> userIds = group.users;
			if(CollectionUtils.isNotEmpty(userIds)){
				for(String userId : userIds){
					JsonNode _memberUser = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/user/show/" + userId);
					if(isSuccess(_memberUser)){
						String userName = _memberUser.get("user").get("userName").asText();
						users.add(new Principal("user", userId, userName));
					}
				}
			}
			
			//List of (groupId,groupName)
			List<Principal> groups = new ArrayList<>();
			List<String> groupIds = group.groups;
			if(CollectionUtils.isNotEmpty(groupIds)){
				for(String groupId : groupIds){
					JsonNode _memberGroup = Util.getJsonResponse("http://localhost:8080/nemakiware/rest/group/show/" + groupId);
					if(isSuccess(_memberGroup)){
						String groupName = _memberGroup.get("group").get("groupName").asText();
						groups.add(new Principal("group", groupId, groupName));
					}
				}
			}
			
			List<Principal>principals = new ArrayList<>();
			principals.addAll(users);
			principals.addAll(groups);
			
			return ok(views.html.group.edit.render(group, principals));
		}else{
			//TODO
			return ok();
		}
	}
	
	public static Result update(String id){
    	
    	Map<String, String>params = buildParams();
    	
    	
    	JsonNode result = Util.putJsonResponse("http://localhost:8080/nemakiware/rest/group/update/" + id , params);
    	
    	if(isSuccess(result)){
    		return redirect(routes.Group.index());
    	}else{
    		//TODO
    		return ok();
    	}
    	
    	
	}
	
	private static boolean isSuccess(JsonNode result){
		return "success".equals(result.get("status").asText()) ||
				("failure".equals(result.get("status").asText()) && "notFound".equals(result.get("error").get("group")));
	}
	
	private static Map<String, String> buildParams(){
		DynamicForm input = Form.form();
    	input = input.bindFromRequest();
    	//Extract form parameters
    	String groupId = input.get("groupId");
    	String groupName = input.get("groupName");
    	String users = input.get("users");
    	String groups = input.get("groups");
    	
    	Map<String, String>params = new HashMap<>();
    	params.put("id", groupId);
    	params.put("name", groupName);
    	params.put("users", users);
    	params.put("groups", groups);
    	
    	return params;
	}
}
