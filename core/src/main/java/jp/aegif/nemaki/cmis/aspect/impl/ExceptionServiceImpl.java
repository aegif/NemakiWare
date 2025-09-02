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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.aspect.impl;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyDecimal;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
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
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class ExceptionServiceImpl implements ExceptionService,
		ApplicationContextAware {
	private TypeManager typeManager;
	private ContentService contentService;
	private TypeService typeService;
	private PermissionService permissionService;
	private RepositoryInfoMap repositoryInfoMap;
	private PrincipalService principalService;
	private ContentDaoService contentDaoService;
	private PropertyManager propertyManager;

	private static final Log log = LogFactory
			.getLog(ExceptionServiceImpl.class);

	private final BigInteger HTTP_STATUS_CODE_400 = BigInteger.valueOf(400);
	private final BigInteger HTTP_STATUS_CODE_403 = BigInteger.valueOf(403);
	private final BigInteger HTTP_STATUS_CODE_404 = BigInteger.valueOf(404);
	private final BigInteger HTTP_STATUS_CODE_405 = BigInteger.valueOf(405);
	private final BigInteger HTTP_STATUS_CODE_409 = BigInteger.valueOf(409);
	private final BigInteger HTTP_STATUS_CODE_500 = BigInteger.valueOf(500);

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
	public void invalidArgumentRootFolder(String repositoryId, Content content) {
		if (contentService.isRoot(repositoryId, content))
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
	public void invalidArgumentRequiredParentFolderId(String repositoryId, String folderId) {
		if (!repositoryInfoMap.get(repositoryId).getCapabilities().isMultifilingSupported())
			invalidArgumentRequiredString("folderId", folderId);
	}

	@Override
	public void invalidArgumentOrderBy(String repositoryId, String orderBy) {
		if (repositoryInfoMap.get(repositoryId).getCapabilities().getOrderByCapability() == CapabilityOrderBy.NONE
				&& orderBy != null)
			invalidArgument("OrderBy capability is not supported");
	}

	@Override
	public void invalidArgumentChangeEventNotAvailable(
			String repositoryId, Holder<String> changeLogToken) {
		if (changeLogToken != null && changeLogToken.getValue() != null) {
			Change change = contentService.getChangeEvent(repositoryId, changeLogToken
					.getValue());
			if (change == null)
				invalidArgument("changeLogToken:" + changeLogToken.getValue()
						+ " does not exist");
		}

	}

	@Override
	public void invalidArgumentCreatableType(String repositoryId, TypeDefinition type) {
		String msg = "";

		try {
			String parentId = type.getParentTypeId();
			
			String baseId = null;
			try {
				if (type.getBaseTypeId() != null) {
					baseId = type.getBaseTypeId().value();
				}
			} catch (Exception e) {
				// Error getting baseId - continue with null value
			}
			
			String typeId = type.getId();
			
			// CRITICAL FIX: For new custom types, parentId should be null and baseId should be checked
			String targetParentId = parentId;
			if (parentId == null && baseId != null) {
				// New custom type - use baseId as parent for validation
				targetParentId = baseId;
			}
			
			if (typeManager.getTypeById(repositoryId, targetParentId) == null) {
				msg = "Specified parent type does not exist";
			} else {
				TypeDefinition parent = typeManager.getTypeById(repositoryId, targetParentId)
						.getTypeDefinition();
				
				if (parent.getTypeMutability() == null) {
					msg = "Specified parent type does not have TypeMutability";
				} else {
					// CRITICAL FIX: Correctly check TypeMutability.canCreate() value instead of just checking if TypeMutability exists
					// This resolves createAndDeleteTypeTest TCK failure
					boolean canCreate = (parent.getTypeMutability() != null && parent.getTypeMutability().canCreate() != null) 
						? parent.getTypeMutability().canCreate() : false;
					if (!canCreate) {
						msg = "Specified parent type has TypeMutability.canCreate = false";
					}
				}
			}
			
		} catch (Exception e) {
			msg = "Internal error during type validation";
		}

		if (!StringUtils.isEmpty(msg)) {
			msg = msg + " [objectTypeId = " + type.getId() + "]";
			invalidArgument(msg);
		}
	}

	@Override
	public void invalidArgumentUpdatableType(TypeDefinition type) {
		String msg = "";
		TypeMutability typeMutability = type.getTypeMutability();
		boolean canUpdate = (typeMutability.canUpdate() == null) ? false
				: typeMutability.canUpdate();
		if (!canUpdate) {
			msg = "Specified type is not updatable";
			msg = msg + " [objectTypeId = " + type.getId() + "]";
			invalidArgument(msg);
		}
	}

	@Override
	public void invalidArgumentDeletableType(String repositoryId, String typeId) {
		TypeDefinition type = typeManager.getTypeDefinition(repositoryId, typeId);

		String msg = "";
		TypeMutability typeMutability = type.getTypeMutability();
		// CRITICAL FIX: 
		// 1. Variable name should be canDelete, not canUpdate
		// 2. Default should be false if null (restrictive), not true (permissive)
		// This resolves createAndDeleteTypeTest TCK failure
		boolean canDelete = (typeMutability.canDelete() == null) ? false
				: typeMutability.canDelete();
		if (!canDelete) {
			msg = "Specified type is not deletable";
			msg = msg + " [objectTypeId = " + type.getId() + "]";
			invalidArgument(msg);
		}

	}

	@Override
	public void invalidArgumentDoesNotExistType(String repositoryId, String typeId) {
		String msg = "";

		TypeDefinition type = typeManager.getTypeDefinition(repositoryId, typeId);
		
		if (type == null) {
			msg = "Specified type does not exist";
			msg = msg + " [objectTypeId = " + typeId + "]";
			invalidArgument(msg);
		}
	}

	@Override
	public void invalidArgumentSecondaryTypeIds(String repositoryId, Properties properties) {
		if (properties == null)
			return;
		Map<String, PropertyData<?>> map = properties.getProperties();
		if (MapUtils.isEmpty(map))
			return;

		List<String> results = new ArrayList<String>();
		PropertyData<?> ids = map.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
		if (ids == null || CollectionUtils.isEmpty(ids.getValues()))
			return;
		for (Object _id : ids.getValues()) {
			String id = (String) _id;
			TypeDefinitionContainer tdc = typeManager.getTypeById(repositoryId, id);
			if (tdc == null) {
				results.add(id);
			}
		}

		if (CollectionUtils.isNotEmpty(results)) {
			String msg = "Invalid cmis:SecondaryObjectTypeIds are provided:"
					+ StringUtils.join(results, ",");
			invalidArgument(msg);
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
	public void objectNotFoundByPath(DomainType type, Object object, String path,
			String msg) {
		if (object == null)
			throw new CmisObjectNotFoundException(msg, HTTP_STATUS_CODE_404);
	}

	@Override
	public void objectNotFoundByPath(DomainType type, Object object, String path) {
		String msg = "[" + type.value() + " path:" + path + "]"
				+ "The specified object is not found";
		objectNotFound(type, object, path, msg);
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
	public void objectNotFoundParentFolder(String repositoryId, String id, Content content) {
		if (!repositoryInfoMap.get(repositoryId).getCapabilities().isMultifilingSupported())
			objectNotFound(DomainType.OBJECT, content, id,
					"The specified parent folder is not found");
	}

	/**
	 *
	 */
	// TODO Show also stack errors
	@Override
	public void permissionDenied(CallContext context, String repositoryId,
			String key, Content content) {

		// CRITICAL DEBUG: Method entry point
		try {
			java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
			debugWriter.write("=== EXCEPTION SERVICE PERMISSION DENIED ENTRY ===\n");
			debugWriter.write("Timestamp: " + new java.util.Date() + "\n");
			debugWriter.write("User: " + context.getUsername() + "\n");
			debugWriter.write("Key: " + key + "\n");
			debugWriter.write("Content: " + content.getId() + "\n");
			debugWriter.close();
		} catch (Exception e) {}

		// Admin user always pass a permission check(skip calculateAcl)
		String userId = context.getUsername();
		log.debug("permissionDenied called for user=" + userId + ", key=" + key + ", content=" + content.getId());
		
		UserItem u = contentService.getUserItemById(repositoryId, userId);
		if (u != null && u.isAdmin()) {
			log.info("ExceptionServiceImpl.permissionDenied: user " + userId + " is admin, granting access");
			return;
		}

		String baseTypeId = content.getType();
		Acl acl = contentService.calculateAcl(repositoryId, content);
		log.info("ExceptionServiceImpl.permissionDenied: Calculated ACL for content " + content.getId() + ", ACL has " + (acl != null ? acl.getAllAces().size() : 0) + " ACEs");
		
		permissionDeniedInternal(context, repositoryId, key, acl, baseTypeId, content);
		permissionTopLevelFolder(context, repositoryId, key, content);
	}

	private void permissionDeniedInternal(CallContext callContext, String repositoryId,
			String key, Acl acl, String baseTypeId, Content content) {		// CRITICAL DEBUG: About to check permission in permissionDeniedInternal
		try {
			java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
			debugWriter.write("=== PERMISSION DENIED INTERNAL DEBUG ===\n");
			debugWriter.write("Timestamp: " + new java.util.Date() + "\n");
			debugWriter.write("User: " + callContext.getUsername() + "\n");
			debugWriter.write("Key: " + key + "\n");
			debugWriter.write("Content: " + content.getId() + " (" + content.getName() + ")\n");
			debugWriter.write("About to call permissionService.checkPermission()\n");
			debugWriter.close();
		} catch (Exception e) {}
		
		boolean permissionResult = permissionService.checkPermission(callContext, repositoryId, key, acl, baseTypeId, content);
		
		try {
			java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
			debugWriter.write("Permission check result: " + permissionResult + "\n");
			debugWriter.close();
		} catch (Exception e) {}
		
		if (!permissionResult) {
			String msg = String.format( "Permission Denied! repositoryId=%s key=%s acl=%s  content={id:%s, name:%s} ", repositoryId, key, acl, content.getId(), content.getName()) ;
			throw new CmisPermissionDeniedException(msg, HTTP_STATUS_CODE_403);
		}
	}

	private void permissionTopLevelFolder(CallContext context, String repositoryId, String key, Content content){
		boolean result = permissionService.checkPermissionAtTopLevel(context, repositoryId, key, content);
		if(!result){
			String msg = String.format( "Permission Denied to top level folders for non-admin user! repositoryId=%s key=%s userId=%s content={id:%s, name:%s} ", repositoryId, key, context.getUsername(), content.getId(), content.getName()) ;
			throw new CmisPermissionDeniedException(msg, HTTP_STATUS_CODE_403);
		}
	}

	@Override
	public void perimissionAdmin(CallContext context, String repositoryId) {
		if(context instanceof SystemCallContext){
			return;
		}

		UserItem userItem = contentService.getUserItemById(repositoryId, context.getUsername());
		if(!userItem.isAdmin()){
			String msg = "This operation is permitted only for administrator";
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

	private void constraint(String msg) {
		throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
	}

	@Override
	public void constraintBaseTypeId(String repositoryId,
			Properties properties, BaseTypeId baseTypeId) {
		String objectTypeId = DataUtil.getObjectTypeId(properties);
		TypeDefinition td = typeManager.getTypeDefinition(repositoryId, objectTypeId);

		if (!td.getBaseTypeId().equals(baseTypeId))
			constraint(null,
					"cmis:objectTypeId is not an object type whose base tyep is "
							+ baseTypeId);
	}

	@Override
	public void constraintAllowedChildObjectTypeId(String repositoryId, Folder folder,Properties childProperties) {
		List<String> allowedTypeIds = folder.getAllowedChildTypeIds();
		String childTypeId = DataUtil.getIdProperty(childProperties, PropertyIds.OBJECT_TYPE_ID);

		boolean result = false;
		if (CollectionUtils.isEmpty(allowedTypeIds)) {
			// If cmis:allowedChildTypeIds is not set, all types are allowed.
			result = true;
		}else{
			String targetTypeId = childTypeId;
			do{
				if (allowedTypeIds.contains(targetTypeId)) {
					result = true;
					break;
				}
				TypeDefinition targetType = typeManager.getTypeById(repositoryId, targetTypeId).getTypeDefinition();
				if (targetType.getId().equals(targetType.getBaseTypeId())) break;
				targetTypeId =  targetType.getParentTypeId();
			}while(true);
		}

		if (!result) {
			String objectId = DataUtil.getIdProperty(childProperties,PropertyIds.OBJECT_ID);
			constraint(
					objectId,
					"cmis:objectTypeId="
							+ childTypeId
							+ " is not in the list of AllowedChildObjectTypeIds of the parent folder");
		}
	}

	@Override
	public <T> void constraintPropertyValue(String repositoryId,
			TypeDefinition typeDefinition, Properties properties, String objectId) {
		Map<String, PropertyDefinition<?>> propertyDefinitions = typeDefinition
				.getPropertyDefinitions();

		// Adding secondary types and its properties MAY be done in the same
		// operation
		List<String> secIds = DataUtil.getIdListProperty(properties,
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
		if (CollectionUtils.isNotEmpty(secIds)) {
			for (String secId : secIds) {
				TypeDefinition sec = typeManager.getTypeById(repositoryId, secId)
						.getTypeDefinition();
				for (Entry<String, PropertyDefinition<?>> entry : sec
						.getPropertyDefinitions().entrySet()) {
					if (!propertyDefinitions.containsKey(entry.getKey())) {
						propertyDefinitions.put(entry.getKey(),
								entry.getValue());
					}
				}
			}
		}

		for (PropertyData<?> _pd : properties.getPropertyList()) {
			PropertyData<T> pd = (PropertyData<T>) _pd;
			PropertyDefinition<T> propertyDefinition = (PropertyDefinition<T>) propertyDefinitions
					.get(pd.getId());
			// If an input property is not defined one, output error.
			if (propertyDefinition == null)
				constraint(objectId, "An undefined property is provided!");

			// Check "required" flag
			if (propertyDefinition.isRequired()
					&& !DataUtil.valueExist(pd.getValues()))
				constraint(objectId, "An required property is not provided!");

			// Check choices
			constraintChoices(propertyDefinition, pd, objectId);

			// Check min/max length
			switch (propertyDefinition.getPropertyType()) {
			case STRING:
				constraintStringPropertyValue(propertyDefinition, pd, objectId);
				break;
			case DECIMAL:
				constraintDecimalPropertyValue(propertyDefinition, pd, objectId);
			case INTEGER:
				constraintIntegerPropertyValue(propertyDefinition, pd, objectId);
				break;
			default:
				break;
			}
		}
	}

	private <T> void constraintChoices(PropertyDefinition<T> definition,
			PropertyData<T> propertyData, String objectId) {
		// Check OpenChoice
		boolean openChoice = (definition.isOpenChoice() == null) ? true : false;
		if (openChoice)
			return;

		List<T> data = propertyData.getValues();
		// null or blank String value should be permitted within any choice list
		if (CollectionUtils.isEmpty(data))
			return;

		List<Choice<T>> choices = definition.getChoices();
		if (CollectionUtils.isEmpty(choices) || CollectionUtils.isEmpty(data))
			return;

		boolean included = false;
		if (definition.getCardinality() == Cardinality.SINGLE) {
			T d = data.get(0);

			if (d instanceof String && StringUtils.isBlank((String) d)
					|| d == null) {
				return;
			} else {
				for (Choice<T> choice : choices) {
					List<T> value = choice.getValue();
					T v = value.get(0);
					if (v.equals(d)) {
						included = true;
						break;
					}
				}
			}

		} else if (definition.getCardinality() == Cardinality.MULTI) {
			List<T> values = new ArrayList<T>();
			for (Choice<T> choice : choices) {
				values.addAll(choice.getValue());
			}

			for (T d : data) {
				if (values.contains(d)) {
					included = true;
				} else {
					if (d instanceof String && StringUtils.isBlank((String) d)
							|| d == null) {
						included = true;
					} else {
						included = false;
						break;
					}
				}
			}
		}

		if (!included) {
			constraint(objectId, propertyData.getId()
					+ " property value must be one of choices");
		}
	}

	private void constraintIntegerPropertyValue(
			PropertyDefinition<?> definition, PropertyData<?> propertyData,
			String objectId) {
		final String msg = "AN INTEGER property violates the range constraints";
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
		if (StringUtils.isEmpty(val))
			return;
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
			String msg = "Versioning state must not be set for a non-versionable object-type";
			throw new CmisConstraintException(buildMsgWithId(msg, objectId),
					HTTP_STATUS_CODE_409);
		}
		if (documentTypeDefinition.isVersionable()
				&& versioningState == VersioningState.NONE) {
			String msg = "Versioning state must be set for a versionable object-type";
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
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			Properties properties) {
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
	public void constraintPermissionDefined(
			String repositoryId, org.apache.chemistry.opencmis.commons.data.Acl acl, String objectId) {
		boolean aclIsEmpty = (acl == null)
				|| (acl != null && CollectionUtils.isEmpty(acl.getAces())) ? true
				: false;
		List<PermissionDefinition> definitions = repositoryInfoMap.get(repositoryId)
				.getAclCapabilities().getPermissions();
		List<String> definedIds = new ArrayList<String>();
		for (PermissionDefinition pdf : definitions) {
			definedIds.add(pdf.getId());
		}

		if (!aclIsEmpty) {
			for (org.apache.chemistry.opencmis.commons.data.Ace ace : acl
					.getAces()) {
				List<String> permissions = ace.getPermissions();
				for (String p : permissions) {
					if (!definedIds.contains(p)) {
						constraint(objectId,
								"A provided ACE includes an unsupported permission");
					}
				}
			}
		}
	}

	@Override
	public void constraintAllowedSourceTypes(
			RelationshipTypeDefinition relationshipTypeDefinition,
			Content source) {
		List<String> allowed = relationshipTypeDefinition
				.getAllowedSourceTypeIds();
		if (CollectionUtils.isNotEmpty(allowed)) {
			if (!allowed.contains((source.getObjectType())))
				constraint(source.getId(),
						"The source object's type is not allowed for the relationship");
		}
	}

	@Override
	public void constraintAllowedTargetTypes(
			RelationshipTypeDefinition relationshipTypeDefinition,
			Content target) {
		List<String> allowed = relationshipTypeDefinition
				.getAllowedTargetTypeIds();
		if (CollectionUtils.isNotEmpty(allowed)) {
			if (!allowed.contains(target.getObjectType()))
				constraint(target.getId(),
						"The target object's type is not allowed for the relationship");
		}
	}

	@Override
	public void constraintVersionable(String repositoryId, String typeId) {
		DocumentTypeDefinition type = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(repositoryId, typeId);
		if (!type.isVersionable()) {
			String msg = "Object type: " + type.getId() + " is not versionbale";
			throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
		}

	}

	@Override
	public void constraintAlreadyCheckedOut(String repositoryId, Document document) {
		VersionSeries vs = contentService.getVersionSeries(repositoryId, document);
		if (vs.isVersionSeriesCheckedOut()) {
			if (!(document.isPrivateWorkingCopy())) {
				constraint(document.getId(),
						"The version series is already checked out");
			}
		}
	}

	@Override
	public void constraintUpdateWhenCheckedOut(String repositoryId,
			String currentUserId, Document document) {
		VersionSeries vs = contentService.getVersionSeries(repositoryId, document);
		if (vs.isVersionSeriesCheckedOut()) {
			if (document.isPrivateWorkingCopy()) {
				// Can update by only the use who has checked it out
				String whoCheckedOut = vs.getVersionSeriesCheckedOutBy();
				if (!currentUserId.equals(whoCheckedOut)) {
					constraint(
							document.getId(),
							"This private working copy can be modified only by the user who has checked it out. ");
				}
			} else {
				// All versions except for PWC are locked.
				constraint(document.getId(),
						"All versions except for PWC are locked when checked out.");
			}
		}

	}

	@Override
	public void constraintAclPropagationDoesNotMatch(
			AclPropagation aclPropagation) {
		// Do nothing
	}

	@Override
	public void constraintContentStreamRequired(String repositoryId, Document document) {
		String objectTypeId = document.getObjectType();
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(repositoryId, objectTypeId);
		if (td.getContentStreamAllowed() == ContentStreamAllowed.REQUIRED) {
			if (document.getAttachmentNodeId() == null
					|| contentService.getAttachment(repositoryId, document
							.getAttachmentNodeId()) == null) {
				constraint(document.getId(),
						"This document type does not allow no content stream");
			}
		}
	}

	@Override
	public void constraintContentStreamRequired(
			DocumentTypeDefinition typeDefinition, ContentStream contentStream) {
		if (ContentStreamAllowed.REQUIRED.equals(typeDefinition
				.getContentStreamAllowed())) {
			if (contentStream == null || contentStream.getStream() == null) {
				constraint("[typeId="
						+ typeDefinition.getId()
						+ "]This document type does not allow no content stream");
			}
		}
	}

	@Override
	public void constraintOnlyLeafTypeDefinition(String repositoryId, String objectTypeId) {
		TypeDefinitionContainer tdc = typeManager.getTypeById(repositoryId, objectTypeId);
		if (!CollectionUtils.isEmpty(tdc.getChildren())) {
			String msg = "Cannot delete a type definition which has sub types"
					+ " [objectTypeId = " + objectTypeId + "]";
			throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
		}
	}

	@Override
	public void constraintObjectsStillExist(String repositoryId, String objectTypeId) {
		if (contentService.existContent(repositoryId, objectTypeId)) {
			String msg = "There still exists objects of the specified object type"
					+ " [objectTypeId = " + objectTypeId + "]";
			throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
		}
	}

	@Override
	public void constraintDuplicatePropertyDefinition(
			String repositoryId, TypeDefinition typeDefinition) {
		Map<String, PropertyDefinition<?>> props = typeDefinition
				.getPropertyDefinitions();
		if (MapUtils.isNotEmpty(props)) {
			Set<String> keys = props.keySet();
			
			// CRITICAL FIX: Use baseId fallback for new custom types (same as invalidArgumentCreatableType)
			String parentTypeId = typeDefinition.getParentTypeId();
			String baseId = typeDefinition.getBaseTypeId() != null ? typeDefinition.getBaseTypeId().value() : null;
			String targetTypeId = (parentTypeId != null) ? parentTypeId : baseId;
			
			if (targetTypeId == null) {
				return;
			}
			
			TypeDefinition parent = typeManager.getTypeDefinition(repositoryId, targetTypeId);
			
			if (parent != null) {
				Map<String, PropertyDefinition<?>> parentProps = parent.getPropertyDefinitions();
				
				if (MapUtils.isNotEmpty(parentProps)) {
					Set<String> parentKeys = parentProps.keySet();
					
					for (String key : keys) {
						if (parentKeys.contains(key)) {
							String msg = "Duplicate property definition with parent type definition"
									+ " [property id = " + key + "]";
							throw new CmisConstraintException(msg, HTTP_STATUS_CODE_409);
						}
					}
				}
			}
		}
	}

	@Override
	public void constraintUpdatePropertyDefinition(
			PropertyDefinition<?> update, PropertyDefinition<?> old) {
		constraintUpdatePropertyDefinitionHelper(update, old);
	}

	private <T> PropertyDefinition<T> constraintUpdatePropertyDefinitionHelper(
			PropertyDefinition<T> update, PropertyDefinition<?> old) {
		String msg = "";
		// objField.setName(field.getName());
		// objField.setValue(field.getValue());
		// return objField;
		if (!old.isInherited().equals(update.isInherited())) {
			msg += "'inherited' cannot be modified";
		}

		if (typeManager.getSystemPropertyIds().contains(update.getId())) {
			msg += "CMIS-defined property definition cannot be modified";
		}

		if (Boolean.FALSE.equals(old.isRequired())
				&& Boolean.TRUE.equals(update.isRequired())) {
			msg += "'required' cannot be modified from Optional to Required";
			constraint(msg);
		}

		if (Boolean.TRUE.equals(old.isOpenChoice())
				&& Boolean.FALSE.equals(update.isOpenChoice())) {
			msg += "'openChoice' cannot be modified from true to false";
			constraint(msg);
		}

		if (Boolean.FALSE.equals(update.isOpenChoice())) {
			constraintChoicesRestriction(update, old);
		}

		constraintRestrictedValidation(update, old);

		if (!old.getPropertyType().equals(update.getPropertyType())) {
			msg += "'property type' cannot be modified";
			constraint(msg);
		}

		if (!old.getCardinality().equals(update.getCardinality())) {
			msg += "'cardinality' cannot be modified";
			constraint(msg);
		}
		return update;
	}

	private void constraintChoicesRestriction(PropertyDefinition<?> update,
			PropertyDefinition<?> old) {
		String msg = update.getId() + ":";

		List<?> updateValues = flattenChoiceValues(update.getChoices());
		List<?> oldValues = flattenChoiceValues(old.getChoices());
		if (!updateValues.containsAll(oldValues)) {
			msg += "'choices' values must not be removed if 'openChoice' is false";
			constraint(msg);
		}
	}

	private <T> List<T> flattenChoiceValues(List<Choice<T>> choices) {
		if (CollectionUtils.isEmpty(choices))
			return null;

		List<T> result = new ArrayList<T>();
		for (Choice<T> choice : choices) {
			List<T> value = choice.getValue();
			if (CollectionUtils.isEmpty(value))
				continue;
			for (T v : value) {
				result.add(v);
			}

			result.addAll(flattenChoiceValues(choice.getChoice()));
		}
		return result;
	}

	private void constraintRestrictedValidation(PropertyDefinition<?> update,
			PropertyDefinition<?> old) {
		switch (update.getPropertyType()) {
		case BOOLEAN:
			break;
		case DATETIME:
			break;
		case DECIMAL:
			constraintRestrictedDecimalValidation(update, old);
			break;
		case HTML:
			break;
		case ID:
			break;
		case INTEGER:
			constraintRestrictedIntegerValidation(update, old);
			break;
		case STRING:
			constraintRestrictedStringValidation(update, old);
			break;
		case URI:
			break;
		default:
			break;
		}
	}

	private void constraintRestrictedDecimalValidation(
			PropertyDefinition<?> update, PropertyDefinition<?> old) {
		PropertyDecimalDefinition _update = (PropertyDecimalDefinition) update;
		PropertyDecimalDefinition _old = (PropertyDecimalDefinition) old;
		// When minValue is restricted, throw an error
		minRestriction(_update.getMinValue(), _old.getMinValue(),
				_update.getId());
		// When minValue is restricted, throw an error
		maxRestriction(_update.getMaxValue(), _old.getMaxValue(),
				_update.getId(), null);
	}

	private void constraintRestrictedIntegerValidation(
			PropertyDefinition<?> update, PropertyDefinition<?> old) {
		PropertyIntegerDefinition _update = (PropertyIntegerDefinition) update;
		PropertyIntegerDefinition _old = (PropertyIntegerDefinition) old;
		// When minValue is restricted, throw an error
		minRestriction(_update.getMinValue(), _old.getMinValue(),
				_update.getId());
		// When minValue is restricted, throw an error
		maxRestriction(_update.getMaxValue(), _old.getMaxValue(),
				_update.getId(), _update.getPropertyType());
	}

	private void constraintRestrictedStringValidation(
			PropertyDefinition<?> update, PropertyDefinition<?> old) {
		PropertyStringDefinition _update = (PropertyStringDefinition) update;
		PropertyStringDefinition _old = (PropertyStringDefinition) old;
		// When minValue is restricted, throw an error
		maxRestriction(_update.getMaxLength(), _old.getMaxLength(),
				_update.getId(), _update.getPropertyType());
	}

	private void minRestriction(Comparable update, Comparable old,
			String propertyId) {
		// When minValue is restricted, throw an error
		boolean flag = false;
		String msg = propertyId + ":";
		if (old == null) {
			if (update != null) {
				flag = true;
			}
		} else {
			if (update != null) {
				if (old.compareTo(update) < 0) {
					flag = true;
				}
			}
		}
		if (flag) {
			msg += "'minValue' cannot be further restricted";
			constraint(msg);
		}
	}

	private void maxRestriction(Comparable update, Comparable old,
			String propertyId, PropertyType propertyType) {
		// When minValue is restricted, throw an error
		boolean flag = false;
		String msg = propertyId + ":";
		if (old == null) {
			if (update != null) {
				flag = true;
			}
		} else {
			if (update != null) {
				if (old.compareTo(update) > 0) {
					flag = true;
				}
			}
		}
		if (flag) {
			if (propertyType.equals(PropertyType.STRING)) {
				msg += "'maxLength' cannot be further restricted";
			} else {
				msg += "'maxValue' cannot be further restricted";
			}
			constraint(msg);
		}
	}

	@Override
	public void constraintQueryName(PropertyDefinition<?> propertyDefinition) {

		String msg = propertyDefinition.getId() + ":";
		String queryName = propertyDefinition.getQueryName();

		if (StringUtils.isEmpty(queryName))
			constraint(msg + "'queryName' is null");

		final String space = " ";
		final String comma = ",";
		final String dubleQuotation = "\"";
		final String singleQuotaion = "\'";
		final String backslash = "\\\\";
		final String period = "\\.";
		final String openParenthesis = "\\(";
		final String closeParenthesis = "\\)";

		if (queryName.matches(buildContainRegEx(space))
				|| queryName.matches(buildContainRegEx(comma))
				|| queryName.matches(buildContainRegEx(dubleQuotation))
				|| queryName.matches(buildContainRegEx(singleQuotaion))
				|| queryName.matches(buildContainRegEx(backslash))
				|| queryName.matches(buildContainRegEx(period))
				|| queryName.matches(buildContainRegEx(openParenthesis))
				|| queryName.matches(buildContainRegEx(closeParenthesis))) {
			constraint(msg
					+ "invalid character for 'queryName'. See spec 2.1.2.1.3");
		}
	}

	private String buildContainRegEx(String contained) {
		return ".*" + contained + ".*";
	}

	@Override
	public void constraintContentStreamDownload(String repositoryId, Document document) {
		DocumentTypeDefinition documentTypeDefinition = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(repositoryId, document);
		ContentStreamAllowed csa = documentTypeDefinition
				.getContentStreamAllowed();
		
		// CMIS Standard Compliance: Only reject if ContentStreamAllowed is NOTALLOWED
		// For ALLOWED: ContentStream is optional - null content stream is valid  
		// For REQUIRED: ContentStream must exist - null content stream is invalid
		if (ContentStreamAllowed.NOTALLOWED == csa) {
			constraint(document.getId(),
					"This document type does not allow ContentStream. getContentStream is not supported.");
		} else if (ContentStreamAllowed.REQUIRED == csa
				&& StringUtils.isBlank(document.getAttachmentNodeId())) {
			constraint(document.getId(),
					"This document type requires ContentStream but none exists. getContentStream is not supported.");
		}
	}

	@Override
	public void constraintRenditionStreamDownload(Content content,
			String streamId) {
		List<String> renditions = content.getRenditionIds();
		if (CollectionUtils.isEmpty(renditions)
				|| !renditions.contains(streamId)) {
			constraint(content.getId(),
					"This document has no rendition specified with " + streamId);
		}
	}

	@Override
	public void constraintImmutable(String repositoryId,
			Document document, TypeDefinition typeDefinition) {
		Boolean defaultVal = (Boolean) typeManager.getSingleDefaultValue(
				PropertyIds.IS_IMMUTABLE, typeDefinition.getId(), repositoryId);

		boolean flag = false;
		if (document.isImmutable() == null) {
			if (defaultVal != null && defaultVal) {
				flag = true;
			}
		} else {
			if (document.isImmutable()) {
				flag = true;
			}
		}

		if (flag) {
			constraint(document.getId(),
					"Immutable document cannot be updated/deleted");
		}
	}

	@Override
	public void constraintPropertyDefinition(TypeDefinition typeDefinition,
			PropertyDefinition<?> propertyDefinition) {

		String typeId = typeDefinition.getId();
		String propertyId = propertyDefinition.getId();

		if (propertyDefinition.isOrderable()
				&& propertyDefinition.getCardinality() == Cardinality.MULTI) {
			String msg = DataUtil.buildPrefixTypeProperty(typeId, propertyId)
					+ "PropertyDefinition violates the specification: cardinality=multi should not be orderable";
			constraint(msg);
		}
	}

	public void constraintDeleteRootFolder(String repositoryId, String objectId){
		String rootFolderId = repositoryInfoMap.get(repositoryId).getRootFolderId();
		if(rootFolderId.equals(objectId)){
			constraint(objectId, "Cannot delete root folder");
		}
	}

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
	public void versioning(CallContext callContext, Document doc) {
		String userId = callContext.getUsername();
		UserItem u = contentService.getUserItemById(callContext.getRepositoryId(), userId);
		
		if (!doc.isLatestVersion() && !doc.isPrivateWorkingCopy() && !u.isAdmin()) {
			String msg = "The operation is not allowed on a non-current version of a document";
			throw new CmisVersioningException(buildMsgWithId(msg, doc.getId()),
					HTTP_STATUS_CODE_409);
		}
	}

	@Override
	public void nameConstraintViolation(String repositoryId, Folder parentFolder,
			Properties properties) {
		String proposedName = DataUtil.getStringProperty(properties, PropertyIds.NAME);
		nameConstraintViolation(repositoryId, parentFolder, proposedName);
	}

	@Override
	public void nameConstraintViolation(String repositoryId, Folder parentFolder,
			String proposedName) {
		boolean mustUnique = propertyManager.readBoolean(PropertyKey.CAPABILITY_EXTENDED_UNIQUE_NAME_CHECK);
		if (!mustUnique) {
			return;
		}

		if (parentFolder == null) {

		} else {
			List<String> names = contentDaoService.getChildrenNames(repositoryId, parentFolder.getId());
			String lowerCaseProposedName = proposedName.toLowerCase();
			for(String name: names) {
				if (lowerCaseProposedName.equals(name.toLowerCase())) {
					throw new CmisContentAlreadyExistsException(
							"A content with the specified name already exists",
							HTTP_STATUS_CODE_409);
				}
			}
		}
	}

	@Override
	public void updateConflict(Content content, Holder<String> changeToken) {
		// CHANGE TOKEN FIX: Handle null change tokens properly for NemakiWare
		// NemakiWare often sets change token to null, which becomes "null" string in properties
		String contentChangeToken = content.getChangeToken();
		String requestChangeToken = (changeToken != null) ? changeToken.getValue() : null;
		
		// If both are null or "null", allow the update (no conflict)
		if (isNullOrNullString(contentChangeToken) && isNullOrNullString(requestChangeToken)) {
			// No change token enforcement when both are null - allow update
			return;
		}
		
		// If request has no change token but content has one, require it
		if (isNullOrNullString(requestChangeToken) && !isNullOrNullString(contentChangeToken)) {
			throw new CmisUpdateConflictException(
					"Change token is required to update", HTTP_STATUS_CODE_409);
		}
		
		// If change tokens don't match, conflict detected
		if (!java.util.Objects.equals(requestChangeToken, contentChangeToken) && 
			!java.util.Objects.equals(requestChangeToken, "null") && 
			!java.util.Objects.equals(contentChangeToken, "null")) {
			throw new CmisUpdateConflictException(
					"Cannot update because the changeToken conflicts",
					HTTP_STATUS_CODE_409);
		}
	}

	/**
	 * Helper method to check if a change token is null or "null" string
	 */
	private boolean isNullOrNullString(String token) {
		return token == null || "null".equals(token);
	}

	private String buildMsgWithId(String msg, String objectId) {
		if (objectId == null)
			objectId = "";
		return msg + " [cmis:objectId = " + objectId + "]";
	}

	private String getObjectId(Properties properties) {
		return DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID);
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

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}
}
