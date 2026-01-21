package jp.aegif.nemaki.businesslogic;

import java.util.List;

import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

public interface TypeService {
	NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId);
	List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId);
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String coreId);
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId);
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId);
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String detailId);
	List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(
			String repositoryId, String coreNodeId);
	NemakiTypeDefinition createTypeDefinition(
			String repositoryId, NemakiTypeDefinition typeDefinition);
	NemakiTypeDefinition updateTypeDefinition(
			String repositoryId, NemakiTypeDefinition typeDefinition);
	void deleteTypeDefinition(String repositoryId, String typeId);
	NemakiPropertyDefinition getPropertyDefinition(String repositoryId, String detailNodeId);
	NemakiPropertyDefinitionDetail createPropertyDefinition(
			String repositoryId, NemakiPropertyDefinition propertyDefinition);
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			String repositoryId, NemakiPropertyDefinitionDetail propertyDefinitionDetail);
	
	/**
	 * Update a property definition core.
	 * WARNING: Property cores may be globally shared across types. Changing core fields
	 * (propertyType, cardinality) could affect all types that use the same propertyId.
	 * Use with caution.
	 * 
	 * @param repositoryId Repository ID
	 * @param propertyDefinitionCore The core to update
	 * @return Updated core
	 */
	NemakiPropertyDefinitionCore updatePropertyDefinitionCore(
			String repositoryId, NemakiPropertyDefinitionCore propertyDefinitionCore);
}
