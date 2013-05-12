package jp.aegif.nemaki.service.cmis;

import java.math.BigInteger;
import java.util.List;

import jp.aegif.nemaki.repository.TypeManager;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface RepositoryService {

	public abstract TypeManager getTypeManager();

	/**
	 * Checks whether this repository has the given identifier or not.
	 */
	public abstract boolean hasThisRepositoryId(String repositoryId);

	/**
	 * Returns information about the CMIS repository, the optional capabilities
	 * it supports and its access control information if applicable.
	 */
	public abstract RepositoryInfo getRepositoryInfo();

	/**
	 * Gets the CMIS types.
	 */
	public abstract TypeDefinitionList getTypeChildren(CallContext callContext,
			String typeId, Boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount);

	/**
	 * Returns the set of descendant object type defined for the repository
	 * under the specified type.
	 */
	public abstract List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String typeId, BigInteger depth,
			Boolean includePropertyDefinitions);

	/**
	 * Gets the definition of the specified object type.
	 */
	public abstract TypeDefinition getTypeDefinition(CallContext callContext,
			String typeId);
}
