package controllers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import play.data.DynamicForm;
import play.data.Form;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security.Authenticated;
import util.Util;
import views.html.user.blank;
import views.html.user.index;
import views.html.user.property;
import views.html.user.favorites;
import views.html.user.password;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;


@Authenticated(Secured.class)
public class User extends Controller {

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	private static Session getCmisSession(String repositoryId){
		return CmisSessions.getCmisSession(repositoryId, session());
	}

	public static Result index(String repositoryId){
		List<model.User>emptyList = new ArrayList<model.User>();

	    	return ok(index.render(repositoryId, emptyList));
	    }

	public static Result search(String repositoryId, String term){
    	JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "search?query=" + term);

    	//TODO check status
    	JsonNode users = result.get("result");

    	List<model.User> list = new ArrayList<model.User>();

    	if(users == null){
    		users = Json.parse("[]");
    	}else{
    		Iterator<JsonNode>itr = users.elements();
        	while(itr.hasNext()){
        		JsonNode node = itr.next();

        		model.User user = new model.User();
        		user.id = node.get("userId").asText();
        		user.name = node.get("userName").asText();
        		user.firstName = node.get("firstName").asText();
        		user.lastName = node.get("lastName").asText();
        		user.email = node.get("email").asText();

        		list.add(user);
        	}
    	}


    	//render
    	if(Util.dataTypeIsHtml(request().acceptedTypes())){
    		return ok(index.render(repositoryId, list));
    	}else{
    		return ok(users);

    	}

    }

	public static Result showBlank(String repositoryId){
		model.User emptyUser = new model.User("", "", "", "", "", "", false, null);

		return ok(blank.render(repositoryId, emptyUser));
	}

	public static Result showDetail(String repositoryId, String id){
		JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);
			return ok(property.render(repositoryId, user));
		}else{
			//TODO
			return ok();
		}
	}

	public static Result showPasswordChanger(String repositoryId, String id){
		JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);
			return ok(password.render(repositoryId, user));
		}else{
			//TODO
			return ok();
		}
	}

	public static Result showFavorites(String repositoryId, String id){
		JsonNode result = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);

			List<CmisObject> list = new ArrayList<CmisObject>();
			Set<String>fs = user.favorites;
			if(CollectionUtils.isNotEmpty(fs)){
				//CMIS session
				Session session =  getCmisSession(repositoryId);

				Iterator<String>fsItr = fs.iterator();
				while(fsItr.hasNext()){
					String favId = fsItr.next();
					list.add(session.getObject(favId));
				}
			}
			return ok(favorites.render(repositoryId, user, list));
		}else{
			//TODO
			return ok();
		}
	}

	public static Result toggleFavorite(String repositoryId, String userId, String objectId){
		Map<String, String>params = new HashMap<String, String>();
		params.put("id", userId);

		JsonNode getResult = Util.getJsonResponse(session(), getEndpoint(repositoryId) + "show/" + userId);

		if("success".equals(getResult.get("status").asText())){
			JsonNode _user = getResult.get("user");
			model.User user = new model.User(_user);

			ArrayNode fs = new ArrayNode(new JsonNodeFactory(true));
			fs.add(objectId);
			if(user.favorites == null || !user.favorites.contains(objectId)){
				params.put("addFavorites", fs.toString());
			}else{
				params.put("removeFavorites", fs.toString());
			}

			//Update
			JsonNode putResult = Util.putJsonResponse(session(), getEndpoint(repositoryId) + "update/" + userId , params);
			if("success".equals(putResult.get("status").asText())){
				return ok();
			}else{
				//TODO
				return internalServerError("User updating failure");
			}
		}else{
			//TODO
			return internalServerError("User retrieving failure");
		}
	}

	public static Result create(String repositoryId){
    	Map<String, String>params = buildParams();
    	JsonNode result = Util.postJsonResponse(session(), getEndpoint(repositoryId) + "create/" + params.get("id"), params);

    	if(isSuccess(result)){
    		flash("flash message");
    		return redirect(routes.User.index(repositoryId));
    	}else{
    		//TODO error
    		return ok();
    	}
	}

	public static Result update(String repositoryId, String id){

    	Map<String, String>params = buildParams();

    	JsonNode result = Util.putJsonResponse(session(), getEndpoint(repositoryId) + "update/" + id , params);

    	if(isSuccess(result)){
    		return ok();
    	}else{
    		return internalServerError();
    	}
	}

	public static Result changePassword(String repositoryId, String id){
		DynamicForm input = Form.form();
    	input = input.bindFromRequest();
    	Map<String, String>changeParams = new HashMap<String, String>();
    	changeParams.put("id", input.get("userId"));
    	changeParams.put("oldPassword",  input.get("oldPassword"));
    	changeParams.put("newPassword",  input.get("newPassword1"));
    	JsonNode changeResult = Util.putJsonResponse(session(), getEndpoint(repositoryId) + "changePassword/" + id , changeParams);

    	if(isSuccess(changeResult)){
    		return redirect(routes.Application.logout(repositoryId));
    	}else{
    		return internalServerError();
    	}
	}

	public static Result delete(String repositoryId, String id){
		JsonNode result = Util.deleteJsonResponse(session(), getEndpoint(repositoryId) + "delete/" + id);

		return ok();
	}

	private static boolean isSuccess(JsonNode result){
		return "success".equals(result.get("status").asText());
	}

	private static Map<String, String> buildParams(){
		DynamicForm input = Form.form();
    	input = input.bindFromRequest();
    	//Extract form parameters
    	String userId = input.get("userId");
    	String userName = input.get("userName");
    	String firstName = input.get("firstName");
    	String lastName = input.get("lastName");
    	String email = input.get("email");
    	String password = input.get("password");

    	Map<String, String>params = new HashMap<String, String>();
    	params.put("id", userId);
    	params.put("name", userName);
    	params.put("firstName", firstName);
    	params.put("lastName", lastName);
    	params.put("email", email);
    	if(StringUtils.isNotBlank(password)){
    		params.put("password", password);
    	}

    	return params;
	}



	private static String getEndpoint(String repositoryId){
		return coreRestUri + "repo/" + repositoryId + "/user/";
	}
}
