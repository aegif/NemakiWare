package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;

@Path("/repo/{repositoryId}/config")
public class ConfigResource extends ResourceBase{
	private ContentDaoService contentDaoService;
	private ThreadLockService threadLockService;
	private PropertyManager propertyManager;

	@SuppressWarnings("unchecked")
	@GET
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public String list(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray configs = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			result = makeResult(status, result, errMsg);
			return result.toJSONString();
		}

		try {
			Set<String> keys = propertyManager.getKeys();
			for(String configKey : keys){
				JSONObject config = createConfig(repositoryId, configKey);
				configs.add(config);
			}
			result.put("configurations", configs);
		} catch (Exception e) {
			status = false;
			e.printStackTrace();
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_LIST);
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{key}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("key") String configKey,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			result = makeResult(status, result, errMsg);
			return result.toJSONString();
		}

		JSONObject config = createConfig(repositoryId, configKey);
		result.put("configuration", config);
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private JSONObject createConfig(String repositoryId, String configKey) {
		JSONObject config = new JSONObject();

		Object configValue = propertyManager.readValue(repositoryId, configKey);
		config.put("key", configKey);
		config.put("value", configValue);
		config.put("isDefault", false);
		return config;
	}


	@PUT
	@Produces(MediaType.APPLICATION_JSON)
	public String update(@PathParam("repositoryId") String repositoryId,
			@FormParam("key") String key, @FormParam("value") String value,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			result = makeResult(status, result, errMsg);
			return result.toJSONString();
		}

		Lock lock = threadLockService.getWriteLock(repositoryId, "configuration");
		lock.lock();
		try{
			Configuration conf = contentDaoService.getConfiguration(repositoryId);
			Map<String, Object> map = conf.getConfiguration();
			map.put(key, value);
			conf.setConfiguration(map);
			contentDaoService.update(repositoryId, conf);
		}catch(Exception e){
			status = false;
			e.printStackTrace();
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_LIST);
		}finally{
			lock.unlock();
		}

		JSONObject config = createConfig(repositoryId, key);
		result.put("configuration", config);
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private static final Set<String> SENSITIVE_KEYWORDS = new HashSet<>(Arrays.asList(
		"password", "secret", "accesskey", "secretkey", "clientsecret", "serviceaccountkey"
	));

	@SuppressWarnings("unchecked")
	@GET
	@Path("/properties")
	@Produces(MediaType.APPLICATION_JSON)
	public String properties(@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest httpRequest) {

		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray properties = new JSONArray();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			result = makeResult(status, result, errMsg);
			return result.toJSONString();
		}

		try {
			Set<String> keys = new TreeSet<>(propertyManager.getKeys());
			for (String key : keys) {
				JSONObject prop = new JSONObject();
				prop.put("key", key);

				// Get resolved value
				String value = propertyManager.readValue(repositoryId, key);
				// Mask sensitive values
				if (isSensitiveKey(key)) {
					prop.put("value", "***");
				} else {
					prop.put("value", value);
				}

				// Determine source
				prop.put("source", determineSource(repositoryId, key));

				// Determine category
				prop.put("category", determineCategory(key));

				properties.add(prop);
			}
			result.put("properties", properties);
		} catch (Exception e) {
			status = false;
			e.printStackTrace();
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_LIST);
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	private boolean isSensitiveKey(String key) {
		String lower = key.toLowerCase().replace(".", "").replace("_", "");
		for (String keyword : SENSITIVE_KEYWORDS) {
			if (lower.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private String determineSource(String repositoryId, String key) {
		// Check system property
		if (System.getProperty(key) != null) {
			return "system_property";
		}

		// Check environment variable
		String envKey = key.toUpperCase().replace('.', '_');
		if (System.getenv(envKey) != null) {
			return "environment";
		}

		// Check CouchDB dynamic configuration (repository-specific and system-wide)
		try {
			Configuration repoConf = contentDaoService.getConfiguration(repositoryId);
			if (repoConf != null && repoConf.getConfiguration().get(key) != null) {
				return "couchdb_dynamic";
			}
			Configuration sysConf = contentDaoService.getConfiguration("nemaki_conf");
			if (sysConf != null && sysConf.getConfiguration().get(key) != null) {
				return "couchdb_dynamic";
			}
		} catch (Exception e) {
			// ignore - fall through to property file check
		}

		// Check property file source
		String fileSource = propertyManager.getPropertySource(key);
		if (fileSource != null) {
			return fileSource;
		}

		return "default";
	}

	private String determineCategory(String key) {
		if (key.startsWith("db.")) return "DB";
		if (key.startsWith("rag.")) return "RAG";
		if (key.startsWith("solr.")) return "Solr";
		if (key.startsWith("cloud.")) return "Cloud";
		if (key.startsWith("oidc.") || key.startsWith("saml.") || key.startsWith("sso.")) return "SSO";
		if (key.startsWith("cmis.")) return "CMIS";
		if (key.startsWith("longterm.") || key.startsWith("retention.")) return "Archive";
		if (key.startsWith("capability.")) return "Capability";
		if (key.startsWith("property.")) return "Property";
		if (key.startsWith("basetype.")) return "BaseType";
		if (key.startsWith("cache.")) return "Cache";
		if (key.startsWith("server.")) return "Server";
		return "General";
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
