package jp.aegif.nemaki.service.cmis;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.constant.DomainType;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface ExceptionService {

	//ObjectNotFound & PermissionDenied exception are handled by calling getObject method 
	
	
	public void invalidArgument(String msg);
	public void invalidArgumentRequired(String argumentName, Object argument);
	public void invalidArgumentRequired(String argumentName);
	public void invalidArgumentRequiredString(String argumentName, String argument);
	public void invalidArgumentRequiredHolderString(String argumentName, Holder<String> argument);
	public void invalidArgumentRequiredCollection(String argumentName, Collection collection);
	public void invalidArgumentRequiredParentFolderId(String folderId);
	public void invalidArgumentOrderBy(String orderBy);
	public void invalidArgumentFolderId(Folder folder, String folderId);
	public void invalidArgumentRootFolder(Folder folder);
	public void invalidArgumentDepth(BigInteger depth);
	public void invalidArgumentChangeEventNotAvailable(Holder<String> changeLogToken);
	public void objectNotFound(DomainType type, Object object, String id, String msg);
	public void objectNotFound(DomainType type, Object object, String id);
	public void objectNotFoundVersionSeries(String id, Collection collection);
	public void objectNotFoundParentFolder(String id, Content content);
	public void permissionDenied(CallContext context, String key, ObjectData object);
	public void permissionDenied(CallContext context, String key, Content content);
	public void constraint(String objectId, String msg);
	public void constraintBaseTypeId(Properties properties, BaseTypeId baseTypeId);
	public void constraintAllowedChildObjectTypeId(Folder folder, Properties childProperties);
	public void constraintPropertyValue(Properties properties);
	public void constraintControllableVersionable(DocumentTypeDefinition documentTypeDefinition, VersioningState versioningState, String objectId);
	public void constraintCotrollablePolicies(TypeDefinition typeDefinition, List<String> policies, Properties properties);
	public void constraintCotrollableAcl(TypeDefinition typeDefinition, Acl addAces, Acl removeAces, Properties properties);
	public void constraintPermissionDefined(Acl acl, String objectId);
	public void constraintAllowedSourceTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content source);
	public void constraintAllowedTargetTypes(RelationshipTypeDefinition relationshipTypeDefinition, Content target);
	public void constraintVersionable(String typeId);
	public void constraintAlreadyCheckedOut(Document document);
	public void constraintAclPropagationDoesNotMatch(AclPropagation aclPropagation);
	public void constraintContentStreamRequired(Document document);
	public void contentAlreadyExists(Content content, Boolean overwriteFlag);
	public void streamNotSupported(DocumentTypeDefinition documentTypeDefinition, ContentStream contentStream);
	public void nameConstraintViolation(Properties properties, Folder parentFolder);
	public void versioning(Document document);
	public void updateConflict(Content content, Holder<String>  changeToken);
	
	//TODO Where to implement "storage" exception? Here or DAO service? 
	
}
