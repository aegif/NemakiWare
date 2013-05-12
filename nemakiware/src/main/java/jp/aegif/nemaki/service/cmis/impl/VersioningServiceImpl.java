package jp.aegif.nemaki.service.cmis.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.VersioningService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.springframework.util.CollectionUtils;

public class VersioningServiceImpl implements VersioningService {

	private ContentService contentService;
	private CompileObjectService compileObjectService;
	private ExceptionService exceptionService;
	
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
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_CHECKOUT_DOCUMENT, document);
		
		// //////////////////
		// Specific Exception
		// //////////////////
		//CMIS doesn't define the error type when checkOut is performed repeatedly
		exceptionService.constraintAlreadyCheckedOut(document);
		exceptionService.constraintVersionable(document.getObjectType());
		exceptionService.versioning(document);
		
		// //////////////////
		// Body of the method
		// //////////////////
		boolean copied = (contentCopied != null && contentCopied.getValue() != null) ? contentCopied.getValue() : true;
		contentService.checkOut(callContext, id, extension, copied);
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
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_CHECKIN_DOCUMENT, document);
		
		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintVersionable(document.getObjectType());
		exceptionService.versioning(document);
		
		// //////////////////
		// Body of the method
		// //////////////////		
		contentService.cancelCheckOut(callContext, objectId, extension);
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
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT, document);		
		
		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintVersionable(document.getObjectType());
		//TODO implement
		//exceptionService.streamNotSupported(documentTypeDefinition, contentStream);
		
		// //////////////////
		// Body of the method
		// //////////////////
		//TODO id? objectId?
		contentService.checkIn(callContext, objectId, major, properties, contentStream, checkinComment, policies, addAces, removeAces, extension);
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
		//Chemistry Atompub calls this method only with objectId
		if(versionSeriesId == null){
			exceptionService.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(objectId);
			versionSeriesId = d.getVersionSeriesId();
		}
		Document document = contentService.getDocumentOfLatestVersion(versionSeriesId);
		exceptionService.objectNotFound(DomainType.OBJECT, document, versionSeriesId);
		exceptionService.permissionDenied(context, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, document);
		
		// //////////////////
		// Body of the method
		// //////////////////
		ObjectData objectData = compileObjectService.compileObjectData(context, document, filter, includeAllowableActions, includeAcl);
		return objectData;
	}

	@Override
	public List<ObjectData> getAllVersions(CallContext context,
			String objectId, String versionSeriesId, String filter,
			Boolean includeAllowableActions, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		//CMIS spec needs versionSeries as required, but Chemistry also takes objectId
		if(versionSeriesId == null){
			exceptionService.invalidArgumentRequiredString("objectId", objectId);
			Document d = contentService.getDocument(objectId);
			versionSeriesId = d.getVersionSeriesId();
		}
		List<Document> allVersions = contentService.getAllVersions(versionSeriesId);
		exceptionService.objectNotFoundVersionSeries(versionSeriesId, allVersions);
		//Sort by the descending order
		Collections.sort(allVersions, new VersionComparator());
		exceptionService.permissionDenied(context, PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES, allVersions.get(0));
		

		// //////////////////
		// Body of the method
		// //////////////////
		List<ObjectData> result = new ArrayList<ObjectData>();
		for(Content content : allVersions){
			ObjectData objectData = compileObjectService.compileObjectData(context, content, filter, includeAllowableActions, true);
			result.add(objectData);
		}
		
		return result;
	}
	
	/**
	 * Descending order by cmis:creationDate 
	 * @author linzhixing
	 */
	private class VersionComparator implements Comparator<Content>{
		public int compare(Content content0, Content content1) {
			GregorianCalendar created0 = content0.getCreated();
			GregorianCalendar created1 = content1.getCreated();
			
			if(created0.before(created1)){
				return 1;
			}else if(created0.after(created1)){
				return -1;
			}else{
				return 0;
			}
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setCompileObjectService(CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}
}
