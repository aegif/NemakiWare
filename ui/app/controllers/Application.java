package controllers;

import org.apache.chemistry.opencmis.client.api.Session;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import model.Login;
import play.Routes;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.login;

public class Application extends Controller{
	public static Result login(String repositoryId) {
	    return ok(login.render(repositoryId, new Form<Login>(Login.class)));
	}
	
	public static Result authenticate(String repositoryId){
		Form<Login> input = Form.form(Login.class);
		input = input.bindFromRequest();
		String id = input.data().get("id");
		String password = input.data().get("password");
		
		if(validate(repositoryId, id, password)){
			session().clear();
			session(Token.LOGIN_USER_ID, id);
			session(Token.LOGIN_USER_PASSWORD, password);
			session(Token.LOGIN_USER_IS_ADMIN, String.valueOf(isAdmin(repositoryId, id)));
			session("repositoryId", repositoryId);
			return redirect(routes.Node.index(repositoryId));
		}else{
			return badRequest(login.render(repositoryId, input));
		}
	}
	
	private static boolean validate(String repositoryId, String id, String password){
		try{
			Session cmisSession = Util.createCmisSession(repositoryId, id, password);
		}catch(Exception e){
			e.printStackTrace();
			return false;
		}
		
		return true;
	}
	
	private static boolean isAdmin(String repositoryId, String id){
		boolean isAdmin = false;
		
		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		String endPoint = coreRestUri + "repo/" + repositoryId + "/user/";
		
		try{
			JsonNode result = Util.getJsonResponse(session(), endPoint + "show/" + id);
			if("success".equals(result.get("status").asText())){
				JsonNode _user = result.get("user");
				model.User user = new model.User(_user);
				
				isAdmin = user.isAdmin;
			}
		}catch(Exception e){
			//TODO logging
			System.out.println("This user is not returned in REST API:" + id);
		}
		
		return isAdmin;
	}

	public static Result logout(String repositoryId){
		//CMIS session
		CmisSessions.disconnect(repositoryId, session());
		
		//Play session
		session().remove("loginUserId");
		
		return redirect(routes.Application.login(repositoryId));
	}
	
	public static Result jsRoutes() {
		response().setContentType("text/javascript");
		return ok(
			Routes.javascriptRouter("jsRoutes", 
				controllers.routes.javascript.Node.showDetail(),
				controllers.routes.javascript.Node.getAce(),
				controllers.routes.javascript.Node.update(),
				controllers.routes.javascript.Node.delete(),
				
				controllers.routes.javascript.Type.showBlank(),
				controllers.routes.javascript.Type.edit(),
				controllers.routes.javascript.Type.delete(),
				
				controllers.routes.javascript.User.showDetail(),
				controllers.routes.javascript.User.delete(),
				
				controllers.routes.javascript.Group.showDetail(),
				controllers.routes.javascript.Group.delete(),
				
				controllers.routes.javascript.SearchEngine.index(),
				controllers.routes.javascript.SearchEngine.init(),
				controllers.routes.javascript.SearchEngine.reindex()
			)
		);
	}
	
}
