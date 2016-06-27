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
package jp.aegif.nemaki.cmis.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.concurrent.locks.Lock;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.lang.StringUtils;

public class VersioningServiceImpl implements VersioningService {
	private ContentService contentService;
	private CompileService compileService;
	private ExceptionService exceptionService;
	private NemakiCachePool nemakiCachePool;
	private ThreadLockService threadLockService;

	@Override
	/**
	 * Repository only allow the latest version to be checked out
	 */
	public void checkOut(CallContext callContext, String repositoryId,
			Holder<String> objectId, Holder<Boolean> contentCopied, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);
		String originalId = objectId.getValue();
		
		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		
		try{
			lock.lock();
			nemakiCachePool.get(repositoryId).removeCmisCache(originalId);
			// //////////////////
			// General Exception
			// //////////////////
			Document document = contentService.getDocument(repositoryId, objectId.getValue());
			exceptionService.objectNotFound(DomainType.OBJECT, document, objectId.getValue());
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_CHECKOUT_DOCUMENT, document);

			// //////////////////
			// Specific Exception
			// //////////////////
			// CMIS doesn't define the error type when checkOut is performed
			// repeatedly
			exceptionService.constraintAlreadyCheckedOut(repositoryId, document);
			exceptionService.constraintVersionable(repositoryId, document.getObjectType());
			exceptionService.versioning(document);

