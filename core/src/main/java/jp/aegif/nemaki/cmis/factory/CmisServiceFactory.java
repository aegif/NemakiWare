package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.constant.NemakiConstant;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Service factory class, specified in repository.properties.
 */
public class CmisServiceFactory extends AbstractServiceFactory implements
		org.apache.chemistry.opencmis.commons.server.CmisServiceFactory {

	private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger
			.valueOf(50);
	private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger
			.valueOf(-1);
	private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger
			.valueOf(200);
	private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger
			.valueOf(10);

	private static final Log log = LogFactory
			.getLog(AuthenticationServiceImpl.class);
	private Repository repository;
	private AuthenticationService authenticationService;

	public CmisServiceFactory() {
		super();
	}

	/**
	 * Repository reference to all repositories.
	 */
	private RepositoryMap repositoryMap;

	/**
	 * Add NemakiRepository into repository map at first.
	 */
	@Override
	public void init(Map<String, String> parameters) {
		repositoryMap.addRepository(repository);
	}

	@Override
	public CmisService getService(CallContext callContext) {
		// Create CmisService
		ConformanceCmisServiceWrapper wrapper = new ConformanceCmisServiceWrapper(
				new jp.aegif.nemaki.cmis.factory.CmisService(repositoryMap),
				DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
				DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);
		wrapper.setCallContext(callContext);

		// Authentication
		boolean auth = login(callContext);
		if (auth) {
			log.info("[userName=" + callContext.getUsername() + "]"
					+ "Authentication succeeded");
			return wrapper;
		} else {
			throw new CmisPermissionDeniedException("[userName="
					+ callContext.getUsername() + "]" + "Authentication failed");
		}
	}

	private boolean login(CallContext callContext) {
		boolean tokenAuth = loginWithToken(callContext);

		// Cookie token Authentication
		if (tokenAuth) {
			return true;
		}

		// Basic Authentication
		boolean basicAuth = loginWithBasicAuth(callContext);
		if (basicAuth) {
			setTokenCookie(callContext);
			return true;
		}

		return false;
	}

	private boolean loginWithToken(CallContext callContext) {
		HttpServletRequest req = (HttpServletRequest) callContext
				.get("httpServletRequest");
		Cookie[] cookies = req.getCookies();
		if (cookies != null) {
			for (Cookie cookie : req.getCookies()) {
				if ((NemakiConstant.AUTH_TOKEN_PREFIX + callContext
						.getUsername()).equals(cookie.getName())) {
					// Token based auth
					String token = cookie.getValue();
					String userName = callContext.getUsername();
					if (authenticationService.authenticateUserByToken(userName,
							token)) {
						if (authenticationService
								.authenticateAdminByToken(userName)) {
							setAdminFlagInContext(callContext, true);
						} else {
							setAdminFlagInContext(callContext, false);
						}
						return true;
					}
				}
			}
		}

		log.info("[userName=" + callContext.getUsername() + "]"
				+ "has no basic authentication token");
		return false;
	}

	private boolean loginWithBasicAuth(CallContext callContext) {
		// Basic auth with id/password
		User user = authenticationService.getAuthenticatedUser(
				callContext.getUsername(), callContext.getPassword());
		if (user == null)
			return false;
		boolean isAdmin = user.isAdmin() == null ? false : true;
		if (user == null) {
			return false;
		} else {
			setAdminFlagInContext(callContext, isAdmin);
			return true;
		}
	}

	private void setAdminFlagInContext(CallContext callContext, Boolean isAdmin) {
		((CallContextImpl) callContext).put(
				NemakiConstant.CALL_CONTEXT_IS_ADMIN, isAdmin);
	}

	private void setTokenCookie(CallContext callContext) {
		String token = authenticationService.registerToken(callContext);
		Cookie cookie = new Cookie(NemakiConstant.AUTH_TOKEN_PREFIX
				+ callContext.getUsername(), token);
		cookie.setMaxAge(60 * 60 * 12);

		HttpServletResponse response = (HttpServletResponse) callContext
				.get("httpServletResponse");
		response.addCookie(cookie);
	}

	@Override
	public void destroy() {
		super.destroy();
	}

	public void setAuthenticationService(
			AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public void setRepository(Repository repository) {
		this.repository = repository;
	}

	public void setRepositoryMap(RepositoryMap repositoryMap) {
		this.repositoryMap = repositoryMap;
	}

}