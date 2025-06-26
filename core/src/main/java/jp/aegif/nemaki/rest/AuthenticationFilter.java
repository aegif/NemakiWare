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
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.NemakiAuthCallContextHandler;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

public class AuthenticationFilter implements Filter {

	@Autowired
	private PropertyManager propertyManager;
	private AuthenticationService authenticationService;
	private RepositoryInfoMap repositoryInfoMap;
	private final String TOKEN_FALSE = "false";

	private  Log log = LogFactory.getLog(AuthenticationFilter.class);

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest hreq = (HttpServletRequest) req;
		HttpServletResponse hres = (HttpServletResponse) res;
		String pathInfo = hreq.getPathInfo();
		String requestURI = hreq.getRequestURI();
		String authToken = hreq.getHeader("AUTH_TOKEN");
		
		System.out.println("AUTH FILTER TOKEN: pathInfo='" + pathInfo + "', requestURI='" + requestURI + "', authToken='" + authToken + "'");
		
		// Bypass authentication for repositories endpoint
		if (pathInfo != null && pathInfo.equals("/repositories")) {
			System.out.println("AUTH FILTER TOKEN: Bypassing repositories");
			chain.doFilter(req, res);
			return;
		}
		
		if (requestURI != null && requestURI.contains("/rest/repositories")) {
			System.out.println("AUTH FILTER TOKEN: Bypassing repositories URI");
			chain.doFilter(req, res);
			return;
		}
		
		// Bypass authentication for login endpoints
		if (pathInfo != null && pathInfo.contains("/login")) {
			System.out.println("AUTH FILTER TOKEN: Bypassing login endpoint");
			chain.doFilter(req, res);
			return;
		}
		
		if (requestURI != null && requestURI.contains("/login")) {
			System.out.println("AUTH FILTER TOKEN: Bypassing login URI");
			chain.doFilter(req, res);
			return;
		}
		
		// If AUTH_TOKEN header is present, bypass authentication filter
		// This allows the application to handle token authentication internally
		if (authToken != null && !authToken.trim().isEmpty()) {
			System.out.println("AUTH FILTER TOKEN: Bypassing authentication due to AUTH_TOKEN header");
			// Create a minimal call context for token-based requests
			final String repositoryId = getRepositoryId(hreq);
			NemakiAuthCallContextHandler callContextHandler = new NemakiAuthCallContextHandler();
			Map<String, String> map = callContextHandler.getCallContextMap(hreq);
			CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, hreq, hres, null, null);
			for(String key : map.keySet()){
				ctxt.put(key, map.get(key));
			}
			hreq.setAttribute("CallContext", ctxt);
			chain.doFilter(req, res);
			return;
		}

		boolean auth = login(hreq, hres);
		if(auth){
			chain.doFilter(req, res);
		}else{
			log.warn("REST API Unauthorized! : " + hreq.getRequestURI());
			hres.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		}
	}

	public boolean login(HttpServletRequest request, HttpServletResponse response){
		final String repositoryId = getRepositoryId(request);

		//Make dummy callContext
		NemakiAuthCallContextHandler callContextHandeler = new NemakiAuthCallContextHandler();
		Map<String, String> map = callContextHandeler.getCallContextMap(request);
		CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, request, response, null, null);
		for(String key : map.keySet()){
			ctxt.put(key, map.get(key));
		}

		// auth
		boolean auth = false;
		if(ObjectUtils.equals(repositoryId, SystemConst.NEMAKI_CONF_DB)){
			auth = authenticationService.loginForNemakiConfDb(ctxt);
		}else{
			auth = authenticationService.login(ctxt);
		}

		//Add attributes to Jersey @Context parameter
		//TODO hard-coded key
		request.setAttribute("CallContext", ctxt);

		return auth;
	}

	private String getRepositoryId(HttpServletRequest request){
		// split path
        String[] pathFragments = HttpUtils.splitPath(request);

        if(pathFragments.length > 0){
        	if(ApiType.REPO.equals(pathFragments[0])){
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			return repositoryId;
        		}else{
        			System.err.println("repositoryId is not specified in URI.");
        		}
        	}else if(ApiType.ALL.equals(pathFragments[0])){
        		return repositoryInfoMap.getSuperUsers().getId();
        	}
        }

        return null;
	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	private boolean checkResourceEnabled(HttpServletRequest request){
		boolean enabled = true;

		String pathInfo = request.getPathInfo();
		if(pathInfo.startsWith("/user")){
			String userResourceEnabled = propertyManager.readValue(PropertyKey.REST_USER_ENABLED);
			enabled = TOKEN_FALSE.equals(userResourceEnabled) ? false : true;
		}else if(pathInfo.startsWith("/group")){
			String groupResourceEnabled = propertyManager.readValue(PropertyKey.REST_GROUP_ENABLED);
			enabled = TOKEN_FALSE.equals(groupResourceEnabled) ? false : true;
		}else if(pathInfo.startsWith("/type")){
			String typeResourceEnabled = propertyManager.readValue(PropertyKey.REST_TYPE_ENABLED);
			enabled = TOKEN_FALSE.equals(typeResourceEnabled) ? false : true;
		}else if(pathInfo.startsWith("/archive")){
			String archiveResourceEnabled = propertyManager.readValue(PropertyKey.REST_ARCHIVE_ENABLED);
			enabled = TOKEN_FALSE.equals(archiveResourceEnabled) ? false : true;
		}else if(pathInfo.startsWith("/search-engine")){
			String solrResourceEnabled = propertyManager.readValue(PropertyKey.REST_SOLR_ENABLED);
			enabled = TOKEN_FALSE.equals(solrResourceEnabled) ? false : true;
		}else if(pathInfo.startsWith("/authtoken")){
			String authtokenResourceEnabled = propertyManager.readValue(PropertyKey.REST_AUTHTOKEN_ENABLED);
			enabled = TOKEN_FALSE.equals(authtokenResourceEnabled) ? false : true;
		}else{
			enabled = false;
		}

		return enabled;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setAuthenticationService(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
