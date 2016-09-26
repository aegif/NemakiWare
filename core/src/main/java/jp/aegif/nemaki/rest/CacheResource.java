package jp.aegif.nemaki.rest;

import java.util.concurrent.locks.Lock;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

@Path("/repo/{repositoryId}/cache/")
public class CacheResource extends ResourceBase{
	private NemakiCachePool nemakiCachePool;
	private ThreadLockService threadLockService;

	@DELETE
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("repositoryId") String repositoryId, @PathParam("id") String objectId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId);
		try{
			lock.lock();
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
		}finally{
			lock.unlock();
		}
		
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
	
	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
}
