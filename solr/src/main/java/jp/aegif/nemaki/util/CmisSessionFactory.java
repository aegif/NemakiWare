package jp.aegif.nemaki.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;

import com.esotericsoftware.yamlbeans.YamlReader;

import org.apache.solr.core.SolrResourceLoader;

import jp.aegif.nemaki.util.impl.PropertyManagerImpl;
import jp.aegif.nemaki.util.yaml.RepositorySetting;
import jp.aegif.nemaki.util.yaml.RepositorySettings;

public class CmisSessionFactory {
	private static final Logger logger = Logger.getLogger(CmisSessionFactory.class);
	
	private static Map<String,Session> sessions = new HashMap<String,Session>();
	private static NemakiTokenManager nemakiTokenManager = new NemakiTokenManager();
	private static RepositorySettings repositorySettings;
	private static PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);
	
	
	public static Session getSession(String repositoryId){
		if(!isConnectionSetup(repositoryId)){
			sessions.put(repositoryId, setupCmisSession(repositoryId));
		}
		
		Session session = sessions.get(repositoryId);
		if(session == null){
			logger.warn("No CMIS repositoryId:" + repositoryId);
		}
		return sessions.get(repositoryId);
	}
	
	private static Session setupCmisSession(String repositoryId){
		// Parameter
		Map<String, String> parameter = buildCommonParam();
		buildRepositoryParam(parameter, repositoryId);
		
		// Create session
		Session session = null;
		try {
			// create session
			SessionFactory f = SessionFactoryImpl.newInstance();
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

	private static Map<String,String> buildCommonParam(){
		Map<String, String> parameter = new HashMap<>();
		
		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);
		String wsEndpoint = pm.readValue(PropertyKey.CMIS_SERVER_WS_ENDPOINT);
		String url = getCmisUrl(protocol, host, port, context, wsEndpoint);
		String country = pm.readValue(PropertyKey.CMIS_LOCALE_COUNTRY);
		String language = pm.readValue(PropertyKey.CMIS_LOCALE_LANGUAGE);
		
		// session locale
		parameter.put(SessionParameter.LOCALE_ISO3166_COUNTRY, country);
		parameter.put(SessionParameter.LOCALE_ISO639_LANGUAGE, language);

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
		
		return parameter;
	}
	
	private static void buildRepositoryParam(Map<String, String> parameter, String repositoryId){
		// repository
		parameter.put(SessionParameter.REPOSITORY_ID, repositoryId);
				
		RepositorySetting setting = getRepositorySettings().get(repositoryId);
		
		// user credentials
		String user = setting.getUser();
		parameter.put(SessionParameter.USER, setting.getUser());
		String password = setting.getPassword();
		parameter.put(SessionParameter.PASSWORD, setting.getPassword());
		
		//Auth token
		Boolean authTokenEnabled = 
				Boolean.valueOf(pm.readValue(PropertyKey.NEMAKI_CAPABILITY_EXTENDED_AUTH_TOKEN));
		if(authTokenEnabled){
			parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "jp.aegif.nemaki.util.NemakiAuthenticationProvider");
			String authToken = nemakiTokenManager.getOrRegister(repositoryId, user, password);
			parameter.put(Constant.AUTH_TOKEN, authToken);
			parameter.put(Constant.AUTH_TOKEN_APP, "ui");
		}
	}
	
	public static RepositorySettings getRepositorySettings(){
		if (repositorySettings == null){
			buildRepositorySettings();
		}
		return repositorySettings;
	}
	
	private static void buildRepositorySettings(){
		try {
			SolrResourceLoader loader = new SolrResourceLoader(null);
			String location = pm.readValue(PropertyKey.REPOSITORIES_SETTING_FILE);
			InputStream in = loader.openResource(location);
			
			YamlReader reader = new YamlReader(new InputStreamReader(in));
			reader.getConfig().setPropertyElementType(RepositorySettings.class, "settings", RepositorySetting.class);
			RepositorySettings settings = reader.read(RepositorySettings.class);
			CmisSessionFactory.repositorySettings = settings;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
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

	public static boolean isConnectionSetup(String repositoryId) {
		return (sessions.get(repositoryId) != null);
	}
	
	public static List<Repository> getRepositories(){
		Map<String,String> parameter = buildCommonParam();
		SessionFactory f = SessionFactoryImpl.newInstance();
		List<Repository> repositories = f.getRepositories(parameter);
		
		if(CollectionUtils.isEmpty(repositories)){
			logger.error("No CMIS repository!");
		}
		
		return repositories;
		
	}
}
