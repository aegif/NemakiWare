package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.CmisServiceWrapper;
import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringUtil;

import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Service factory class, specified in repository.properties.
 */
public class CmisServiceFactory extends AbstractServiceFactory implements
		org.apache.chemistry.opencmis.commons.server.CmisServiceFactory, ApplicationContextAware{

	private ApplicationContext applicationContext;
	private PropertyManager propertyManager;

	private RepositoryInfoMap repositoryInfoMap;
	
	private AuthenticationService authenticationService;

	private static BigInteger DEFAULT_MAX_ITEMS_TYPES;
	private static BigInteger DEFAULT_DEPTH_TYPES;
	private static BigInteger DEFAULT_MAX_ITEMS_OBJECTS;
	private static BigInteger DEFAULT_DEPTH_OBJECTS;

	private static final Log log = LogFactory
			.getLog(CmisServiceFactory.class);

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
		String repositoryId = callContext.getRepositoryId();
		String userName = callContext.getUsername();

		// Authentication
		boolean auth = authenticationService.login(callContext);

		if (auth) {
			// Create CmisService
			CmisService calledCmisService = SpringUtil.getBeanByType(applicationContext, CmisService.class);
			if(calledCmisService == null){
				log.error("RepositoryId=" + repositoryId + " does not exist", new Throwable());
			}
			
			CmisServiceWrapper wrapper = new CmisServiceWrapper(
					calledCmisService,
					DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
					DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS,
					callContext);
			if(log.isTraceEnabled()){
				log.trace("nemaki_log[FACTORY]"
						+ "CmisService@" 
						+ calledCmisService.hashCode() 
						+ " with CallContext@" 
						+ callContext.hashCode()
						+ "[repositoryId=" + callContext.getRepositoryId()
						+ ", userId=" + callContext.getUsername()
						+ "] is generated");
			}
			
			return wrapper;
		} else {
			String msg = String.format("[Repository=%1$s][UserName=%2$s]Authentication failed",repositoryId, userName);
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

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}