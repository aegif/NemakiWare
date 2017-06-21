package util;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.RelationshipType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

import constant.PropertyKey;
import jp.aegif.nemaki.common.NemakiObjectType;

public class RelationshipUtil {
	public static boolean isCascadeRelation(ObjectType typeDef){
		if ( typeDef.getId().equals(NemakiObjectType.nemakiParentChildRelationship)){
			return true;
		}else if (!typeDef.isBaseType()){
			return 	isCascadeRelation(typeDef.getParentType());
		}
		return false;
	}


	public static List<RelationshipType> getCreatableRelationsTypes(Session session, CmisObject obj){
		List<String> enableTypes = NemakiConfig.getValues(PropertyKey.UI_VISIBILITY_CREATE_RELATIONSHIP);

		List<RelationshipType> viewTypesTemp = session.getTypeDescendants(null, -1, true).stream().map(Tree::getItem)
				.filter(p -> p.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP).map(p -> (RelationshipType) p)
				.filter(p -> enableTypes.contains(p.getLocalName())).collect(Collectors.toList());

		List<RelationshipType> viewTypes = viewTypesTemp.stream()
				.filter(p -> p.getAllowedSourceTypes().contains(obj.getType())).collect(Collectors.toList());

		return viewTypes;
	}
}
