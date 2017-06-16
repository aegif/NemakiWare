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
public class Patch_20170617 extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_20170617.class);

	public String getName() {
		return "patch_20170617";
	}

	@Override
	protected void applySystemPatch() {
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		patchUtil.deleteView(repositoryId, "joinedDirectGroupsByGroupId");
		int GroupMaxDepth = 20;
		String joinedDirectGroupsByGroupIdCode = "function(doc) {" + "if (doc.type == 'cmis:item' && doc.groupId) {"
				+ "if ( doc.subTypeProperties ) {" + "for(var i in doc.subTypeProperties ) {"
				+ "if ( doc.subTypeProperties[i].key == 'nemaki:groups' ) {"
				+ "for(var group in doc.subTypeProperties[i].value) {";
		for (int i = 0; i < GroupMaxDepth; i++) {
			joinedDirectGroupsByGroupIdCode += "emit([doc.subTypeProperties[i].value[group],"+i+"], doc);";
		}
		joinedDirectGroupsByGroupIdCode += "}}}}}}";

		patchUtil.addView(repositoryId, "joinedDirectGroupsByGroupId", joinedDirectGroupsByGroupIdCode);	}

}
