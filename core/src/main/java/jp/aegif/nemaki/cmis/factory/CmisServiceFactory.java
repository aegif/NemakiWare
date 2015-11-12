package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.Map;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.CmisServiceWrapper;
import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Service factory class, specified in repository.properties.
 */
public class CmisServiceFactory extends AbstractServiceFactory implements
		org.apache.chemistry.opencmis.commons.server.CmisServiceFactory {

	private PropertyManager propertyManager;

	private jp.aegif.nemaki.cmis.factory.CmisService cmisService;

	private AuthenticationService authenticationService;

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
		String repositryId = callContext.getRepositoryId();
		String userName = callContext.getUsername();

		// Authentication
		boolean auth = authenticationService.login(callContext);


		if (auth) {
			String msg = String.format("[repository=%1$s][userName=%2$s]Authentication succeeded",repositryId, userName);
			log.info(msg);

			// Create CmisService
			CmisServiceWrapper wrapper = new CmisServiceWrapper(
					cmisService,
					DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
					DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS,
					callContext);

			return wrapper;
		} else {
			String msg = String.format("[repository=%1$s][userName=%2$s]Authentication failed",repositryId, userName);
			throw new CmisUnauthorizedException(msg, BigInteger.valueOf(401));
		}
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

	public void setCmisService(CmisService cmisService) {
		this.cmisService = cmisService;
	}
}