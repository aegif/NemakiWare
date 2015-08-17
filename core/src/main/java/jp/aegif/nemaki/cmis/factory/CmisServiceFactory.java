package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.Map;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Service factory class, specified in repository.properties.
 */
public class CmisServiceFactory extends AbstractServiceFactory implements
		org.apache.chemistry.opencmis.commons.server.CmisServiceFactory {

	private PropertyManager propertyManager;
	
	private jp.aegif.nemaki.cmis.factory.CmisService cmisService;
	
	private AuthenticationService authenticationService;
	private PrincipalService principalService;
	
	private static BigInteger DEFAULT_MAX_ITEMS_TYPES;
	private static BigInteger DEFAULT_DEPTH_TYPES;
	private static BigInteger DEFAULT_MAX_ITEMS_OBJECTS;
	private static BigInteger DEFAULT_DEPTH_OBJECTS;

	private static final Log log = LogFactory
			.getLog(AuthenticationServiceImpl.class);
	
	public CmisServiceFactory() {
		super();
	}
	
	@PostConstruct
	public void setup(){
		DEFAULT_MAX_ITEMS_TYPES = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_ITEMS_TYPES));
		DEFAULT_DEPTH_TYPES = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_DEPTH_TYPES));
		DEFAULT_MAX_ITEMS_OBJECTS = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_ITEMS_OBJECTS));
		DEFAULT_DEPTH_OBJECTS = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_DEPTH_OBJECTS));
	}
	
	/**
	 * Add NemakiRepository into repository map at first.
	 */
	@Override
	public void init(Map<String, String> parameters) {
	}

	@Override
	public org.apache.chemistry.opencmis.commons.server.CmisService getService(CallContext callContext) {
		// Create CmisService
		ConformanceCmisServiceWrapper wrapper = new ConformanceCmisServiceWrapper(
				cmisService,
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
			throw new CmisProxyAuthenticationException("[userName="
					+ callContext.getUsername() + "]" + "Authentication failed");
		}
	}
	
	private boolean login(CallContext callContext) {
		//SSO
		if(loginWithExternalAuth(callContext)){
			return true;
		}
		
		//Token for Basic auth
		if(loginWithToken(callContext)){
			return true;
		}
		
		//Basic auth
		return loginWithBasicAuth(callContext);
	}

	private boolean loginWithExternalAuth(CallContext callContext){
		final String repositoryId = "bedroom"; //TODO hard coding
		
		String proxyHeaderKey = propertyManager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_PROXY_HEADER);
		String proxyUserId = (String) callContext.get(proxyHeaderKey);
		if(StringUtils.isBlank(proxyUserId)){
			log.warn("Not authenticated user");
			return false;
		}else{
			User user = principalService.getUserById(repositoryId, proxyUserId);
			if(user == null){
				User newUser = new User(proxyUserId, proxyUserId, "", "", "", BCrypt.hashpw(proxyUserId, BCrypt.gensalt()));
				principalService.createUser(repositoryId, newUser);
				log.debug("Authenticated userId=" + newUser.getUserId());
			}else{
				log.debug("Authenticated userId=" + user.getUserId());
			}
			return true;
		}
	}
	
	private boolean loginWithToken(CallContext callContext) {
		String userName = callContext.getUsername();
		String token;
		if(callContext.get("nemaki_auth_token") == null){
			return false;
		}else{
			token = (String)callContext.get("nemaki_auth_token");
			if(StringUtils.isBlank(token)){
				return false;
			}
		}
		Object _app = callContext.get("nemaki_auth_token_app");
		String app = (_app == null) ? "" : (String)_app;
		
		if (authenticationService.authenticateUserByToken(app, userName,
				token)) {
			if (authenticationService
					.authenticateAdminByToken(userName)) {
				setAdminFlagInContext(callContext, true);
			} else {
				setAdminFlagInContext(callContext, false);
			}
			return true;
		}
		
		return false;
	}

	private boolean loginWithBasicAuth(CallContext callContext) {
		//TODO
		final String repositoryId = "bedroom"; //TODO get from callContext
		
		
		// Basic auth with id/password
		User user = authenticationService.getAuthenticatedUser(
				repositoryId, callContext.getUsername(), callContext.getPassword());
		if (user == null)
			return false;
		boolean isAdmin = user.isAdmin() == null ? false : true;
		setAdminFlagInContext(callContext, isAdmin);
		return true;
	}

	private void setAdminFlagInContext(CallContext callContext, Boolean isAdmin) {
		((CallContextImpl) callContext).put(
				CallContextKey.IS_ADMIN, isAdmin);
	}

	@Override
	public void destroy() {
		super.destroy();
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setAuthenticationService(
			AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setCmisService(CmisService cmisService) {
		this.cmisService = cmisService;
	}
}