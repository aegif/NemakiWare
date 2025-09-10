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
		
		System.err.println("*** CMIS SERVICE WRAPPER CREATETYPE: CALLED ***");
		System.err.println("*** REPOSITORY ID: " + repositoryId + " ***");
		System.err.println("*** TYPE ID: " + (type != null ? type.getId() : "null") + " ***");
		System.err.println("*** TYPE DISPLAY NAME: " + (type != null ? type.getDisplayName() : "null") + " ***");
		System.err.println("*** WRAPPED SERVICE CLASS: " + getWrappedService().getClass().getName() + " ***");
		System.err.println("*** WRAPPED SERVICE HASH: " + getWrappedService().hashCode() + " ***");
		System.err.println("*** CALL CONTEXT: " + (getCallContext() != null ? getCallContext().getClass().getName() : "null") + " ***");
		System.err.println("*** USERNAME: " + (getCallContext() != null ? getCallContext().getUsername() : "null") + " ***");
		System.err.println("*** THREAD: " + Thread.currentThread().getName() + " ***");
		System.err.println("*** TIMESTAMP: " + System.currentTimeMillis() + " ***");
		
		// Check type properties for contamination debugging
		if (type != null && type.getPropertyDefinitions() != null) {
			System.err.println("*** TYPE PROPERTIES COUNT: " + type.getPropertyDefinitions().size() + " ***");
			for (String propertyId : type.getPropertyDefinitions().keySet()) {
				org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef = type.getPropertyDefinitions().get(propertyId);
				System.err.println("*** INPUT PROPERTY: " + propertyId + " -> ID: " + propDef.getId() + " ***");
			}
		}
		
		// Delegate to the wrapped NemakiWare CmisService
		org.apache.chemistry.opencmis.commons.definitions.TypeDefinition result = getWrappedService().createType(repositoryId, type, extension);
		
		System.err.println("*** CMIS SERVICE WRAPPER CREATETYPE: COMPLETED ***");
		System.err.println("*** RESULT TYPE ID: " + (result != null ? result.getId() : "null") + " ***");
		
		// Check result properties for contamination
		if (result != null && result.getPropertyDefinitions() != null) {
			System.err.println("*** RESULT PROPERTIES COUNT: " + result.getPropertyDefinitions().size() + " ***");
			for (String propertyId : result.getPropertyDefinitions().keySet()) {
				org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propDef = result.getPropertyDefinitions().get(propertyId);
				System.err.println("*** RESULT PROPERTY: " + propertyId + " -> ID: " + propDef.getId() + " ***");
				if (!propertyId.equals(propDef.getId())) {
					System.err.println("*** CONTAMINATION DETECTED: Key=" + propertyId + " but ID=" + propDef.getId() + " ***");
				}
			}
		}
		
		System.err.println("*** RETURNING CREATETYPE RESULT ***");
		
		return result;
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
		
		System.err.println("!!! CMIS SERVICE WRAPPER CREATEFOLDER: CALLED !!!");
		System.err.println("!!! REPOSITORY ID: " + repositoryId + " !!!");
		System.err.println("!!! FOLDER ID: " + folderId + " !!!");
		System.err.println("!!! WRAPPED SERVICE: " + getWrappedService().getClass().getName() + " !!!");
		
		// Delegate to the wrapped NemakiWare CmisService
		String result = getWrappedService().createFolder(repositoryId, properties, folderId, policies, addAces, removeAces, extension);
		
		System.err.println("!!! CMIS SERVICE WRAPPER CREATEFOLDER: COMPLETED WITH RESULT: " + result + " !!!");
		
		return result;
	}
}
