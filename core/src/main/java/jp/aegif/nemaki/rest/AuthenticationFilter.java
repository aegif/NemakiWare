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
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

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

		// Check if this is an /all/ path that should bypass authentication
		String requestURI = hreq.getRequestURI();
		String servletPath = hreq.getServletPath();
		String pathInfo = hreq.getPathInfo();
		
		log.info("=== AUTH DEBUG: requestURI=" + requestURI + ", servletPath=" + servletPath + ", pathInfo=" + pathInfo + " ===");
		
		// Check various URI patterns for /all/ paths
		if (requestURI != null && requestURI.contains("/rest/all/")) {
			log.info("Bypassing authentication for /rest/all/ URI: " + requestURI);
			chain.doFilter(req, res);
			return;
		}
		
		// For servlet mappings, pathInfo might be null, so check servletPath
		if (servletPath != null && servletPath.contains("/all/")) {
			log.info("Bypassing authentication for /all/ servletPath: " + servletPath);
			chain.doFilter(req, res);
			return;
		}
		
		if (pathInfo != null && pathInfo.startsWith("/all/")) {
			log.info("Bypassing authentication for /all/ pathInfo: " + pathInfo);
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

		//Create simplified callContext without servlet dependencies
		CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, null, null, null, null);
		
		// Extract basic auth information directly
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Basic ")) {
			String base64Credentials = authHeader.substring("Basic ".length()).trim();
			String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials));
			String[] values = credentials.split(":", 2);
			if (values.length == 2) {
				ctxt.put(CallContext.USERNAME, values[0]);
				ctxt.put(CallContext.PASSWORD, values[1]);
			}
		}
		
		// Add additional context from headers
		ctxt.put("AUTH_TOKEN", request.getHeader("AUTH_TOKEN"));
		ctxt.put("AUTH_TOKEN_APP", request.getHeader("AUTH_TOKEN_APP"));

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
		// Extract path and split manually
		String pathInfo = request.getPathInfo();
		if (pathInfo == null || pathInfo.isEmpty()) {
			return null;
		}
		
		// Remove leading slash and split
		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}
		String[] pathFragments = pathInfo.split("/");

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
        	}else if("repositories".equals(pathFragments[0])){
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
