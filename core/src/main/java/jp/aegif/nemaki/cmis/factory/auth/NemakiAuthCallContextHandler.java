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

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Context handler class to do basic authentication
 */
public class NemakiAuthCallContextHandler extends org.apache.chemistry.opencmis.server.shared.BasicAuthCallContextHandler {

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
		ctxMap.put(CallContextKey.AUTH_TOKEN, request.getHeader(CallContextKey.AUTH_TOKEN));
		ctxMap.put(CallContextKey.AUTH_TOKEN_APP, request.getHeader(CallContextKey.AUTH_TOKEN_APP));
		return ctxMap;
	}
}