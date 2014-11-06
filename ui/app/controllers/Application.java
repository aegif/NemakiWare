package controllers;

import org.apache.chemistry.opencmis.client.api.Session;

import com.fasterxml.jackson.databind.JsonNode;

import constant.Token;
import model.Login;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.login;

public class Application extends Controller{
	public static Result login() {
	    return ok(login.render(new Form<Login>(Login.class)));
	}
	
	public static Result authenticate(){
		Form<Login> input = Form.form(Login.class);
		input = input.bindFromRequest();
		String id = input.data().get("id");
		String password = input.data().get("password");
		
		if(validate(id, password)){
			session().clear();
			session(Token.LOGIN_USER_ID, id);
			session(Token.LOGIN_USER_PASSWORD, password);
			session(Token.LOGIN_USER_IS_ADMIN, String.valueOf(isAdmin(id)));
			return redirect(routes.Node.index());
		}else{
			return badRequest(login.render(input));
		}
	}
	
	private static boolean validate(String id, String password){
		try{
			Session cmisSession = Util.createCmisSession(id, password);
		}catch(Exception e){
			e.printStackTrace();
			return false;
		}
		
		return true;
	}
	
	private static boolean isAdmin(String id){
		boolean isAdmin = false;
		
		String coreRestUri = Util.buildNemakiCoreUri() + "rest/";
		String endPoint = coreRestUri + "user/";
		
		JsonNode result = Util.getJsonResponse(session(), endPoint + "show/" + id);
		
		if("success".equals(result.get("status").asText())){
			JsonNode _user = result.get("user");
			model.User user = new model.User(_user);
			
			isAdmin = user.isAdmin;
		}else{
			//TODO logging
			System.out.println("This user is not returned in REST API:" + id);
		}
		
		return isAdmin;
	}

	public static Result logout(){
		session().remove("loginUserId");
		return redirect(routes.Application.login());
	}
	
}
