/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.factory.auth;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;

/**
 * Context handler class to do basic authentication
 */
public class NemakiAuthCallContextHandler extends org.apache.chemistry.opencmis.server.shared.BasicAuthCallContextHandler{

	private static final long serialVersionUID = -8877261669069241258L;
	private static final Log log = LogFactory.getLog(NemakiAuthCallContextHandler.class);
	
	/**
	 * Constructor. Initialize authenticationService here.
	 */
	public NemakiAuthCallContextHandler() {
	}

	/**
	 * Return call context map. Throw exception if denied.
	 *
	 * @throws CmisPermissionDeniedException
	 */
	@Override
	public Map<String, String> getCallContextMap(HttpServletRequest request) {
		// Call superclass to get user and password via basic authentication.
		Map<String, String> ctxMap = super.getCallContextMap(request);
		if(ctxMap == null){
			ctxMap = new HashMap<String, String>();
		}
		
		//SSO header
		final ApplicationContext applicationContext = SpringContext.getApplicationContext();
		PropertyManager manager = applicationContext.getBean("propertyManager", PropertyManager.class);
		String proxyHeaderKey = manager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_PROXY_HEADER);
		String proxyHeaderVal = request.getHeader(proxyHeaderKey);
		ctxMap.put(proxyHeaderKey, proxyHeaderVal);
		if(StringUtils.isNotBlank(proxyHeaderVal)){
			ctxMap.put(CallContext.USERNAME, proxyHeaderVal);
		}
		
		//Nemaki auth token
		ctxMap.put(CallContextKey.AUTH_TOKEN, request.getHeader(CallContextKey.AUTH_TOKEN));
		ctxMap.put(CallContextKey.AUTH_TOKEN_APP, request.getHeader(CallContextKey.AUTH_TOKEN_APP));
		
		//Nemaki REST auth header
		request.getHeader(CallContextKey.REST_REPOSITORY_ID_FOR_AUTH);
		//ctxMap.put(CallContextKey.REST_REPOSITORY_ID_FOR_AUTH, request.getHeader(arg0))
		
		return ctxMap;
	}
}