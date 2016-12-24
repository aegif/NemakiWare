package util.authentication;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.exception.CredentialsException;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.play.PlayWebContext;

import constant.Token;
import play.Logger;
import play.Logger.ALogger;
import play.i18n.Messages;
import util.Util;

public class NemakiAuthenticator implements Authenticator<UsernamePasswordCredentials>{
	private static final ALogger logger = Logger.of(NemakiAuthenticator.class);

	@Override
	public void validate(UsernamePasswordCredentials credentials, WebContext context) throws HttpAction {
		final String repositoryId = context.getRequestParameter("repositoryId");
		final String userId = credentials.getUsername();
		final String password = credentials.getPassword();

		try{
			Session cmisSession = Util.createCmisSessionByBasicAuth(repositoryId, userId, password);

			PlayWebContext playCtx  = (PlayWebContext)context;
			String version = cmisSession.getRepositoryInfo().getProductVersion();
			Boolean	isAdmin = Util.isAdmin(repositoryId, userId, playCtx.getJavaContext());

	        final CommonProfile profile = new CommonProfile();
	        profile.setId(userId);
	        profile.addAttribute(Token.LOGIN_USER_ID, userId);
	        profile.addAttribute(Token.LOGIN_USER_PASSWORD, password);
	        profile.addAttribute(Token.LOGIN_REPOSITORY_ID, repositoryId);

	        profile.addAttribute(Token.LOGIN_USER_IS_ADMIN, isAdmin);
	        profile.addAttribute(Token.NEMAKIWARE_VERSION, version);
	        credentials.setUserProfile(profile);
		}catch(CmisUnauthorizedException e){
			throwsException(Messages.get("view.auth.login.error"));
		}catch(CmisConnectionException e){
			throwsException(Messages.get("view.auth.login.error.connection"));
		}
	}

	protected void throwsException(String message) {
		throw new CredentialsException(message);// 47
	}
}
