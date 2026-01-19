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
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class AuthenticationFilter implements Filter {

	private PropertyManager propertyManager;
	private AuthenticationService authenticationService;
	private RepositoryInfoMap repositoryInfoMap;
	private PrincipalService principalService;
	private final String TOKEN_FALSE = "false";
	
	// ObjectMapper for RFC 7807 ProblemDetail serialization (thread-safe, reusable)
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private  Log log = LogFactory.getLog(AuthenticationFilter.class);

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest hreq = (HttpServletRequest) req;
		HttpServletResponse hres = (HttpServletResponse) res;

		// CORS is handled by SimpleCorsFilter in web.xml - do not add CORS headers here
		// to avoid duplicate header application
		
		// Handle CORS preflight requests (OPTIONS) - bypass authentication
		// SimpleCorsFilter handles the actual CORS headers, we just need to bypass auth
		if ("OPTIONS".equalsIgnoreCase(hreq.getMethod())) {
			log.info("=== CORS: Bypassing authentication for OPTIONS preflight request ===");
			chain.doFilter(req, res);
			return;
		}

		// Check if this is a path that should bypass authentication
		String requestURI = hreq.getRequestURI();
		String servletPath = hreq.getServletPath();
		String pathInfo = hreq.getPathInfo();

		log.info("=== AUTH DEBUG: requestURI=" + requestURI + ", servletPath=" + servletPath + ", pathInfo=" + pathInfo + " ===");

		// UI paths should bypass REST authentication (handled by SPA)
		if (requestURI != null && requestURI.contains("/ui/")) {
			log.info("Bypassing REST authentication for UI path: " + requestURI);
			chain.doFilter(req, res);
			return;
		}

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

		// Bypass authentication for SSO token conversion endpoints (SAML and OIDC)
		if (requestURI != null && (requestURI.contains("/authtoken/saml/convert") || requestURI.contains("/authtoken/oidc/convert"))) {
			log.info("Bypassing authentication for SSO token conversion endpoint: " + requestURI);
			chain.doFilter(req, res);
			return;
		}

		// Bypass authentication for OpenAPI specification endpoints (allow public access to API docs)
		// Note: API v1 CMIS endpoints are at /api/v1/cmis/* to avoid conflict with legacy /api/v1/repo/* endpoints
		if (requestURI != null && (requestURI.contains("/api/v1/cmis/openapi.json") || requestURI.contains("/api/v1/cmis/openapi.yaml"))) {
			log.info("Bypassing authentication for OpenAPI spec endpoint: " + requestURI);
			chain.doFilter(req, res);
			return;
		}

		boolean auth = login(hreq, hres);
		if(auth){
			chain.doFilter(req, res);
		}else{
			log.warn("REST API Unauthorized! : " + hreq.getRequestURI());

			// Check if this is an API v1 endpoint - return RFC 7807 Problem Details response
			// Use requestURI instead of pathInfo because pathInfo may be null for filter URL patterns
			if (requestURI != null && requestURI.contains("/api/v1/")) {
				// Return RFC 7807 compliant 401 response for API v1 endpoints
				hres.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				hres.setContentType("application/problem+json");
				hres.setCharacterEncoding("UTF-8");
				// Support both Basic and Bearer authentication for future JWT/OAuth2 compatibility
				hres.setHeader("WWW-Authenticate", "Basic realm=\"NemakiWare API\", Bearer realm=\"NemakiWare API\"");
				
				// Use ProblemDetail class for consistent RFC 7807 format and proper JSON escaping
				ProblemDetail problem = ProblemDetail.unauthorized(
					"Valid credentials are required to access this resource. Please provide Basic or Bearer authentication credentials.",
					requestURI
				);
				String problemJson = objectMapper.writeValueAsString(problem);
				hres.getWriter().write(problemJson);
				hres.getWriter().flush();
			} else {
				// For legacy endpoints, use standard error response
				hres.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			}
		}
	}

	public boolean login(HttpServletRequest request, HttpServletResponse response){
		final String repositoryId = getRepositoryId(request);
		
		log.info("=== AUTH LOGIN: repositoryId=" + repositoryId + " ===");

		//Create simplified callContext without servlet dependencies
		CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, null, null, null, null);
		
		// Extract basic auth information directly
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Basic ")) {
			try {
				String base64Credentials = authHeader.substring("Basic ".length()).trim();
				String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials));
				String[] values = credentials.split(":", 2);
				if (values.length == 2) {
					ctxt.put(CallContext.USERNAME, values[0]);
					ctxt.put(CallContext.PASSWORD, values[1]);
					log.info("=== AUTH: Basic auth extracted - username=" + values[0] + " ===");
				}
			} catch (Exception e) {
				log.error("Failed to parse Basic auth header", e);
				return false;
			}
		} else {
			log.warn("No Authorization header found");
			return false;
		}
		
		// Add additional context from headers
		ctxt.put(CallContextKey.AUTH_TOKEN, request.getHeader(CallContextKey.AUTH_TOKEN));
		ctxt.put(CallContextKey.AUTH_TOKEN_APP, request.getHeader(CallContextKey.AUTH_TOKEN_APP));

		// auth
		boolean auth = false;
		try {
			if(ObjectUtils.equals(repositoryId, SystemConst.NEMAKI_CONF_DB)){
				auth = authenticationService.loginForNemakiConfDb(ctxt);
			}else{
				auth = authenticationService.login(ctxt);
			}
			log.info("=== AUTH: Authentication result=" + auth + " ===");
		} catch (Exception e) {
			log.error("Authentication error", e);
			return false;
		}

		// If authentication successful, check if user is admin
		if(auth && ctxt.getUsername() != null && principalService != null){
			try {
				// Check if user is admin by getting admin list
				java.util.List<jp.aegif.nemaki.model.User> admins = principalService.getAdmins(repositoryId);
				boolean isAdmin = false;
				if(admins != null){
					for(jp.aegif.nemaki.model.User admin : admins){
						if(admin.getUserId() != null && admin.getUserId().equals(ctxt.getUsername())){
							isAdmin = true;
							break;
						}
					}
				}
				ctxt.put(CallContextKey.IS_ADMIN, isAdmin);
				log.info("=== AUTH: User admin status=" + isAdmin + " ===");
			} catch (Exception e) {
				log.error("Failed to check admin status for user: " + ctxt.getUsername(), e);
			}
		}

		//Add attributes to Jersey @Context parameter
		//TODO hard-coded key
		request.setAttribute("CallContext", ctxt);

		return auth;
	}

	private String getRepositoryId(HttpServletRequest request){
		// Extract path and split manually
		String pathInfo = request.getPathInfo();
		log.info("=== AUTH: getRepositoryId - pathInfo=" + pathInfo + " ===");

		if (pathInfo == null || pathInfo.isEmpty()) {
			log.warn("PathInfo is null or empty");
			return null;
		}

		// Remove leading slash and split
		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}
		String[] pathFragments = pathInfo.split("/");
		log.info("=== AUTH: pathFragments=" + java.util.Arrays.toString(pathFragments) + " ===");

        if(pathFragments.length > 0){
        	// Handle API v1 path pattern: /v1/repo/{repositoryId}/...
        	// pathFragments = ["v1", "repo", "bedroom", "renditions", ...]
        	if("v1".equals(pathFragments[0])){
        		if(pathFragments.length > 2 && ApiType.REPO.equals(pathFragments[1]) && StringUtils.isNotBlank(pathFragments[2])){
        			String repositoryId = pathFragments[2];
        			log.info("=== AUTH: Found repositoryId from API v1 path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			log.warn("Could not extract repositoryId from API v1 path: " + java.util.Arrays.toString(pathFragments));
        		}
        	}else if(ApiType.REPO.equals(pathFragments[0])){
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.info("=== AUTH: Found repositoryId from repo path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			log.warn("repositoryId is not specified in URI.");
        		}
        	}else if(ApiType.ALL.equals(pathFragments[0])){
        		String superUserId = repositoryInfoMap.getSuperUsers().getId();
        		log.info("=== AUTH: Using superuser ID for /all/ path=" + superUserId + " ===");
        		return superUserId;
        	}else if("repositories".equals(pathFragments[0])){
        		// Handle /api/v1/repositories/{repositoryId}/... pattern
        		// pathFragments = ["repositories", "{repositoryId}", ...]
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.info("=== AUTH: Found repositoryId from /repositories/ path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			// For /repositories (list all) endpoint, use superuser
        			String superUserId = repositoryInfoMap.getSuperUsers().getId();
        			log.info("=== AUTH: Using superuser ID for /repositories list path=" + superUserId + " ===");
        			return superUserId;
        		}
        	}else{
        		// For paths like /user/bedroom, /group/bedroom, etc.
        		// The repository ID is typically the second fragment
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.info("=== AUTH: Found repositoryId from standard REST path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			log.warn("Could not extract repositoryId from path: " + java.util.Arrays.toString(pathFragments));
        		}
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

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}
