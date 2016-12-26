package controllers;

import play.mvc.Controller;
import play.mvc.Result;
import org.pac4j.play.java.Secure;
public class JsTemplate extends Controller{
	public Result duplicateNameCheck(String repositoryId){
		return ok(views.js.node.js.duplicateNameCheck.render(repositoryId));
	}
}
