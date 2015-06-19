package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.Map;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Service factory class, specified in repository.properties.
 */
public class CmisServiceFactory extends AbstractServiceFactory implements
		org.apache.chemistry.opencmis.commons.server.CmisServiceFactory {

	private PropertyManager propertyManager;
	private Repository repository;
	private AuthenticationService authenticationService;
	
	private RepositoryMap repositoryMap;
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
			throw new CmisProxyAuthenticationException("[userName="
					+ callContext.getUsername() + "]" + "Authentication failed");
		}
	}

	private boolean login(CallContext callContext) {
		boolean authenticated = false;
		
		Object _authToken = callContext.get("nemaki_auth_token");
		
		//To use token auth, don't put in password value
		if(_authToken != null && StringUtils.isNotBlank((String)_authToken)){
			authenticated = loginWithToken(callContext);
		}
		
		if(authenticated){
			return true;
		}else{
			return loginWithBasicAuth(callContext);
		}
		
	}

	private boolean loginWithToken(CallContext callContext) {
		String userName = callContext.getUsername();
		String token = (String)callContext.get("nemaki_auth_token");
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

	public void setRepository(Repository repository) {
		this.repository = repository;
	}

	public void setRepositoryMap(RepositoryMap repositoryMap) {
		this.repositoryMap = repositoryMap;
	}

}