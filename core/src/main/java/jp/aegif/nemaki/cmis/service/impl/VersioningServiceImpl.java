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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.cache.NemakiCache;
import jp.aegif.nemaki.util.constant.DomainType;

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
	private NemakiCache nemakiCache;

	@Override
	/**
	 * Repository only allow the latest version to be checked out
	 */
	public void checkOut(CallContext callContext, Holder<String> objectId,
			ExtensionsData extension, Holder<Boolean> contentCopied) {
		// //////////////////
		// General Exception
		// //////////////////
		String id = objectId.getValue();
		exceptionService.invalidArgumentRequiredString("objectId", id);
		Document document = contentService.getDocument(id);
		exceptionService.objectNotFound(DomainType.OBJECT, document, id);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CHECKOUT_DOCUMENT, document);

		// //////////////////
		// Specific Exception
		// //////////////////
		// CMIS doesn't define the error type when checkOut is performed
		// repeatedly
		exceptionService.constraintAlreadyCheckedOut(document);
		exceptionService.constraintVersionable(document.getObjectType());
		exceptionService.versioning(document);

		// //////////////////
		// Body of the method
		// //////////////////
		Document pwc = contentService.checkOut(callContext, id, extension);
		objectId.setValue(pwc.getId());
		Holder<Boolean> copied = new Holder<Boolean>(true);
		contentCopied = copied;
		
		nemakiCache.removeCmisCache(id);
	}

	@Override
	public void cancelCheckOut(CallContext callContext, String objectId,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Document document = contentService.getDocument(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, document, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CHECKIN_DOCUMENT, document);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintVersionable(document.getObjectType());

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.cancelCheckOut(callContext, objectId, extension);
		
		VersionSeries vs = contentService.getVersionSeries(document);
		Document latest = contentService.getDocumentOfLatestVersion(vs.getId());
		nemakiCache.removeCmisCache(objectId);
		nemakiCache.removeCmisCache(latest.getId());
	}

	@Override
	public void checkIn(CallContext callContext, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		String id = objectId.getValue();
		exceptionService.invalidArgumentRequiredString("objectId", id);
		Document document = contentService.getDocument(id);
		exceptionService.objectNotFound(DomainType.OBJECT, document, id);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT, document);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintVersionable(document.getObjectType());
		// TODO implement
		// exceptionService.streamNotSupported(documentTypeDefinition,
		// contentStream);

		// //////////////////
		// Body of the method
		// //////////////////
		Document checkedIn = contentService.checkIn(callContext, objectId,
				major, properties, contentStream, checkinComment, policies,
				addAces, removeAces, extension);
		objectId.setValue(checkedIn.getId());
		
		nemakiCache.removeCmisCache(id);
	}

	@Override
	public ObjectData getObjectOfLatestVersion(CallContext context,
			String objectId, String versionSeriesId, Boolean major,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// Chemistry Atompub calls this method only with objectId
		if (versionSeriesId == null) {
			exceptionService
					.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(objectId);
			versionSeriesId = d.getVersionSeriesId();
		}

		// Default to false
		Boolean _major = (major == null) ? false : major;
		Document document = null;
		if (_major) {
			document = contentService
					.getDocumentOfLatestMajorVersion(versionSeriesId);
		} else {
			document = contentService
					.getDocumentOfLatestVersion(versionSeriesId);
		}
		exceptionService.objectNotFound(DomainType.OBJECT, document,
				versionSeriesId);
		exceptionService.permissionDenied(context,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, document);

		// //////////////////
		// Body of the method
		// //////////////////
		ObjectData objectData = compileService.compileObjectData(context,
				document, filter, includeAllowableActions,
				includeRelationships, renditionFilter, includeAcl);
		return objectData;
	}

	@Override
	public List<ObjectData> getAllVersions(CallContext context,
			String objectId, String versionSeriesId, String filter,
			Boolean includeAllowableActions, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// CMIS spec needs versionSeries as required, but Chemistry also takes
		// objectId
		if (StringUtils.isBlank(versionSeriesId)) {
			exceptionService
					.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(objectId);
			versionSeriesId = d.getVersionSeriesId();
		}
		
		List<Document> allVersions = contentService
				.getAllVersions(context, versionSeriesId);
		exceptionService.objectNotFoundVersionSeries(versionSeriesId,
				allVersions);
		// Sort by the descending order
		Collections.sort(allVersions, new VersionComparator());
	
		//Permissions filter
		/*exceptionService.permissionDenied(context,
				PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES,
				allVersions.get(0));
		 */
		Document latest = allVersions.get(0);
		if(latest.isPrivateWorkingCopy()){
			VersionSeries vs = contentService.getVersionSeries(latest);
			if(!context.getUsername().equals(vs.getVersionSeriesCheckedOutBy())){
				allVersions.remove(latest);
			}
		}
		
		// //////////////////
		// Body of the method
		// //////////////////
		List<ObjectData> result = new ArrayList<ObjectData>();
		for (Content content : allVersions) {
			ObjectData objectData = compileService.compileObjectData(
					context, content, filter, includeAllowableActions,
					IncludeRelationships.NONE, null, true);
			result.add(objectData);
		}

		return result;
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

	public void setNemakiCache(NemakiCache nemakiCache) {
		this.nemakiCache = nemakiCache;
	}
}
