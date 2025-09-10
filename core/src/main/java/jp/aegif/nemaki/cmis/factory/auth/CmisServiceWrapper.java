package jp.aegif.nemaki.cmis.factory.auth;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
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
	
	/**
	 * CRITICAL DEBUG: Direct createType override to trace TCK type creation flow
	 * 
	 * This override will help identify the exact code path used by TCK tests for type creation
	 * and ensure debugging reaches the NemakiWare RepositoryServiceImpl.
	 */
	@Override
	public org.apache.chemistry.opencmis.commons.definitions.TypeDefinition createType(String repositoryId, 
			org.apache.chemistry.opencmis.commons.definitions.TypeDefinition type, 
			org.apache.chemistry.opencmis.commons.data.ExtensionsData extension) {
		
		// Delegate to the wrapped NemakiWare CmisService
		return getWrappedService().createType(repositoryId, type, extension);
	}
	
	/**
	 * CRITICAL FIX: Direct createFolder override to ensure NemakiWare ObjectServiceImpl is called
	 * 
	 * Issue: OpenCMIS 1.2.0-SNAPSHOT Browser Binding was not calling CmisService.createFolder(),
	 * causing "Unknown operation" errors in TCK tests. This override ensures that createFolder
	 * operations are properly delegated to the wrapped NemakiWare CmisService.
	 */
	@Override
	public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		
		// Delegate to the wrapped NemakiWare CmisService
		return getWrappedService().createFolder(repositoryId, properties, folderId, policies, addAces, removeAces, extension);
	}
}
