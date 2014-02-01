package jp.aegif.nemaki.service.node;

import java.util.List;

import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

public interface TypeService {
	List<NemakiTypeDefinition> getTypeDefinitions();
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String detailId);
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String coreId);
	NemakiTypeDefinition getTypeDefinition(String typeId);
	NemakiTypeDefinition createTypeDefinition(
			NemakiTypeDefinition typeDefinition);

	NemakiTypeDefinition updateTypeDefinition(
			NemakiTypeDefinition typeDefinition);
	void deleteTypeDefinition(String typeId);
	NemakiPropertyDefinition getPropertyDefinition(String detailNodeId);
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores();
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String propertyId);
	NemakiPropertyDefinitionDetail createPropertyDefinition(
			NemakiPropertyDefinition propertyDefinition);
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail);
}
