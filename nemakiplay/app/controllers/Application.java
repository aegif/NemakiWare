package controllers;

import org.apache.chemistry.opencmis.client.api.Session;

import model.Login;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;
import util.Util;
import views.html.login;

public class Application extends Controller{
	public static Result login() {
	    return ok(login.render(new Form<>(Login.class)));
	}
	
	public static Result authenticate(){
		Form<Login> input = Form.form(Login.class);
		input = input.bindFromRequest();
		String id = input.data().get("id");
		String password = input.data().get("password");
		
		if(validate(id, password)){
			session().clear();
			session("loginUserId", id);
			session("loginUserPassword", password);
			return redirect(routes.Node.index());
		}else{
			return badRequest(login.render(input));
		}
	}
	
	public static boolean validate(String id, String password){
		try{
			Session cmisSession = Util.createCmisSession(id, password);
		}catch(Exception e){
			e.printStackTrace();
			return false;
		}
		
		return true;
	}

	public static Result logout(){
		session().remove("loginUserId");
		return redirect(routes.Application.login());
	}
	
}
