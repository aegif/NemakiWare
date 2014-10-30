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
package jp.aegif.nemaki.repository;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.server.shared.BasicAuthCallContextHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Context handler class to do basic authentication
 */
public class DefaultContextHandler extends BasicAuthCallContextHandler {

	private static final long serialVersionUID = -8877261669069241258L;
	private static final Log log = LogFactory.getLog(DefaultContextHandler.class);

	/**
	 * Constructor. Initialize authenticationService here.
	 */
	public DefaultContextHandler() {
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


		//clear latest change cache
		WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(request.getSession().getServletContext());
		RequestDurationCacheBean rdc = context.getBean("requestDurationCache", RequestDurationCacheBean.class);
		rdc.getLatestChangeCache().clear();

		return ctxMap;
	}
}
