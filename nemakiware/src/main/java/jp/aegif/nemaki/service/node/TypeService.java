package jp.aegif.nemaki.service.node;

import java.util.List;

import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

public interface TypeService {
	NemakiTypeDefinition getTypeDefinition(String typeId);
	List<NemakiTypeDefinition> getTypeDefinitions();
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String coreId);
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String propertyId);
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores();
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String detailId);
	List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(
			String coreNodeId);
	NemakiTypeDefinition createTypeDefinition(
			NemakiTypeDefinition typeDefinition);
	NemakiTypeDefinition updateTypeDefinition(
			NemakiTypeDefinition typeDefinition);
	void deleteTypeDefinition(String typeId);
	NemakiPropertyDefinition getPropertyDefinition(String detailNodeId);
	NemakiPropertyDefinitionDetail createPropertyDefinition(
			NemakiPropertyDefinition propertyDefinition);
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail);
}
