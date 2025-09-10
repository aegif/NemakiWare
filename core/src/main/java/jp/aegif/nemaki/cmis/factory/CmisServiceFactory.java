package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

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
		if (log.isDebugEnabled()) {
			log.debug("CMIS Service Factory constructor called - Thread: " + Thread.currentThread().getName());
		}
	}

	@PostConstruct
	public void setup(){
		if (log.isDebugEnabled()) {
			log.debug("CMIS Service Factory setup starting - Thread: " + Thread.currentThread().getName());
		}
		
		DEFAULT_MAX_ITEMS_TYPES = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_ITEMS_TYPES));
		DEFAULT_DEPTH_TYPES = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_DEPTH_TYPES));
		DEFAULT_MAX_ITEMS_OBJECTS = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_ITEMS_OBJECTS));
		DEFAULT_DEPTH_OBJECTS = DataUtil.convertToBigInteger(propertyManager.readValue(PropertyKey.CMIS_SERVER_DEFAULT_MAX_DEPTH_OBJECTS));
		
		if (log.isDebugEnabled()) {
			log.debug("CMIS Service Factory setup completed");
		}
	}

	/**
	 * Add NemakiRepository into repository map at first.
	 */
	@Override
	public void init(Map<String, String> parameters) {
		if (log.isDebugEnabled()) {
			log.debug("CMIS Service Factory init called with parameters: " + 
				(parameters != null ? parameters.toString() : "null"));
		}
	}

	@Override
	public org.apache.chemistry.opencmis.commons.server.CmisService getService(CallContext callContext) {
		String repositoryId = callContext.getRepositoryId();
		String userName = callContext.getUsername();
		
		// CRITICAL DEBUG: Always log CmisServiceFactory calls
		if (log.isDebugEnabled()) {
			log.debug("CMIS SERVICE FACTORY: getService called - Repository ID: " + repositoryId + 
				", Username: " + userName + ", Thread: " + Thread.currentThread().getName());
		}
		
		// Check if this is from HTTP request and log additional context
		try {
			if (callContext instanceof org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl) {
				org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl browserContext = 
					(org.apache.chemistry.opencmis.server.impl.browser.BrowserCallContextImpl) callContext;
				// Try to get HTTP request details
				if (log.isDebugEnabled()) {
					log.debug("CONTEXT: Browser Binding call context detected");
				}
			}
		} catch (Exception e) {
			if (log.isDebugEnabled()) {
				log.debug("CONTEXT: Error getting context details: " + e.getMessage());
			}
		}

		// Authentication
		boolean auth = authenticationService.login(callContext);

		if (auth) {
			if (log.isDebugEnabled()) {
				log.debug("AUTHENTICATION SUCCESS");
			}
			
			// Create CmisService - CRITICAL FIX: Use bean name to avoid TypeManager ambiguity
			CmisService calledCmisService = (CmisService) applicationContext.getBean("cmisService");
			if(calledCmisService == null){
				System.err.println("CRITICAL ERROR: CmisService bean is NULL");
				log.error("RepositoryId=" + repositoryId + " does not exist", new Throwable());
			} else {
				if (log.isDebugEnabled()) {
					log.debug("CMIS SERVICE RETRIEVED - Service class: " + calledCmisService.getClass().getName() + 
						", Service hash: " + calledCmisService.hashCode());
				}
			}

			CmisServiceWrapper wrapper = new CmisServiceWrapper(
					calledCmisService,
					DEFAULT_MAX_ITEMS_TYPES, DEFAULT_DEPTH_TYPES,
					DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS,
					callContext);
					
			if (log.isDebugEnabled()) {
				log.debug("CMIS SERVICE WRAPPER CREATED - Wrapper class: " + wrapper.getClass().getName() + 
					", Wrapper hash: " + wrapper.hashCode());
			}
			
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

			if (log.isDebugEnabled()) {
				log.debug("RETURNING CMIS SERVICE WRAPPER");
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
