package util;

import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;

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
}
