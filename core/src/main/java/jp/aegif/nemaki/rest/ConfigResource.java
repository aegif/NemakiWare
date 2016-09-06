package jp.aegif.nemaki.rest;

import java.util.Map;
import java.util.concurrent.locks.Lock;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.lock.ThreadLockService;

@Component
@Path("/repo/{repositoryId}/config/")
public class ConfigResource extends ResourceBase{
	private ContentDaoService contentDaoService;
	private ThreadLockService threadLockService;

	@PUT
	@Produces(MediaType.APPLICATION_JSON)
	public String update(@PathParam("repositoryId") String repositoryId, 
			@QueryParam("key") String key, @QueryParam("value") String value, 
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
		}finally{
			lock.unlock();
		}
		
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
}
