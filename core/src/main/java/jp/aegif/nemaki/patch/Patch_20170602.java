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
public class Patch_20170602 extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_20170602.class);

	public String getName() {
		return "patch_20170602";
	}

	@Override
	protected void applySystemPatch() {
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		String changesByObjectIdViewCode = "function(doc) { if (doc.type == 'change')  emit(doc.objectId, doc) }";
		patchUtil.addView(repositoryId, "changesByObjectId", changesByObjectIdViewCode);
	}

}
