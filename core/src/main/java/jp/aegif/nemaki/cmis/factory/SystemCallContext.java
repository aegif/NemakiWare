package jp.aegif.nemaki.cmis.factory;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

import jp.aegif.nemaki.util.constant.PrincipalId;

public class SystemCallContext extends CallContextImpl{

	public SystemCallContext(String binding, CmisVersion cmisVersion, String repositoryId,
			ServletContext servletContext, HttpServletRequest request, HttpServletResponse response,
			CmisServiceFactory factory, TempStoreOutputStreamFactory streamFactory) {
		super(binding, cmisVersion, repositoryId, servletContext, request, response, factory, streamFactory);
	}
	
	public SystemCallContext(String repositoryId){
		this(BINDING_ATOMPUB, CmisVersion.CMIS_1_1, repositoryId, null, null, null, null, null);
		this.put(this.USERNAME, PrincipalId.SYSTEM_IN_DB);
	}

}
