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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;

@Component
public class Patch_20170425 extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_20170425.class);

	public static final int GroupMaxDepth = 20;

	public String getName() {
		return "patch_20170425";
	}

	@Override
	protected void applySystemPatch() {
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		addRelationship(repositoryId, NemakiObjectType.nemakiParentChildRelationship);
		addRelationship(repositoryId, NemakiObjectType.nemakiBidirectionalRelationship);

		// New view is userItemsById and groupItemById, old view delete.
		patchUtil.deleteView(repositoryId, "users");
		patchUtil.deleteView(repositoryId, "usersById");
		patchUtil.deleteView(repositoryId, "groups");
		patchUtil.deleteView(repositoryId, "groupsById");

		String joinedDirectGroupsByUserIdViewCode = "function(doc) {" + "if (doc.type == 'cmis:item' && doc.groupId) {"
				+ "if ( doc.subTypeProperties ) {" + "for(var i in doc.subTypeProperties ) {"
				+ "if ( doc.subTypeProperties[i].key == 'nemaki:users' ) {"
				+ "for(var user in doc.subTypeProperties[i].value) {"
				+ "emit(doc.subTypeProperties[i].value[user], doc)" + "}}}}}}";
		patchUtil.addView(repositoryId, "joinedDirectGroupsByUserId", joinedDirectGroupsByUserIdViewCode);

		String joinedDirectGroupsByGroupIdCode = "function(doc) {" + "if (doc.type == 'cmis:item' && doc.groupId) {"
				+ "if ( doc.subTypeProperties ) {" + "for(var i in doc.subTypeProperties ) {"
				+ "if ( doc.subTypeProperties[i].key == 'nemaki:groups' ) {"
				+ "for(var group in doc.subTypeProperties[i].value) {";
		for (int i = 0; i < GroupMaxDepth; i++) {
			joinedDirectGroupsByGroupIdCode += "emit([doc.subTypeProperties[i].value[group]," + i + "], doc);";
		}
		joinedDirectGroupsByGroupIdCode += "}}}}}}";

		patchUtil.addView(repositoryId, "joinedDirectGroupsByGroupId", joinedDirectGroupsByGroupIdCode);
	}

	private void addRelationship(String repositoryId, String relationshipName) {
		final CallContext context = new SystemCallContext(repositoryId);
		try {
			TypeDefinition _type = patchUtil.getRepositoryService().getTypeDefinition(context, repositoryId,
					relationshipName, null);
		} catch (CmisObjectNotFoundException e) {
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
