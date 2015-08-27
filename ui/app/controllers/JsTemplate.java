package controllers;

import play.mvc.Controller;
import play.mvc.Result;

public class JsTemplate extends Controller{
	public static Result duplicateNameCheck(String repositoryId){
		return ok(views.js.node.js.duplicateNameCheck.render(repositoryId));
	}
}
