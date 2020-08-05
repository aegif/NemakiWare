package jp.aegif.nemaki.util;

import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.YamlWriter;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;
import jp.aegif.nemaki.util.yaml.RepositorySetting;
import jp.aegif.nemaki.util.yaml.RepositorySettings;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.solr.core.SolrResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class CmisSessionFactory {
	private static final Logger logger = LoggerFactory.getLogger(CmisSessionFactory.class);

	private static Map<String, Session> sessions = new HashMap<String, Session>();
	private static NemakiTokenManager nemakiTokenManager = new NemakiTokenManager();
	private static RepositorySettings repositorySettings;
	private static PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);

	public static Session getSession(String repositoryId) {
logger.warn("sessions1:" + sessions.toString());
		if (!isConnectionSetup(repositoryId)) {
			sessions.put(repositoryId, setupCmisSession(repositoryId));
		}
logger.warn("sessions2:" + sessions.toString());
		Session session = sessions.get(repositoryId);
		if (session == null) {
			logger.warn("No CMIS repositoryId:{}", repositoryId);
		}
		return sessions.get(repositoryId);
	}

	public static void clearSession(String repositoryId) {
		Session session = sessions.get(repositoryId);
		if (session == null)
			return;
		sessions.remove(repositoryId);
	}

	private static Session setupCmisSession(String repositoryId) {
		// Parameter
		Map<String, String> parameter = buildCommonParam(repositoryId);
		buildRepositoryParam(parameter, repositoryId);

		// Create session
		Session session = null;
		try {
			// create session
			SessionFactory f = SessionFactoryImpl.newInstance();
			session = f.createSession(parameter);
			OperationContext operationContext = session.createOperationContext(null, false, false, false, null, null,
					false, null, false, 100); // Cache disabled
			session.setDefaultContext(operationContext);

			return session;
		} catch (Exception e) {
			logger.error("Failed to create a session to CMIS server", e);
		}

		return null;
	}

	private static Map<String, String> buildCommonParam(String repositoryId) {
		Map<String, String> parameter = new HashMap<>();

		// session locale
		parameter.put(SessionParameter.LOCALE_ISO3166_COUNTRY, "");
		parameter.put(SessionParameter.LOCALE_ISO639_LANGUAGE, "");

		// repository
		// String repositoryId =
		// NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_REPOSITORY);
		parameter.put(SessionParameter.REPOSITORY_ID, repositoryId);
		// parameter.put(org.apache.chemistry.opencmis.commons.impl.Constants.PARAM_REPOSITORY_ID,
		// NemakiConfig.getValue(PropertyKey.NEMAKI_CORE_URI_REPOSITORY));

		parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());

		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);
		String endpoint = getAtomEndpoint(protocol, host, port, context, repositoryId);
		parameter.put(SessionParameter.ATOMPUB_URL, endpoint);

		return parameter;
	}

	private static String getAtomEndpoint(String protocol, String host, String port, String context,
			String repositoryId) {
		try {
			URL url = new URL(protocol, host, Integer.parseInt(port), "");
			return String.format("%s/%s/atom/%s", url.toString(), context, repositoryId);
		} catch (Exception e) {
			logger.error("Error occurred during getting ATOM endpoint.", e);
		}
		return null;
	}

	private static void buildRepositoryParam(Map<String, String> parameter, String repositoryId) {
		// repository
		RepositorySetting setting = getRepositorySettings().get(repositoryId);

		// user credentials
		String user = setting.getUser();
		String password = setting.getPassword();
		parameter.put(SessionParameter.USER, user);
		parameter.put(SessionParameter.PASSWORD, password);

		// Auth token
		boolean authTokenEnabled = Boolean.parseBoolean(pm.readValue(PropertyKey.NEMAKI_CAPABILITY_EXTENDED_AUTH_TOKEN));
		if (authTokenEnabled) {
			String authToken = nemakiTokenManager.getOrRegister(repositoryId, user, password);
			if (authToken != null) {
				parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS,
						"jp.aegif.nemaki.util.NemakiAuthenticationProvider");
				parameter.put(Constant.AUTH_TOKEN, authToken);
				parameter.put(Constant.AUTH_TOKEN_APP, "solr");
			}
		}
	}

	public static RepositorySettings getRepositorySettings() {
		if (repositorySettings == null) {
			buildRepositorySettings();
		}
		return repositorySettings;
	}

	private static void buildRepositorySettings() {
		String location = pm.readValue(PropertyKey.REPOSITORIES_SETTING_FILE);
logger.warn(location);
		CmisSessionFactory.repositorySettings = readRepositorySettings(location);

	}

	private static RepositorySettings readRepositorySettings(String location) {
		SolrResourceLoader loader = new SolrResourceLoader(null);
		try {
			InputStream in = loader.openResource(location);
			YamlReader reader = new YamlReader(new InputStreamReader(in));
			reader.getConfig().setPropertyElementType(RepositorySettings.class, "settings", RepositorySetting.class);
			RepositorySettings settings = reader.read(RepositorySettings.class);

			return settings;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				loader.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;
	}

	public static void modifyRepositorySettings(RepositorySettings settings) {
		String location = pm.readValue(PropertyKey.REPOSITORIES_SETTING_FILE);
		SolrResourceLoader loader = new SolrResourceLoader(null);
		try {
			String configDir = loader.getConfigDir();
			File file = new File(configDir + location);
			YamlWriter writer = new YamlWriter(new FileWriter(file));
			writer.write(settings);
			writer.close();
		} catch (Exception ex) {
			logger.error("Error occurred during writing repository settings", ex);
		} finally {
			try {
				loader.close();
			} catch (Exception ex) {
				logger.error("Error occurred during closing SolrResourceLoader", ex);
			}
		}
	}

	public static boolean isConnectionSetup(String repositoryId) {
		return (sessions.get(repositoryId) != null);
	}

}
