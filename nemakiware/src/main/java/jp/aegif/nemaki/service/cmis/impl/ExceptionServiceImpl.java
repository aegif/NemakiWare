package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.ObjectService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import org.apache.chemistry.opencmis.commons.data.Properties;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyDecimal;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tools.ant.types.resources.selectors.InstanceOf;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class ExceptionServiceImpl implements ExceptionService,
		ApplicationContextAware {
	private TypeManager typeManager;
	private ContentService contentService;
	private PermissionService permissionService;
	private NemakiRepositoryInfoImpl repositoryInfo;

	private final BigInteger HTTP_STATUS_CODE_400 = BigInteger.valueOf(400);
	private final BigInteger HTTP_STATUS_CODE_403 = BigInteger.valueOf(403);
	private final BigInteger HTTP_STATUS_CODE_404 = BigInteger.valueOf(404);
	private final BigInteger HTTP_STATUS_CODE_405 = BigInteger.valueOf(405);
	private final BigInteger HTTP_STATUS_CODE_409 = BigInteger.valueOf(409);
	private final BigInteger HTTP_STATUS_CODE_500 = BigInteger.valueOf(500);

	private static final Log logger = LogFactory
			.getLog(ObjectServiceImpl.class);

	@Override
	public void invalidArgument(String msg) {
		throw new CmisInvalidArgumentException(msg, HTTP_STATUS_CODE_400);
	}

	@Override
	public void invalidArgumentRequired(String argumentName) {
		throw new CmisInvalidArgumentException(argumentName + " must be set",
				HTTP_STATUS_CODE_400);
	}

	@Override
	public void invalidArgumentRequired(String argumentName, Object argument) {
		if (argument == null) {
			invalidArgumentRequired(argumentName);
		}
	}

	@Override
	public void invalidArgumentRequiredString(String argumentName,
			String argument) {
		if (isEmptyString(argument)) {
			invalidArgumentRequired(argumentName);
		}
	}

	@Override
	public void invalidArgumentRequiredHolderString(String argumentName,
			Holder<String> argument) {
		if (argument == null || isEmptyString(argument.getValue())) {
			invalidArgumentRequired(argumentName);
		}

	}

	@Override
	public void invalidArgumentRootFolder(Folder folder) {
		if (folder.isRoot())
			invalidArgument("Cannot specify the root folder as an input parameter");
	}

	@Override
	public void invalidArgumentFolderId(Folder folder, String folderId) {
		if (folder == null) {
			String msg = "This objectId is not a folder id";
			invalidArgument(buildMsgWithId(msg, folderId));
		}
	}

	@Override
	public void invalidArgumentDepth(BigInteger depth) {
		if (depth == BigInteger.ZERO) {
			invalidArgument("Depth must not be zero");
		} else if (depth == BigInteger.valueOf(-1)) {
			invalidArgument("Depth must not be less than -1");
		}
	}

	private boolean isEmptyString(String s) {
		if (s != null && s.length() != 0) {
			return false;
		} else {
			return true;
		}
	}

	@Override
	public void invalidArgumentRequiredCollection(String argumentName,
			Collection collection) {
		if (CollectionUtils.isEmpty(collection))
			invalidArgument(argumentName);
	}

	@Override
	public void invalidArgumentRequiredParentFolderId(String folderId) {
		if (!repositoryInfo.getCapabilities().isMultifilingSupported())
			invalidArgumentRequiredString("folderId", folderId);
	}

	@Override
	public void invalidArgumentOrderBy(String orderBy) {
		if (repositoryInfo.getCapabilities().getOrderByCapability() == CapabilityOrderBy.NONE
				&& orderBy != null)
			invalidArgument("OrderBy capability is not supported");
	}

	@Override
	public void invalidArgumentChangeEventNotAvailable(
			Holder<String> changeLogToken) {
		if (changeLogToken != null && changeLogToken.getValue() != null) {
			Change change = contentService.getChangeEvent(changeLogToken
					.getValue());
			if (change == null)
				invalidArgument("changeLogToken:" + changeLogToken.getValue()
						+ " is no longer available");
		}

	}

	@Override
	public void objectNotFound(DomainType type, Object object, String id,
			String msg) {
		if (object == null)
			throw new CmisObjectNotFoundException(msg, HTTP_STATUS_CODE_404);
	}

	@Override
	public void objectNotFound(DomainType type, Object object, String id) {
		String msg = "[" + type.value() + "Id:" + id + "]"
				+ "The specified object is not found";
		objectNotFound(type, object, id, msg);
	}

	@Override
	public void objectNotFoundVersionSeries(String id, Collection collection) {
		if (CollectionUtils.isEmpty(collection)) {
			String msg = "[VersionSeriesId:" + id + "]"
					+ "The specified version series is not found";
			throw new CmisObjectNotFoundException(msg, HTTP_STATUS_CODE_404);
		}
	}

	@Override
	public void objectNotFoundParentFolder(String id, Content content) {
		if (!!repositoryInfo.getCapabilities().isMultifilingSupported())
			objectNotFound(DomainType.OBJECT, content, id,
					"The specified parent folder is not found");
	}

	/**
	 * 
	 */
	// TODO Show also stack errors
	@Override
	public void permissionDenied(CallContext context, String key,
			ObjectData object) {
		Content content = contentService.getContentAsTheBaseType(object.getId());
		permissionDeniedInternal(context, key, object.getAcl(),
				getBaseTypeId(object.getProperties()), content);
	}

	@Override
	public void permissionDenied(CallContext context, String key,
			Content content) {
		String baseTypeId = content.getType();
		Acl acl = contentService.convertToCmisAcl(content, false);
		permissionDeniedInternal(context, key, acl, baseTypeId, content);
	}

	private void permissionDeniedInternal(CallContext callContext, String key,
			Acl acl, String baseTypeId, Content content) {
		if (!permissionService.checkPermission(callContext, key, acl,
				baseTypeId, content)) {
			String msg = "Permission Denied!";
			throw new CmisPermissionDeniedException(msg, HTTP_STATUS_CODE_403);
		}
	}

	/**
	 * 
	 * NOTE:Check the condition before calling this method
	 */
	@Override
	public void constraint(String objectId, String msg) {
		throw new CmisConstraintException(buildMsgWithId(msg, objectId),
				HTTP_STATUS_CODE_409);
	}

	@Override
	public void constraintBaseTypeId(Properties properties,
			BaseTypeId baseTypeId) {
		String objectTypeId = getTypeId(properties);
		TypeDefinition td = typeManager.getType(objectTypeId);

		if (!td.getBaseTypeId().equals(baseTypeId))
			constraint(null,
					"cmis:objectTypeId is not an object type whose base tyep is "
							+ baseTypeId);
	}

	@Override
	public void constraintAllowedChildObjectTypeId(Folder folder,
			Properties childProperties) {

		List<String> allowedTypes = folder.getAllowedChildTypeIds();
		if (!CollectionUtils.isEmpty(allowedTypes)) {
			// NOTE: Elements of allowedTypes must be like "cmis:folder", not
			// "folder"
			String childType = getStringProperty(childProperties,
					PropertyIds.OBJECT_TYPE_ID);
			if (!allowedTypes.contains(childType)) {
				String objectId = getStringProperty(childProperties,
						PropertyIds.OBJECT_ID);
				constraint(
						objectId,
						"cmis:objectTypeId property value is NOT in the list of AllowedChildOb-jectTypeIds of the parent-folder");
			}
		}
	}

	@Override
	public void constraintPropertyValue(Properties properties) {
		String objectId = getObjectId(properties);
		String objectTypeId = getObjectTypeId(properties);
		Map<String, PropertyDefinition<?>> definitions = typeManager.getType(
				objectTypeId).getPropertyDefinitions();
		for (PropertyData<?> pd : properties.getPropertyList()) {
			PropertyDefinition<?> definition = definitions.get(pd.getId());
			// If an input property is not defined one, output error.
			if (definition == null)
				constraint(objectId, "An undefined property is provided!");

			// Check for "required" flag
			if (definition.isRequired()
					&& CollectionUtils.isEmpty(pd.getValues()))
				constraint(objectId, "An required property is not provided!");

			// Check for min/max length
			switch (definition.getPropertyType()) {
			case STRING:
				constraintStringPropertyValue(definition, pd, objectId);
				break;
			case DECIMAL:
				constraintDecimalPropertyValue(definition, pd, objectId);
			case INTEGER:
				constraintIntegerPropertyValue(definition, pd, objectId);
				break;
			default:
				break;
			}
		}
	}

	private void constraintIntegerPropertyValue(
			PropertyDefinition<?> definition, PropertyData<?> propertyData,
			String objectId) {
		final String msg = "An INTEGER property violates the range constraints";
		BigInteger val = BigInteger
				.valueOf((Long) propertyData.getFirstValue());

		BigInteger min = ((PropertyIntegerDefinition) definition).getMinValue();
		if (min != null && min.compareTo(val) > 0) {
			constraint(objectId, msg);
		}

		BigInteger max = ((PropertyIntegerDefinition) definition).getMinValue();
		if (max != null && max.compareTo(val) < 0) {
			constraint(objectId, msg);
		}
	}

	private void constraintDecimalPropertyValue(
			PropertyDefinition<?> definition, PropertyData<?> propertyData,
			String objectId) {
		final String msg = "An DECIMAL property violates the range constraints";

		if (!(propertyData instanceof PropertyDecimal))
			return;
		BigDecimal val = ((PropertyDecimal) propertyData).getFirstValue();

		BigDecimal min = ((PropertyDecimalDefinition) definition).getMinValue();
		if (min != null && min.compareTo(val) > 0) {
			constraint(objectId, msg);
		}

		BigDecimal max = ((PropertyDecimalDefinition) definition).getMaxValue();
		if (max != null && max.compareTo(val) > 0) {
			constraint(objectId, msg);
		}
	}

	private void constraintStringPropertyValue(
			PropertyDefinition<?> definition, PropertyData<?> propertyData,
			String objectId) {
		final String msg = "An STRING property violates the length constraints";
		if (!(propertyData instanceof PropertyString))
			return;
		String val = ((PropertyString) propertyData).getFirstValue();
		if (StringUtils.isBlank(val)){
			constraint(objectId, msg);
		}
		
		BigInteger length = BigInteger.valueOf(val.length());

		BigInteger max = ((PropertyStringDefinition) definition).getMaxLength();

		if (max != null && max.compareTo(length) < 0) {
			constraint(objectId, msg);
		}
	}

	@Override
	public void constraintControllableVersionable(
			DocumentTypeDefinition documentTypeDefinition,
			VersioningState versioningState, String objectId) {
		if (!documentTypeDefinition.isVersionable()
				&& (versioningState != null && versioningState != VersioningState.NONE)) {
			String msg = "Versioning state is not set for a versionable object-type";
			throw new CmisConstraintException(buildMsgWithId(msg, objectId),
					HTTP_STATUS_CODE_409);
		}
		if (documentTypeDefinition.isVersionable()
				&& (versioningState == null || versioningState == VersioningState.NONE)) {
			String msg = "Versioning state is set for a non-versionable object-type";
			throw new CmisConstraintException(buildMsgWithId(msg, objectId),
					HTTP_STATUS_CODE_409);
		}

	}

	@Override
	public void constraintCotrollablePolicies(TypeDefinition typeDefinition,
			List<String> policies, Properties properties) {
		if (!typeDefinition.isControllablePolicy()
				&& !CollectionUtils.isEmpty(policies)) {
			String msg = "Policies cannnot be provided to a non-controllablePolicy object-type";
			constraint(getObjectId(properties), msg);
		}
	}

	@Override
	public void constraintCotrollableAcl(TypeDefinition typeDefinition,
			Acl addAces, Acl removeAces, Properties properties) {
		// TODO ignore removeAces?
		boolean aclIsEmpty = (addAces == null)
				|| (addAces != null && CollectionUtils.isEmpty(addAces
						.getAces())) ? true : false;
		if (!typeDefinition.isControllableAcl() && !aclIsEmpty) {
			constraint(getObjectId(properties),
					"Acl cannnot be provided to a non-controllableAcl object-type");
		}
	}

	@Override
	public void constraintPermissionDefined(Acl acl, String objectId) {
		boolean aclIsEmpty = (acl == null)
				|| (acl != null && CollectionUtils.isEmpty(acl.getAces())) ? true
				: false;
		List<String> nemakiPermissions = permissionService.getPermissions();

		if (!aclIsEmpty) {
			for (Ace ace : acl.getAces()) {
				List<String> permissions = ace.getPermissions();
				for (String p : permissions) {
					if (!nemakiPermissions.contains(p)) {
						constraint(objectId,
								"A provided ACE includes a permission not supported by the repository");
					}
				}
			}
		}
	}

	@Override
	public void constraintAllowedSourceTypes(
			RelationshipTypeDefinition relationshipTypeDefinition,
			Content source) {
		if (!relationshipTypeDefinition.getAllowedSourceTypeIds().contains(
				(source.getObjectType())))
			constraint(source.getId(),
					"The source object's type is not allowed for the relationship");
	}

	@Override
	public void constraintAllowedTargetTypes(
			RelationshipTypeDefinition relationshipTypeDefinition,
			Content target) {
		if (!relationshipTypeDefinition.getAllowedTargetTypeIds().contains(
				target.getObjectType()))
			constraint(target.getId(),
					"The target object's type is not allowed for the relationship");
	}

	@Override
	public void constraintVersionable(String typeId) {
		DocumentTypeDefinition type = (DocumentTypeDefinition) typeManager
				.getType(typeId);
		if (!type.isVersionable()) {
			String msg = "Object type: " + type.getId() + " is not versionbale";
			throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
		}

	}

	@Override
	public void constraintAlreadyCheckedOut(Document document) {
		VersionSeries vs = contentService.getVersionSeries(document
				.getVersionSeriesId());
		if (vs.isVersionSeriesCheckedOut()) {
			if (!(document.isPrivateWorkingCopy() && repositoryInfo
					.getCapabilities().isPwcUpdatableSupported())) {
				constraint(document.getId(),
						"The version series is alredy checked out");
			}
		}
	}

	@Override
	public void constraintAclPropagationDoesNotMatch(
			AclPropagation aclPropagation) {
		if (aclPropagation == AclPropagation.OBJECTONLY)
			throw new CmisConstraintException(
					"The repository doesn't support this AclPropagation",
					HTTP_STATUS_CODE_409);
	}

	@Override
	public void constraintContentStreamRequired(Document document) {
		String objectTypeId = document.getObjectType();
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager.getType(objectTypeId);
		if(td.getContentStreamAllowed() == ContentStreamAllowed.REQUIRED){
			if(document.getAttachmentNodeId() == null || 
					contentService.getAttachment(document.getAttachmentNodeId()) == null){
				constraint(document.getId(), "This document type does not allow no content stream");
			}
		}
	}

	/**
	 * 	
	 */
	@Override
	public void contentAlreadyExists(Content content, Boolean overwriteFlag) {
		if (!overwriteFlag) {
			Document document = (Document) content; // FIXME
			String attachmentNodeId = document.getAttachmentNodeId(); // FIXME
																		// getAttachmentNodes

			if (attachmentNodeId != null) {
				String msg = "Can't overwrite the content stream when overwriteFlag is false";
				throw new CmisContentAlreadyExistsException(buildMsgWithId(msg,
						content.getId()), HTTP_STATUS_CODE_409);
			}
		}
	}

	/**
	 * 
	 */
	@Override
	public void streamNotSupported(
			DocumentTypeDefinition documentTypeDefinition,
			ContentStream contentStream) {
		if (documentTypeDefinition.getContentStreamAllowed() == ContentStreamAllowed.NOTALLOWED
				&& contentStream != null) {
			String msg = "A Content stream must not be included";
			throw new CmisStreamNotSupportedException(msg, HTTP_STATUS_CODE_403);
		}
	}

	/**
	 * 
	 */
	@Override
	public void versioning(Document doc) {
		if (!doc.isLatestVersion()) {
			String msg = "The operation is not allowed on a non-current version of a document";
			throw new CmisVersioningException(buildMsgWithId(msg, doc.getId()),
					HTTP_STATUS_CODE_409);
		}
	}

	// TODO implement!
	@Override
	public void nameConstraintViolation(Properties properties,
			Folder parentFolder) {
		// If name conflicts, modify names by the repository without outputting
		// error
		if (parentFolder == null) {

		} else {

		}
	}

	@Override
	public void updateConflict(Content content, Holder<String> changeToken) {
		int latestOfContent = content.getChangeToken();
		
		if ((changeToken == null || changeToken.getValue() == null) && latestOfContent != 0) {
			throw new CmisUpdateConflictException(
					"Change token is required to update", HTTP_STATUS_CODE_409);
		} else {
			// NOTE: 0 means somewhat "not set" for system use
			if (latestOfContent == 0) {
				//Do nothing
				//TODO logging? 
			} else {
				if (String.valueOf(latestOfContent).equals(changeToken))
					throw new CmisUpdateConflictException(
							"Cannot update because the changeToken conflicts",
							HTTP_STATUS_CODE_409);
			}
		}

	}

	private String buildMsgWithId(String msg, String objectId) {
		if (objectId == null)
			objectId = "";
		return msg + " [cmis:objectId = " + objectId + "]";
	}

	/**
	 * Gets the type id from a set of properties.
	 */
	private String getTypeId(Properties properties) {
		PropertyData<?> typeProperty = properties.getProperties().get(
				PropertyIds.OBJECT_TYPE_ID);
		if (!(typeProperty instanceof PropertyId)) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		String typeId = ((PropertyId) typeProperty).getFirstValue();
		if (typeId == null) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		return typeId;
	}

	/**
	 * Reads a given property from a set of properties.
	 */
	private String getStringProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyString)) {
			return null;
		}

		return ((PropertyString) property).getFirstValue();
	}

	private String getIdProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getFirstValue();
	}

	private String getObjectId(Properties properties) {
		return getIdProperty(properties, PropertyIds.OBJECT_ID);
	}

	private String getObjectTypeId(Properties properties) {
		return getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
	}

	private String getBaseTypeId(Properties properties) {
		return getIdProperty(properties, PropertyIds.BASE_TYPE_ID);
	}

	/**
	 * Setter
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}

	public void setRepositoryInfo(NemakiRepositoryInfoImpl repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

}
