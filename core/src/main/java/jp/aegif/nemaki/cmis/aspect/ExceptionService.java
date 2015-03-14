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
package jp.aegif.nemaki.cmis.aspect;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.constant.DomainType;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface ExceptionService {

	//ObjectNotFound & PermissionDenied exception are handled by calling getObject method


	void invalidArgument(String msg);
	void invalidArgumentRequired(String argumentName, Object argument);
	void invalidArgumentRequired(String argumentName);
	void invalidArgumentRequiredString(String argumentName, String argument);
	void invalidArgumentRequiredHolderString(String argumentName, Holder<String> argument);
	void invalidArgumentRequiredCollection(String argumentName, Collection collection);
	void invalidArgumentRequiredParentFolderId(String folderId);
	void invalidArgumentOrderBy(String orderBy);
	void invalidArgumentFolderId(Folder folder, String folderId);
	void invalidArgumentRootFolder(Content content);
	void invalidArgumentDepth(BigInteger depth);
	void invalidArgumentChangeEventNotAvailable(Holder<String> changeLogToken);
	void invalidArgumentCreatableType(TypeDefinition type);
	void invalidArgumentUpdatableType(TypeDefinition type);
	void invalidArgumentDeletableType(String typeId);
	void invalidArgumentDoesNotExistType(String typeId);
	void invalidArgumentSecondaryTypeIds(Properties properties);
	void objectNotFound(DomainType type, Object object, String id, String msg);
	void objectNotFound(DomainType type, Object object, String id);
	void objectNotFoundVersionSeries(String id, Collection collection);
	void objectNotFoundParentFolder(String id, Content content);
	void permissionDenied(CallContext context, String key, Content content);
	void perimissionAdmin(CallContext context);
	void constraint(String objectId, String msg);
	void constraintBaseTypeId(Properties properties, BaseTypeId baseTypeId);
	void constraintAllowedChildObjectTypeId(Folder folder, Properties childProperties);
	<T>void constraintPropertyValue(TypeDefinition typeDefinition, Properties properties, String objectId);
	void constraintControllableVersionable(DocumentTypeDefinition documentTypeDefinition, VersioningState versioningState, String objectId);
	void constraintCotrollablePolicies(TypeDefinition typeDefinition, List<String> policies, Properties properties);
	void constraintCotrollableAcl(TypeDefinition typeDefinition, Acl addAces, Acl removeAces, Properties properties);
	void constraintPermissionDefined(Acl acl, String objectId);
	void constraintAllowedSourceTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content source);
	void constraintAllowedTargetTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content target);
	void constraintVersionable(String typeId);
	void constraintAlreadyCheckedOut(Document document);
	void constraintUpdateWhenCheckedOut(String currentUserId, Document document);
	void constraintAclPropagationDoesNotMatch(AclPropagation aclPropagation);
	void constraintContentStreamRequired(Document document);
	void constraintContentStreamRequired(DocumentTypeDefinition typeDefinition, ContentStream contentStream);
	void constraintOnlyLeafTypeDefinition(String objectTypeId);
	void constraintObjectsStillExist(String objectTypeId);
	void constraintDuplicatePropertyDefinition(TypeDefinition typeDefinition);
	void constraintUpdatePropertyDefinition(PropertyDefinition<?> update,PropertyDefinition<?> old);
	void constraintQueryName(PropertyDefinition<?> propertyDefinition);
	void constraintImmutable(Document document, TypeDefinition typeDefinition);
	void constraintContentStreamDownload(Document document);
	void constraintRenditionStreamDownload(Content content, String streamId);
	void constraintPropertyDefinition(TypeDefinition typeDefinition, PropertyDefinition<?> propertyDefinition);
	void constraintDeleteRootFolder(String objectId);
	void contentAlreadyExists(Content content, Boolean overwriteFlag);
	void streamNotSupported(DocumentTypeDefinition documentTypeDefinition, ContentStream contentStream);
	void nameConstraintViolation(Properties properties, Folder parentFolder);
	void versioning(Document document);
	void updateConflict(Content content, Holder<String>  changeToken);

	//TODO Where to implement "storage" exception? Here or DAO service?

}
