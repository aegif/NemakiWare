package jp.aegif.nemaki.service.cmis;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface CompileObjectService {
	public ObjectData compileObjectData(CallContext context,
			Content content, String filter, Boolean includeAllowableActions,
			Boolean includeAcl);
	
	public <T> ObjectList compileObjectDataList(CallContext callContext,
			List<T> contents, String filter, Boolean includeAllowableActions,
			Boolean includeAcl, BigInteger maxItems, BigInteger skipCount);
	
	public ObjectList compileChangeDataList(CallContext context, List<Change> changes,
			Boolean includeProperties, String filter, Boolean includePolicyIds,
			Boolean includeAcl);
	
	public Properties compileProperties(Content content, Set<String> filter,
			ObjectInfoImpl objectInfo);
	
	public AllowableActions compileAllowableActions(CallContext callContext,
			Content content);
	
	public Set<String> splitFilter(String filter);
}
