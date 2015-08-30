package controllers;

import constant.Token;
import play.mvc.Action;
import play.mvc.Http;
import play.mvc.Http.Context;
import play.mvc.Result;
import play.mvc.Security;

public class Secured extends Security.Authenticator{

	@Override
	public String getUsername(Context ctx) {
		return ctx.session().get(Token.LOGIN_USER_ID);
	}

	@Override
	public Result onUnauthorized(Context ctx) {
		String repositoryId = ctx.session().get("repositoryId");
		return redirect(routes.Application.login(repositoryId));
	}
	
}
