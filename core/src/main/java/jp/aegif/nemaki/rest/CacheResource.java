package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import play.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.text.ParseException;
import java.util.GregorianCalendar;
import java.util.concurrent.locks.Lock;

@Path("/repo/{repositoryId}/cache/")
public class CacheResource extends ResourceBase{
	private NemakiCachePool nemakiCachePool;
	private ThreadLockService threadLockService;
	private jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager;

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
	
	public void setTypeManager(jp.aegif.nemaki.cmis.aspect.type.TypeManager typeManager) {
		this.typeManager = typeManager;
	}
	
	/**
	 * Invalidate type definition cache and force regeneration
	 * This triggers TypeManager to rebuild all type definitions with the latest fixes
	 *
	 * @param repositoryId
	 * @param httpRequest
	 * @return JSON result indicating success/failure and details
	 */
	@DELETE
	@Path("/types")
	@Produces(MediaType.APPLICATION_JSON)
	public String invalidateTypeCache(@PathParam("repositoryId") String repositoryId, @Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();
		
		try {
			Logger.info("=== TYPE CACHE INVALIDATION REQUEST ===");
			Logger.info("Repository: " + repositoryId);
			Logger.info("Triggering TypeManager cache invalidation and regeneration...");
			
			// Get Spring Application Context
			ApplicationContext appContext = WebApplicationContextUtils.getWebApplicationContext(
				httpRequest.getServletContext());
			
			if (appContext == null) {
				throw new RuntimeException("Spring ApplicationContext not available");
			}
			
			if (typeManager == null) {
				throw new RuntimeException("TypeManager not properly injected - check Spring configuration");
			}
			
			Logger.info("TypeManager found: " + typeManager.getClass().getName());
			
			// Call TypeManager to invalidate and regenerate type definitions
			// This will force all buildTypeDefinitionFromDB methods to execute with our fixes
			java.lang.reflect.Method invalidateMethod = typeManager.getClass().getDeclaredMethod("invalidateTypeDefinitionCache", String.class);
			invalidateMethod.setAccessible(true);
			invalidateMethod.invoke(typeManager, repositoryId);
			
			Logger.info("TYPE CACHE INVALIDATION COMPLETED SUCCESSFULLY");
			
			result.put("invalidated", true);
			result.put("repository", repositoryId);
			result.put("message", "Type definition cache invalidated and regenerated successfully");
			
		} catch (Exception e) {
			Logger.error("TYPE CACHE INVALIDATION FAILED: " + e.getMessage(), e);
			status = false;
			addErrMsg(errMsg, ITEM_ERROR, ErrorCode.ERR_UPDATE);
			result.put("invalidated", false);
			result.put("error", e.getMessage());
		}
		
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}
}
