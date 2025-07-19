package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.Set;
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
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("key") String configKey) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
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
