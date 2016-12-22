package controllers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import antlr.Utils;
import constant.PropertyKey;
import constant.Token;
import play.mvc.Http.Context;
import play.mvc.Http.Request;
import play.mvc.Http.Session;
import play.mvc.Result;
import play.mvc.Security;
import util.NemakiConfig;
import util.Util;
import org.pac4j.play.java.Secure;
public class Secured extends Security.Authenticator {

	@Override
	public String getUsername(Context ctx) {
		Session session = ctx.session();
		final String requestRepoId = extractRepositoryId(ctx.request());
		final String sessionRepoId = session.get(Token.LOGIN_REPOSITORY_ID);
		String userId = null;

		if (StringUtils.isBlank(sessionRepoId) || sessionRepoId.equals(requestRepoId)) {
			userId =  session.get(Token.LOGIN_USER_ID);
			if (userId == null){
				final String remoteUserHeader = NemakiConfig.getRemoteAuthHeader();
				userId = ctx.request().getHeader(remoteUserHeader);
				if (userId != null){
					//無ければここで入れてしまう
					//TODO 本当は初アクセス時にいれるべき
					Util.setupSessionHeaderAuth(session, sessionRepoId, userId);
				}
			}
		}

		// Redirect to login page
		return userId;
	}

	@Override
	public Result onUnauthorized(Context ctx) {
		String repoId = ctx.session().get(Token.LOGIN_REPOSITORY_ID);

		if(StringUtils.isBlank(repoId)){
			repoId = extractRepositoryId(ctx.request());
			if(StringUtils.isBlank(repoId)){
				return redirect(routes.Application.error());
			}
		}
		return redirect(routes.Application.login(repoId));
	}

	private String extractRepositoryId(Request request) {
		String uri = request.uri();
		Pattern p = Pattern.compile("^/ui/repo/([^/]*).*");
		Matcher m = p.matcher(uri);
		if (m.find()) {
			return m.group(1);
		}

		return null;
	}
}
