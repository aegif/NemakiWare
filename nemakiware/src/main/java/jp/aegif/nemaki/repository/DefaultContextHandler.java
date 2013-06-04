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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.repository;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.service.cmis.AuthenticationService;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.shared.BasicAuthCallContextHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Context handler class to do basic authentication
 */
public class DefaultContextHandler extends BasicAuthCallContextHandler {

	private static final long serialVersionUID = -8877261669069241258L;

	/**
	 * Authentication service, that allows to login a user.
	 */
	private AuthenticationService authenticationService;

	/**
	 * Constructor. Initialize authenticationService here.
	 */
	public DefaultContextHandler() {
		ApplicationContext context = new ClassPathXmlApplicationContext(
				"applicationContext.xml");
		authenticationService = (AuthenticationService) context
				.getBean("AuthenticationService");
	}

	/**
	 * Return call context map. Throw exception if denied.
	 * 
	 * @throws CmisPermissionDeniedException
	 */
	public Map<String, String> getCallContextMap(HttpServletRequest request) {

		// Call superclass to get user and password via basic authentication.
		Map<String, String> ctxMap = super.getCallContextMap(request);
		if (null == ctxMap)
			throw new CmisPermissionDeniedException("Authentication required");

		authenticationService.login(ctxMap.get(CallContext.USERNAME),
				ctxMap.get(CallContext.PASSWORD));
		return ctxMap;
	}

}
