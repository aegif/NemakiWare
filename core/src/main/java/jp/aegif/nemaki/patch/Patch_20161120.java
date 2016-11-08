package jp.aegif.nemaki.patch;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.util.constant.NemakiObjectType;

public class Patch_20161120 extends AbstractNemakiPatch{
	private static Logger logger = LoggerFactory.getLogger(Patch_20161120.class);

	public String getName() {
    	return "patch_20161120";
	}

	@Override
	protected void applySystemPatch() {
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		addRelationship(repositoryId, NemakiObjectType.nemakiParentChildRelationship);
		addRelationship(repositoryId, NemakiObjectType.nemakiBidirectionalRelationship);
	}

	private void addRelationship(String repositoryId, String relationshipName){
		final CallContext context = new SystemCallContext(repositoryId);
		try{
			TypeDefinition _type = patchUtil.getRepositoryService().getTypeDefinition(context, repositoryId, relationshipName, null);
		}catch(CmisObjectNotFoundException e){
			RelationshipTypeDefinitionImpl tdf = new RelationshipTypeDefinitionImpl();
				tdf.setId(relationshipName);
				tdf.setLocalName(relationshipName);
				tdf.setQueryName(relationshipName);
				tdf.setDisplayName(relationshipName);
				tdf.setBaseTypeId(BaseTypeId.CMIS_RELATIONSHIP);
				tdf.setParentTypeId("cmis:relationship");
				tdf.setDescription(relationshipName);
				tdf.setIsCreatable(true);
				tdf.setIsFileable(false);
				tdf.setIsQueryable(true);
				tdf.setIsControllablePolicy(false);
				tdf.setIsControllableAcl(true);
				tdf.setIsFulltextIndexed(false);
				tdf.setIsIncludedInSupertypeQuery(true);
				TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();
				typeMutability.setCanCreate(true);
				typeMutability.setCanUpdate(false);
				typeMutability.setCanDelete(false);
				tdf.setTypeMutability(typeMutability);

				Map<String, PropertyDefinition<?>> props = new HashMap<>();

				tdf.setPropertyDefinitions(props);

				patchUtil.getRepositoryService().createType(context, repositoryId, tdf, null);
		}
	}
}