			// //////////////////
			// Body of the method
			// //////////////////
			Document pwc = contentService.checkOut(callContext, repositoryId, objectId.getValue(), extension);
			objectId.setValue(pwc.getId());
			Holder<Boolean> copied = new Holder<Boolean>(true);
			contentCopied = copied;
			
		}finally{
			lock.unlock();
		}
	}

	@Override
	public void cancelCheckOut(CallContext callContext, String repositoryId,
			String objectId, ExtensionsData extension) {
		
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		
		Lock lock = threadLockService.getWriteLock(repositoryId, objectId);
		
		try{
			lock.lock();
			nemakiCachePool.get(repositoryId).removeCmisCache(objectId);
			// //////////////////
			// General Exception
			// //////////////////
			Document document = contentService.getDocument(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, document, objectId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_CHECKIN_DOCUMENT, document);

			// //////////////////
			// Specific Exception
			// //////////////////
			exceptionService.constraintVersionable(repositoryId, document.getObjectType());

			// //////////////////
			// Body of the method
			// //////////////////
			contentService.cancelCheckOut(callContext, repositoryId, objectId, extension);

			//remove cache
			
			Document latest = contentService.getDocumentOfLatestVersion(repositoryId, document.getVersionSeriesId());
			//Latest document does not exit when pwc is created as the first version
			if(latest != null){
				Lock latestLock = threadLockService.getWriteLock(repositoryId, latest.getId());
				try{
					latestLock.lock();
					nemakiCachePool.get(repositoryId).removeCmisCache(latest.getId());
				}finally{
					latestLock.unlock();
				}
			}
		}finally{
			lock.unlock();
		}
	}

	@Override
	public void checkIn(CallContext callContext, String repositoryId,
			Holder<String> objectId, Boolean major, Properties properties,
			ContentStream contentStream, String checkinComment, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);
		
		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		
		try{
			lock.lock();
			
			// //////////////////
			// General Exception
			// //////////////////

			Document pwc = contentService.getDocument(repositoryId, objectId.getValue());
			nemakiCachePool.get(repositoryId).removeCmisCache(pwc.getId());
			
			exceptionService.objectNotFound(DomainType.OBJECT, pwc, objectId.getValue());
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT, pwc);

			// //////////////////
			// Specific Exception
			// //////////////////
			exceptionService.constraintVersionable(repositoryId, pwc.getObjectType());
			// TODO implement
			// exceptionService.streamNotSupported(documentTypeDefinition,
			// contentStream);

			// //////////////////
			// Body of the method
			// //////////////////
			Document checkedIn = contentService.checkIn(callContext, repositoryId,
					objectId, major, properties, contentStream, checkinComment,
					policies, addAces, removeAces, extension);
			objectId.setValue(checkedIn.getId());


			//refresh latest version
			Document latest = contentService
					.getDocumentOfLatestVersion(repositoryId, pwc.getVersionSeriesId());
			if(latest != null){
				Lock latestLock = threadLockService.getWriteLock(repositoryId, latest.getId());
				try{
					latestLock.lock();
					nemakiCachePool.get(repositoryId).removeCmisCache(latest.getId());
				}finally{
					latestLock.unlock();
				}
			}
		}finally{
			lock.unlock();
		}
	}

	@Override
	public ObjectData getObjectOfLatestVersion(CallContext callContext,
			String repositoryId, String objectId, String versionSeriesId,
			Boolean major, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePolicyIds,
			Boolean includeAcl, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// Chemistry Atompub calls this method only with objectId
		if (versionSeriesId == null) {
			exceptionService
					.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(repositoryId, objectId);
			versionSeriesId = d.getVersionSeriesId();
		}

		// Default to false
		Boolean _major = (major == null) ? false : major;
		Document document = null;
		if (_major) {
			document = contentService
					.getDocumentOfLatestMajorVersion(repositoryId, versionSeriesId);
		} else {
			document = contentService
					.getDocumentOfLatestVersion(repositoryId, versionSeriesId);
		}
		
		Lock lock = threadLockService.getReadLock(repositoryId, document.getId());
		
		try{
			lock.lock();
			
			exceptionService.objectNotFound(DomainType.OBJECT, document,
					versionSeriesId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, document);

			// //////////////////
			// Body of the method
			// //////////////////
			ObjectData objectData = compileService.compileObjectData(callContext,
					repositoryId, document, filter,
					includeAllowableActions, includeRelationships, renditionFilter, includeAcl);
			return objectData;
			
		}finally{
			lock.unlock();
		}
	}

	@Override
	public List<ObjectData> getAllVersions(CallContext callContext,
			String repositoryId, String objectId, String versionSeriesId,
			String filter, Boolean includeAllowableActions, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// CMIS spec needs versionSeries as required, but Chemistry also takes
		// objectId
		if (StringUtils.isBlank(versionSeriesId)) {
			exceptionService
					.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(repositoryId, objectId);
			versionSeriesId = d.getVersionSeriesId();
		}
		
		List<Document> allVersions = contentService
				.getAllVersions(callContext, repositoryId, versionSeriesId);
		exceptionService.objectNotFoundVersionSeries(versionSeriesId,
				allVersions);
		
		List<Lock> locks = threadLockService.readLocks(repositoryId, allVersions);
		try{
			threadLockService.bulkLock(locks);
			
			// Sort by the descending order
			Collections.sort(allVersions, new VersionComparator());
		
			Document latest = allVersions.get(0);
			if(latest.isPrivateWorkingCopy()){
				VersionSeries vs = contentService.getVersionSeries(repositoryId, latest);
				if(!callContext.getUsername().equals(vs.getVersionSeriesCheckedOutBy())){
					allVersions.remove(latest);
				}
			}
			
			// //////////////////
			// Body of the method
			// //////////////////
			List<ObjectData> result = new ArrayList<ObjectData>();
			for (Content content : allVersions) {
				ObjectData objectData = compileService.compileObjectData(
						callContext, repositoryId, content, filter,
						includeAllowableActions, IncludeRelationships.NONE, null, true);
				result.add(objectData);
			}

			return result;
			
		}finally{
			threadLockService.bulkUnlock(locks);
		}
	}

	/**
	 * Descending order by cmis:creationDate
	 *
	 * @author linzhixing
	 */
	private class VersionComparator implements Comparator<Content> {
		@Override
		public int compare(Content content0, Content content1) {
			// TODO when created time is not set
			GregorianCalendar created0 = content0.getCreated();
			GregorianCalendar created1 = content1.getCreated();

			if (created0.before(created1)) {
				return 1;
			} else if (created0.after(created1)) {
				return -1;
			} else {
				return 0;
			}
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
}
