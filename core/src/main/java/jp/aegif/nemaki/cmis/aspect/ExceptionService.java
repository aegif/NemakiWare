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
	void invalidArgumentRequiredParentFolderId(String repositoryId, String folderId);
	void invalidArgumentOrderBy(String repositoryId, String orderBy);
	void invalidArgumentFolderId(Folder folder, String folderId);
	void invalidArgumentRootFolder(String repositoryId, Content content);
	void invalidArgumentDepth(BigInteger depth);
	void invalidArgumentChangeEventNotAvailable(String repositoryId, Holder<String> changeLogToken);
	void invalidArgumentCreatableType(String repositoryId, TypeDefinition type);
	void invalidArgumentUpdatableType(TypeDefinition type);
	void invalidArgumentDeletableType(String repositoryId, String typeId);
	void invalidArgumentDoesNotExistType(String repositoryId, String typeId);
	void invalidArgumentSecondaryTypeIds(String repositoryId, Properties properties);
	void objectNotFound(DomainType type, Object object, String id, String msg);
	void objectNotFound(DomainType type, Object object, String id);
	void objectNotFoundByPath(DomainType type, Object object, String id, String msg);
	void objectNotFoundByPath(DomainType type, Object object, String id);
	void objectNotFoundVersionSeries(String id, Collection collection);
	void objectNotFoundParentFolder(String repositoryId, String id, Content content);
	void permissionDenied(CallContext context, String repositoryId, String key, Content content);
	void perimissionAdmin(CallContext context, String repositoryId);
	void constraint(String objectId, String msg);
	void constraintBaseTypeId(String repositoryId, Properties properties, BaseTypeId baseTypeId);
	void constraintAllowedChildObjectTypeId(Folder folder, Properties childProperties);
	<T>void constraintPropertyValue(String repositoryId, TypeDefinition typeDefinition, Properties properties, String objectId);
	void constraintControllableVersionable(DocumentTypeDefinition documentTypeDefinition, VersioningState versioningState, String objectId);
	void constraintCotrollablePolicies(TypeDefinition typeDefinition, List<String> policies, Properties properties);
	void constraintCotrollableAcl(TypeDefinition typeDefinition, Acl addAces, Acl removeAces, Properties properties);
	void constraintPermissionDefined(String repositoryId, Acl acl, String objectId);
	void constraintAllowedSourceTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content source);
	void constraintAllowedTargetTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content target);
	void constraintVersionable(String repositoryId, String typeId);
	void constraintAlreadyCheckedOut(String repositoryId, Document document);
	void constraintUpdateWhenCheckedOut(String repositoryId, String currentUserId, Document document);
	void constraintAclPropagationDoesNotMatch(AclPropagation aclPropagation);
	void constraintContentStreamRequired(String repositoryId, Document document);
	void constraintContentStreamRequired(DocumentTypeDefinition typeDefinition, ContentStream contentStream);
	void constraintOnlyLeafTypeDefinition(String repositoryId, String objectTypeId);
	void constraintObjectsStillExist(String repositoryId, String objectTypeId);
	void constraintDuplicatePropertyDefinition(String repositoryId, TypeDefinition typeDefinition);
	void constraintUpdatePropertyDefinition(PropertyDefinition<?> update,PropertyDefinition<?> old);
	void constraintQueryName(PropertyDefinition<?> propertyDefinition);
	void constraintImmutable(String repositoryId, Document document, TypeDefinition typeDefinition);
	void constraintContentStreamDownload(String repositoryId, Document document);
	void constraintRenditionStreamDownload(Content content, String streamId);
	void constraintPropertyDefinition(TypeDefinition typeDefinition, PropertyDefinition<?> propertyDefinition);
	void constraintDeleteRootFolder(String repositoryId, String objectId);
	void contentAlreadyExists(Content content, Boolean overwriteFlag);
	void streamNotSupported(DocumentTypeDefinition documentTypeDefinition, ContentStream contentStream);
	void nameConstraintViolation(String repositoryId, Folder parentFolder, Properties properties);
	void nameConstraintViolation(String repositoryId, Folder parentFolder, String proposedName);
	void versioning(Document document);
	void updateConflict(Content content, Holder<String>  changeToken);

	//TODO Where to implement "storage" exception? Here or DAO service?

}
