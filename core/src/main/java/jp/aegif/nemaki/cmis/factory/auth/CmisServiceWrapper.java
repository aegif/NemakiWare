package jp.aegif.nemaki.cmis.factory.auth;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.CmisServiceWrapperManager;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;

import jp.aegif.nemaki.util.constant.CallContextKey;

public class CmisServiceWrapper extends ConformanceCmisServiceWrapper{
	/**
     * Constructor used by {@link CmisServiceWrapperManager}.
     */
    public CmisServiceWrapper(CmisService service) {
        super(service);
    }

    /**
     * Alternative constructor.
     */
    public CmisServiceWrapper(CmisService service, BigInteger defaultTypesMaxItems,
            BigInteger defaultTypesDepth, BigInteger defaultMaxItems, BigInteger defaultDepth,
            CallContext callContext) {
        super(service, defaultTypesMaxItems, defaultTypesDepth, defaultMaxItems, defaultDepth);
       setCallContext(callContext);
    }
	
	@Override
	public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
		Boolean isSu = (Boolean)getCallContext().get(CallContextKey.IS_SU);
		if(isSu){
			return super.getRepositoryInfos(extension);
		}else{
			List<RepositoryInfo> list = new ArrayList<RepositoryInfo>();
			list.add(getRepositoryInfo(getCallContext().getRepositoryId(), extension));
			return list;
		}
	}
}
