/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.util.DateUtil;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.locks.Lock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.archive.LongTermStorageAdapter;
import jp.aegif.nemaki.archive.LongTermStorageAdapterFactory;
import jp.aegif.nemaki.archive.RetentionMigrationLog;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.dao.RetentionLogDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@Path("/repo/{repositoryId}/archive")
public class ArchiveResource extends ResourceBase {

	private static final Log log = LogFactory
            .getLog(ArchiveResource.class);

	private ContentService contentService;
	private PropertyManager propertyManager;
	private RetentionLogDaoService retentionLogDaoService;
	private LongTermStorageAdapterFactory longTermStorageAdapterFactory;
	private ThreadLockService threadLockService;
	private NemakiCachePool nemakiCachePool;

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setRetentionLogDaoService(RetentionLogDaoService retentionLogDaoService) {
		this.retentionLogDaoService = retentionLogDaoService;
	}

	public void setLongTermStorageAdapterFactory(LongTermStorageAdapterFactory longTermStorageAdapterFactory) {
		this.longTermStorageAdapterFactory = longTermStorageAdapterFactory;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	/**
	 * Get ContentService with fallback to SpringContext lookup.
	 * Jersey may create its own instances instead of using Spring beans,
	 * so we need this fallback for dependency injection.
	 */
	private ContentService getContentService() {
		if (contentService != null) {
			return contentService;
		}
		// Fallback to manual Spring context lookup
		try {
			ContentService service = SpringContext.getApplicationContext()
					.getBean("ContentService", ContentService.class);
			if (service != null) {
				log.debug("ContentService retrieved from SpringContext successfully");
				return service;
			}
		} catch (Exception e) {
			log.error("Failed to get ContentService from SpringContext: " + e.getMessage(), e);
		}
		// Final fallback - try different bean name patterns
		try {
			ContentService service = SpringContext.getApplicationContext()
					.getBean("contentService", ContentService.class);
			if (service != null) {
				log.debug("ContentService retrieved from SpringContext with lowercase name");
				return service;
			}
		} catch (Exception e) {
			log.debug("Could not find contentService with lowercase name: " + e.getMessage());
		}
		log.error("ContentService is null and SpringContext fallback failed - dependency injection issue");
		return null;
	}

	//FIXME Attachment should always be got out of the output on this layer
	@SuppressWarnings("unchecked")
	@GET
	@Path("/index")
	@Produces(MediaType.APPLICATION_JSON)
	public String index(
			@PathParam("repositoryId") String repositoryId,
			@QueryParam("skip") Integer skip,
			@QueryParam("limit") Integer limit,
			@QueryParam("desc") Boolean desc,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray list = new JSONArray();
		JSONArray errMsg = new JSONArray();

		String username = getCallContextUsername(httpRequest);
		if (username == null) {
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN);
			return makeResult(false, result, errMsg).toJSONString();
		}

		boolean adminUser = isAdmin(httpRequest);

		try{
			List<Archive> archives;
			if (adminUser) {
				archives = getContentService().getArchives(repositoryId, skip, limit, desc);
			} else {
				archives = getContentService().getArchivesByCreator(repositoryId, username);
			}
			for(Archive a : archives){
				//Filter out Attachment & old Versions
				if (NodeType.ATTACHMENT.value().equals(a.getType())){
					continue;
				}else if (NodeType.CMIS_DOCUMENT.value().equals(a.getType())){
					boolean ilv = (a.isLatestVersion() != null) ? a.isLatestVersion() : false;
					if (!ilv) continue;
				}

				JSONObject o = buildArchiveJson(a);

				if(a.isDocument()){
					o.put("mimeType", a.getMimeType());
				}

				list.add(o);
			}
			result.put("archives", list);
			result.put("isAdmin", adminUser);
		}catch(Exception e){
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/show/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String show(@PathParam("repositoryId") String repositoryId, @PathParam("id") String id,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		String username = getCallContextUsername(httpRequest);
		if (username == null) {
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN);
			return makeResult(false, result, errMsg).toJSONString();
		}

		boolean adminUser = isAdmin(httpRequest);

		try {
			Archive archive = getContentService().getArchive(repositoryId, id);
			if (archive == null) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_NOTFOUND);
			} else if (!adminUser && !username.equals(archive.getCreator())) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN);
			} else {
				JSONObject archiveJson = buildArchiveJson(archive);
				if (archive.isDocument()) {
					archiveJson.put("mimeType", archive.getMimeType());
				}
				result.put("archive", archiveJson);
			}
		} catch (Exception e) {
			e.printStackTrace();
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}

		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@PUT
	@Path("/restore/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String restore(@PathParam("repositoryId") String repositoryId, @PathParam("id") String id,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		String username = getCallContextUsername(httpRequest);
		if (username == null) {
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN);
			return makeResult(false, result, errMsg).toJSONString();
		}

		boolean adminUser = isAdmin(httpRequest);

		try{
			Archive archiveToRestore = getContentService().getArchive(repositoryId, id);
			if (archiveToRestore == null) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_NOTFOUND);
				return makeResult(status, result, errMsg).toJSONString();
			}

			// Non-admin can only restore their own archives
			if (!adminUser && !username.equals(archiveToRestore.getCreator())) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN);
				return makeResult(status, result, errMsg).toJSONString();
			}

			// Check if archive is in cold storage - cannot restore directly
			if (Archive.STATE_ARCHIVED_COLD.equals(archiveToRestore.getEffectiveArchiveState())) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_RESTORE_FROM_COLD_STORAGE);
				return makeResult(status, result, errMsg).toJSONString();
			}
			getContentService().restoreArchive(repositoryId, id);
		}catch(ParentNoLongerExistException e){
			log.error(e, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_RESTORE_BECAUSE_PARENT_NO_LONGER_EXISTS);
		}catch(Exception e){
			log.error(e, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_DESTROY);
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	@DELETE
	@Path("/destroy/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	public String destroy(@PathParam("repositoryId") String repositoryId, @PathParam("id") String id,
			@Context HttpServletRequest httpRequest){
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		// Admin check
		status = checkAdmin(errMsg, httpRequest);

		if (status) {
			try{
				getContentService().destroyArchive(repositoryId, id);
			}catch(Exception e){
				log.error(e, e);
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_DESTROY);
			}
		}
		result = makeResult(status, result, errMsg);
		return result.toJSONString();
	}

	/**
	 * Get retention settings (Admin only).
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/retention-settings")
	@Produces(MediaType.APPLICATION_JSON)
	public String getRetentionSettings(
			@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		try {
			PropertyManager pm = getPropertyManager();
			if (pm == null) {
				status = false;
				addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
				return makeResult(status, result, errMsg).toJSONString();
			}

			JSONObject settings = new JSONObject();
			settings.put("enabled", pm.readBoolean(PropertyKey.RETENTION_ENABLED));

			String coldAfterDays = pm.readValue(PropertyKey.RETENTION_ARCHIVE_COLD_AFTER_DAYS);
			settings.put("coldAfterDays", coldAfterDays != null ? coldAfterDays : "90");

			String cronExpression = pm.readValue(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_COLD);
			settings.put("cronExpression", cronExpression != null ? cronExpression : "");

			String storageType = pm.readValue(PropertyKey.LONGTERM_STORAGE_TYPE);
			settings.put("storageType", storageType != null ? storageType : "filesystem");

			// Check storage connection
			boolean storageConnected = false;
			LongTermStorageAdapterFactory factory = getLongTermStorageAdapterFactory();
			if (factory != null) {
				LongTermStorageAdapter adapter = factory.getAdapter();
				if (adapter != null) {
					storageConnected = adapter.checkConnection();
				}
			}
			settings.put("storageConnected", storageConnected);

			settings.put("keepLocalCopy", pm.readBoolean(PropertyKey.RETENTION_COLD_KEEP_LOCAL_COPY));

			String localArchiveAfterDays = pm.readValue(PropertyKey.RETENTION_ARCHIVE_LOCAL_AFTER_DAYS);
			settings.put("localArchiveAfterDays", localArchiveAfterDays != null ? localArchiveAfterDays : "");

			String localArchiveCron = pm.readValue(PropertyKey.RETENTION_SCHEDULE_ARCHIVE_LOCAL);
			settings.put("localArchiveCron", localArchiveCron != null ? localArchiveCron : "");

			result.put("settings", settings);
		} catch (Exception e) {
			log.error("Failed to get retention settings", e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}

		return makeResult(status, result, errMsg).toJSONString();
	}

	/**
	 * Get migration logs (Admin only).
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/migration-logs")
	@Produces(MediaType.APPLICATION_JSON)
	public String getMigrationLogs(
			@PathParam("repositoryId") String repositoryId,
			@QueryParam("limit") Integer limit,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		try {
			RetentionLogDaoService dao = getRetentionLogDaoService();
			if (dao == null) {
				// Return empty list if DAO not configured
				result.put("logs", new JSONArray());
				return makeResult(status, result, errMsg).toJSONString();
			}

			int maxLogs = (limit != null && limit > 0) ? Math.min(limit, 100) : 50;
			List<RetentionMigrationLog> logs = dao.getLogs(repositoryId, maxLogs);

			JSONArray logsArray = new JSONArray();
			for (RetentionMigrationLog logEntry : logs) {
				JSONObject logJson = new JSONObject();
				logJson.put("id", logEntry.getId());
				logJson.put("jobType", logEntry.getJobType());
				logJson.put("repositoryId", logEntry.getRepositoryId());
				if (logEntry.getStartedAt() != null) {
					logJson.put("startedAt", logEntry.getStartedAt().getTimeInMillis());
				}
				if (logEntry.getCompletedAt() != null) {
					logJson.put("completedAt", logEntry.getCompletedAt().getTimeInMillis());
				}
				logJson.put("processed", logEntry.getProcessed());
				logJson.put("succeeded", logEntry.getSucceeded());
				logJson.put("failed", logEntry.getFailed());
				logJson.put("status", logEntry.getStatus());
				logJson.put("details", logEntry.getDetails());
				logsArray.add(logJson);
			}
			result.put("logs", logsArray);
		} catch (Exception e) {
			log.error("Failed to get migration logs", e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}

		return makeResult(status, result, errMsg).toJSONString();
	}

	private PropertyManager getPropertyManager() {
		if (propertyManager != null) {
			return propertyManager;
		}
		try {
			return SpringContext.getApplicationContext().getBean("propertyManager", PropertyManager.class);
		} catch (Exception e) {
			log.error("Failed to get PropertyManager from SpringContext: " + e.getMessage());
			return null;
		}
	}

	private RetentionLogDaoService getRetentionLogDaoService() {
		if (retentionLogDaoService != null) {
			return retentionLogDaoService;
		}
		try {
			return SpringContext.getApplicationContext().getBean("retentionLogDaoService", RetentionLogDaoService.class);
		} catch (Exception e) {
			log.debug("RetentionLogDaoService not available: " + e.getMessage());
			return null;
		}
	}

	private ThreadLockService getThreadLockService() {
		if (threadLockService != null) {
			return threadLockService;
		}
		try {
			return SpringContext.getApplicationContext().getBean("threadLockService", ThreadLockService.class);
		} catch (Exception e) {
			log.debug("ThreadLockService not available: " + e.getMessage());
			return null;
		}
	}

	private NemakiCachePool getNemakiCachePool() {
		if (nemakiCachePool != null) {
			return nemakiCachePool;
		}
		try {
			return SpringContext.getApplicationContext().getBean("nemakiCachePool", NemakiCachePool.class);
		} catch (Exception e) {
			log.debug("NemakiCachePool not available: " + e.getMessage());
			return null;
		}
	}

	private LongTermStorageAdapterFactory getLongTermStorageAdapterFactory() {
		if (longTermStorageAdapterFactory != null) {
			return longTermStorageAdapterFactory;
		}
		try {
			return SpringContext.getApplicationContext().getBean("longTermStorageAdapterFactory", LongTermStorageAdapterFactory.class);
		} catch (Exception e) {
			log.debug("LongTermStorageAdapterFactory not available: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get pending archives - expired documents that are still in the live DB (Admin only).
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Path("/pending-archives")
	@Produces(MediaType.APPLICATION_JSON)
	public String getPendingArchives(
			@PathParam("repositoryId") String repositoryId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		try {
			GregorianCalendar now = new GregorianCalendar();
			List<Content> expiredDocs = getContentService().getExpiredDocuments(repositoryId, now);

			JSONArray pendingArray = new JSONArray();
			for (Content doc : expiredDocs) {
				JSONObject docJson = new JSONObject();
				docJson.put("id", doc.getId());
				docJson.put("name", doc.getName());
				docJson.put("parentId", doc.getParentId());
				docJson.put("lastModifiedBy", doc.getModifier());

				// Extract expirationDate from subTypeProperties
				if (doc.getSubTypeProperties() != null) {
					for (jp.aegif.nemaki.model.Property prop : doc.getSubTypeProperties()) {
						if ("cmis:rm_expirationDate".equals(prop.getKey())) {
							Object val = prop.getValue();
							if (val instanceof Number) {
								GregorianCalendar expCal = new GregorianCalendar();
								expCal.setTimeInMillis(((Number) val).longValue());
								docJson.put("expirationDate", DateUtil.formatSystemDateTime(expCal));
							} else if (val != null) {
								docJson.put("expirationDate", val.toString());
							}
							break;
						}
					}
				}

				pendingArray.add(docJson);
			}
			result.put("pendingArchives", pendingArray);
		} catch (Exception e) {
			log.error("Failed to get pending archives", e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
		}

		return makeResult(status, result, errMsg).toJSONString();
	}

	/**
	 * Force archive an expired document (Admin only).
	 */
	@SuppressWarnings("unchecked")
	@POST
	@Path("/force-archive/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String forceArchive(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		ThreadLockService lockService = getThreadLockService();
		NemakiCachePool cachePool = getNemakiCachePool();
		if (lockService == null) {
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_GET_ARCHIVES);
			return makeResult(status, result, errMsg).toJSONString();
		}

		Lock lock = lockService.getWriteLock(repositoryId, objectId);
		lock.lock();
		try {
			CallContext systemContext = new SystemCallContext(repositoryId);
			// Use deleteDocument to properly handle attachments, renditions, and version series
			getContentService().deleteDocument(systemContext, repositoryId, objectId, true, false);
			if (cachePool != null) {
				cachePool.get(repositoryId).removeCmisCache(objectId);
			}
		} catch (Exception e) {
			log.error("Failed to force-archive document " + objectId, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_DESTROY);
		} finally {
			lock.unlock();
		}

		return makeResult(status, result, errMsg).toJSONString();
	}

	/**
	 * Extend the expiration date of a document (Admin only).
	 */
	@SuppressWarnings("unchecked")
	@PUT
	@Path("/extend-expiration/{objectId}")
	@Produces(MediaType.APPLICATION_JSON)
	public String extendExpiration(
			@PathParam("repositoryId") String repositoryId,
			@PathParam("objectId") String objectId,
			@QueryParam("newExpirationDate") String newExpirationDate,
			@Context HttpServletRequest httpRequest) {
		boolean status = true;
		JSONObject result = new JSONObject();
		JSONArray errMsg = new JSONArray();

		status = checkAdmin(errMsg, httpRequest);
		if (!status) {
			return makeResult(status, result, errMsg).toJSONString();
		}

		if (newExpirationDate == null || newExpirationDate.trim().isEmpty()) {
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_MANDATORY);
			return makeResult(status, result, errMsg).toJSONString();
		}

		try {
			java.time.Instant instant = java.time.Instant.parse(newExpirationDate);
			GregorianCalendar newDate = new GregorianCalendar();
			newDate.setTimeInMillis(instant.toEpochMilli());
			getContentService().updateExpirationDate(repositoryId, objectId, newDate);

			NemakiCachePool cachePool = getNemakiCachePool();
			if (cachePool != null) {
				cachePool.get(repositoryId).removeCmisCache(objectId);
			}
		} catch (java.time.format.DateTimeParseException e) {
			log.error("Invalid date format: " + newExpirationDate, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_MANDATORY);
		} catch (Exception e) {
			log.error("Failed to extend expiration for " + objectId, e);
			status = false;
			addErrMsg(errMsg, ITEM_ARCHIVE, ErrorCode.ERR_UPDATE);
		}

		return makeResult(status, result, errMsg).toJSONString();
	}


	@SuppressWarnings({ "unchecked" })
	private JSONObject buildArchiveJson(Archive archive){

		JSONObject archiveJson = new JSONObject();
		archiveJson.put("id", archive.getId());
		archiveJson.put("type", archive.getType());
		archiveJson.put("name", archive.getName());
		archiveJson.put("originalId", archive.getOriginalId());
		archiveJson.put("parentId", archive.getParentId());
		archiveJson.put("isDeletedWithParent", archive.isDeletedWithParent());
		try{
			String _created = DateUtil.formatSystemDateTime(archive.getCreated());
			archiveJson.put("created", _created);
		}catch(Exception e){
			log.warn(String.format("Archive(%s) 'created' property is broken.", archive.getId()));
		}
		archiveJson.put("creator", archive.getCreator());
		// Additional fields for UI display
		if (archive.getPath() != null) {
			archiveJson.put("path", archive.getPath());
		}
		if (archive.getMimeType() != null) {
			archiveJson.put("mimeType", archive.getMimeType());
		}
		if (archive.getContentStreamLength() != null) {
			archiveJson.put("contentLength", archive.getContentStreamLength());
		}
		// Retention lifecycle fields
		archiveJson.put("archiveState", archive.getEffectiveArchiveState());
		if (archive.getArchivedAt() != null) {
			try {
				archiveJson.put("archivedAt", DateUtil.formatSystemDateTime(archive.getArchivedAt()));
			} catch (Exception e) {
				// ignore
			}
		}
		if (archive.getColdArchivedAt() != null) {
			try {
				archiveJson.put("coldArchivedAt", DateUtil.formatSystemDateTime(archive.getColdArchivedAt()));
			} catch (Exception e) {
				// ignore
			}
		}
		if (archive.getColdMoveMode() != null) {
			archiveJson.put("coldMoveMode", archive.getColdMoveMode());
		}
		return archiveJson;
	}
}
