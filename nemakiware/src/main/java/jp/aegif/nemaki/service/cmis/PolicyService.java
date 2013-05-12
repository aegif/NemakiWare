package jp.aegif.nemaki.service.cmis;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public interface PolicyService {
	public void applyPolicy(CallContext callContext, String policyId, String objectId, ExtensionsData extension);
	public void removePolicy(CallContext callContext, String policyId, String objectId, ExtensionsData extension);
	public List<ObjectData> getAppliedPolicies(CallContext callContext, String objectId, String  filter, ExtensionsData extension);

}
