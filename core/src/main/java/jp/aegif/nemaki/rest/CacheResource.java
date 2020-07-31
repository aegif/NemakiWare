package jp.aegif.nemaki.rest;

import java.text.ParseException;
import java.util.GregorianCalendar;
import java.util.concurrent.locks.Lock;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import play.Logger;

@Path("/repo/{repositoryId}/cache/")
public class CacheResource extends ResourceBase{
	private NemakiCachePool nemakiCachePool;
	private ThreadLockService threadLockService;

	/**
	 *
	 * @param repositoryId
	 * @param objectId
	 * @param strBeforeDate
	 *            delete if cache data modification before this date
	 * @param httpRequest
	 * @return
	 */
	@DELETE
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("repositoryId") String repositoryId, @PathParam("id") String objectId,
			@QueryParam("date") String strBeforeDate, @Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId);
		try {
			CacheService cache = nemakiCachePool.get(repositoryId);
			lock.lock();
			if (StringUtils.isNotEmpty(strBeforeDate)) {
				GregorianCalendar beforeDate = DataUtil.convertToCalender(strBeforeDate);
				Content c = cache.getContentCache().get(objectId);
				if (c == null) {
					Logger.info("Target cache not found.");
					result.put("deleted", false);
				} else {
					if (beforeDate.compareTo(c.getModified()) > 0) {
						cache.removeCmisAndContentCache(objectId);
						result.put("deleted", true);
						Logger.info("Remove cmis object and content cache because updated by other.");
					}else{
						result.put("deleted", false);
					}
				}
			}else{
				cache.removeCmisAndContentCache(objectId);
				result.put("deleted", true);
			}
		} catch (ParseException e) {
			Logger.error(e.getMessage());
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_READ);
		} finally {
			lock.unlock();
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 *
	 * @param repositoryId
	 * @param parentId
	 * @param httpRequest
	 * @return
	 */
	@DELETE
	@Path("/tree/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String delete(@PathParam("repositoryId") String repositoryId, @PathParam("id") String parentId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
				
		if(!nemakiCachePool.get(repositoryId).getTreeCache().isCacheEnabled()){
			//do nothing when cache disabled
			result.put("treeCacheEnabled", false);
			return result.toJSONString();
		}

		Lock lock = threadLockService.getWriteLock(repositoryId, parentId);
		try {
			CacheService cache = nemakiCachePool.get(repositoryId);
			lock.lock();
			cache.removeCmisAndTreeCache(parentId);
			result.put("deleted", true);
		} catch (Exception e) {
			Logger.error(e.getMessage());
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_READ);
		} finally {
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
