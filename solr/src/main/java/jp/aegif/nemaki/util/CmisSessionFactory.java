package jp.aegif.nemaki.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.log4j.Logger;

import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

public class CmisSessionFactory {
	private static final Logger logger = Logger.getLogger(CmisSessionFactory.class);
	
	private static Session session;
	private static NemakiTokenManager nemakiTokenManager = new NemakiTokenManager();
	
	
	public static Session getSession(){
		if(!isConnectionSetup()){
			session = setupCmisSession();
		}
		return session;
	}
	
	private static Session setupCmisSession(){
		PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);

		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);
		String wsEndpoint = pm.readValue(PropertyKey.CMIS_SERVER_WS_ENDPOINT);
		String url = getCmisUrl(protocol, host, port, context, wsEndpoint);

		String repository = pm.readValue(PropertyKey.CMIS_REPOSITORY_MAIN);
		String country = pm.readValue(PropertyKey.CMIS_LOCALE_COUNTRY);
		String language = pm.readValue(PropertyKey.CMIS_LOCALE_LANGUAGE);
		String user = pm.readValue(PropertyKey.CMIS_PRINCIPAL_ADMIN_ID);
		String password = pm
				.readValue(PropertyKey.CMIS_PRINCIPAL_ADMIN_PASSWORD);

		SessionFactory f = SessionFactoryImpl.newInstance();
		Map<String, String> parameter = new HashMap<String, String>();

		// user credentials
		parameter.put(SessionParameter.USER, user);
		parameter.put(SessionParameter.PASSWORD, password);

		// session locale
		parameter.put(SessionParameter.LOCALE_ISO3166_COUNTRY, country);
		parameter.put(SessionParameter.LOCALE_ISO639_LANGUAGE, language);

		// repository
		parameter.put(SessionParameter.REPOSITORY_ID, repository);

		// WebServices ports
		parameter.put(SessionParameter.BINDING_TYPE,
				BindingType.WEBSERVICES.value());

		parameter.put(SessionParameter.WEBSERVICES_ACL_SERVICE, url
				+ "ACLService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, url
				+ "DiscoveryService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_MULTIFILING_SERVICE, url
				+ "MultiFilingService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_NAVIGATION_SERVICE, url
				+ "NavigationService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_OBJECT_SERVICE, url
				+ "ObjectService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_POLICY_SERVICE, url
				+ "PolicyService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE, url
				+ "RelationshipService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, url
				+ "RepositoryService?wsdl");
		parameter.put(SessionParameter.WEBSERVICES_VERSIONING_SERVICE, url
				+ "VersioningService?wsdl");
		
		//Auth token
		Boolean authTokenEnabled = 
				Boolean.valueOf(pm.readValue(PropertyKey.NEMAKI_CAPABILITY_EXTENDED_AUTH_TOKEN));
		if(authTokenEnabled){
			parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "jp.aegif.nemaki.util.NemakiAuthenticationProvider");
			String authToken = nemakiTokenManager.getOrRegister(user, password);
			parameter.put(Constant.AUTH_TOKEN, authToken);
			parameter.put(Constant.AUTH_TOKEN_APP, "ui");
		}
		
		Session session = null;
		try {
			// create session
			session = f.createSession(parameter);
			OperationContext operationContext = session
					.createOperationContext(null, false, false, false, null,
							null, false, null, true, 100);
			session.setDefaultContext(operationContext);
		
			return session;
		} catch (Exception e) {
			logger.error("Failed to create a session to CMIS server", e);
		}
		
		return null;
	}

	private static String getCmisUrl(String protocol, String host, String port,
			String context, String wsEndpoint) {
		try {
			URL url = new URL(protocol, host, Integer.parseInt(port), "");
			return url.toString() + "/" + context + "/" + wsEndpoint + "/";
		} catch (NumberFormatException e) {
			logger.error("", e);
			e.printStackTrace();
		} catch (MalformedURLException e) {
			logger.error("", e);
		}
		return null;
	}

	public static boolean isConnectionSetup() {
		return (session != null);
	}
}
