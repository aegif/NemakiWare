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

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

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
	 * Jakarta EE compatible implementation with Basic Authentication.
	 * Note: Temporarily removed @Override due to signature mismatch with Jakarta converted OpenCMIS
	 *
	 * @throws CmisPermissionDeniedException
	 */
	public Map<String, String> getCallContextMap(HttpServletRequest request) {
		log.info("=== Jakarta EE HTTP Request Processing ===");
		log.info("Request URI: " + request.getRequestURI());
		log.info("Content Type: " + request.getContentType());
		log.info("Method: " + request.getMethod());
		
		// Initialize context map
		Map<String, String> ctxMap = new HashMap<String, String>();
		
		// Extract Basic Authentication manually for Jakarta EE compatibility
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Basic ")) {
			try {
				String base64Credentials = authHeader.substring("Basic".length()).trim();
				String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials), "UTF-8");
				String[] values = credentials.split(":", 2);
				if (values.length == 2) {
					ctxMap.put(CallContext.USERNAME, values[0]);
					ctxMap.put(CallContext.PASSWORD, values[1]);
					log.info("Basic authentication extracted for user: " + values[0]);
				}
			} catch (Exception e) {
				log.warn("Failed to parse Basic authentication header", e);
			}
		}
		
		//SSO header
		try {
			final ApplicationContext applicationContext = SpringContext.getApplicationContext();
			PropertyManager manager = applicationContext.getBean("propertyManager", PropertyManager.class);
			String proxyHeaderKey = manager.readValue(PropertyKey.EXTERNAL_AUTHENTICATION_PROXY_HEADER);
			if(StringUtils.isNotBlank(proxyHeaderKey)){
				String proxyHeaderVal = request.getHeader(proxyHeaderKey);
				ctxMap.put(proxyHeaderKey, proxyHeaderVal);
				if(StringUtils.isNotBlank(proxyHeaderVal)){
					ctxMap.put(CallContext.USERNAME, proxyHeaderVal);
					log.info("SSO authentication extracted for user: " + proxyHeaderVal);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to process SSO headers", e);
		}

		//Nemaki auth token
		ctxMap.put(CallContextKey.AUTH_TOKEN, request.getHeader(CallContextKey.AUTH_TOKEN));
		ctxMap.put(CallContextKey.AUTH_TOKEN_APP, request.getHeader(CallContextKey.AUTH_TOKEN_APP));
		
		log.info("Call context map created with " + ctxMap.size() + " entries");
		return ctxMap;
	}
}