package controllers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

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
import util.ErrorMessage;
import util.Util;
import util.authentication.NemakiProfile;
import views.html.user.blank;
import views.html.user.index;
import views.html.user.property;
import views.html.user.favorites;
import views.html.user.password;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.pac4j.play.java.Secure;

public class User extends Controller {

	private static String coreRestUri = Util.buildNemakiCoreUri() + "rest/";

	private static Session getCmisSession(String repositoryId){
		return CmisSessions.getCmisSession(repositoryId, ctx());
	}

	@Secure
	public Result index(String repositoryId){
	    	return search(repositoryId, "");
	  }

	@Secure
	public Result search(String repositoryId, String term){

		try {
			term = URLEncoder.encode(term,"UTF-8");
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		NemakiProfile profile = Util.getProfile(ctx());
    	JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "search?query=" + term);

    	//TODO check status
    	JsonNode users = result.get("result");

    	List<model.User> list = new ArrayList<model.User>();

    	if(users == null){
    		users = Json.parse("[]");
    	}else{
    		Iterator<JsonNode>itr = users.elements();
        	while(itr.hasNext()){
        		JsonNode node = itr.next();
        		model.User user = new model.User(node);
        		list.add(user);
        	}
    	}

    	//render
    	if(Util.dataTypeIsHtml(request().acceptedTypes())){
    		return ok(index.render(repositoryId, list, profile));
    	}else{
    		return ok(users);

    	}

    }

	@Secure
	public Result showBlank(String repositoryId){
		model.User emptyUser = new model.User("", "", "", "", "", "", false, null);

		return ok(blank.render(repositoryId, emptyUser));
	}

	@Secure
	public Result showDetail(String repositoryId, String id){
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);

			return ok(property.render(repositoryId, user));
		}else{
			//TODO
			return internalServerError();
		}
	}



	@Secure
	public Result showPasswordChanger(String repositoryId, String id){
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);

			return ok(password.render(repositoryId, user));
		}else{
			//TODO
			return internalServerError();
		}
	}

	@Secure
	public Result showFavorites(String repositoryId, String id){
		NemakiProfile profile = Util.getProfile(ctx());
		JsonNode result = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "show/" + id);

		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);

			List<CmisObject> list = new ArrayList<CmisObject>();
			Set<String>fs = user.favorites;
			Session session = null;
			if(CollectionUtils.isNotEmpty(fs)){
				//CMIS session
				session =  getCmisSession(repositoryId);

				Iterator<String>fsItr = fs.iterator();

				while(fsItr.hasNext()){
					String favId = fsItr.next();
					list.add(session.getObject(favId));
				}
			}
			return ok(favorites.render(repositoryId, user, list, session, profile,0, (long)fs.size()));
		}else{
			return internalServerError();
		}
	}

	@Secure
	public Result toggleFavorite(String repositoryId, String userId, String objectId){
		Map<String, String>params = new HashMap<String, String>();
		params.put("id", userId);

		JsonNode getResult = Util.getJsonResponse(ctx(), getEndpoint(repositoryId) + "show/" + userId);

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
			JsonNode putResult = Util.putJsonResponse(ctx(), getEndpoint(repositoryId) + "update/" + userId , params);
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

	@Secure
	public Result create(String repositoryId){
    	Map<String, String>params = buildParams();
    	JsonNode result = Util.postJsonResponse(ctx(), getEndpoint(repositoryId) + "create/" + params.get("id"), params);

    	if(isSuccess(result)){
    		flash("flash message");
    		return redirect(routes.User.index(repositoryId));
    	}else{
    		String errorMsg = result.get("error").get("userId").asText();
    		return internalServerError(errorMsg);
    	}
	}

	@Secure
	public Result update(String repositoryId, String id){

    	Map<String, String>params = buildParams();

    	JsonNode result = Util.putJsonResponse(ctx(), getEndpoint(repositoryId) + "update/" + id , params);

    	if(isSuccess(result)){
    		return ok();
    	}else{
    		return internalServerError();
    	}
	}

	@Secure
	public Result changePassword(String repositoryId, String id){
		DynamicForm input = Form.form();
    	input = input.bindFromRequest();
    	Map<String, String>changeParams = new HashMap<String, String>();
    	changeParams.put("id", input.get("userId"));
    	changeParams.put("oldPassword",  input.get("oldPassword"));
    	changeParams.put("newPassword",  input.get("newPassword1"));
    	JsonNode changeResult = Util.putJsonResponse(ctx(), getEndpoint(repositoryId) + "changePassword/" + id , changeParams);

    	if(isSuccess(changeResult)){
    		return redirect(routes.Application.logout(repositoryId, "Password Changed."));
    	}else{
    		String errorCode = changeResult.get("error").get(0).get("user").asText();
    		return internalServerError(ErrorMessage.getMessage(errorCode));
    	}
	}

	@Secure
	public Result delete(String repositoryId, String id){
		JsonNode deleteResult = Util.deleteJsonResponse(ctx(), getEndpoint(repositoryId) + "delete/" + id);

    	if(isSuccess(deleteResult)){
    		return ok();
    	}else{
    		String error = deleteResult.get("error").get(0).get("user").asText();
    		return internalServerError(error);
    	}
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
