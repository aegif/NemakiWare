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

import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ObjectUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import jp.aegif.nemaki.config.ObjectMapperFactory;

public class AuthenticationFilter implements Filter {

	private PropertyManager propertyManager;
	private AuthenticationService authenticationService;
	private TokenService tokenService;
	private RepositoryInfoMap repositoryInfoMap;
	private PrincipalService principalService;
	private final String TOKEN_FALSE = "false";

	// ObjectMapper for RFC 7807 ProblemDetail serialization (thread-safe, reusable)
	private static final ObjectMapper objectMapper = ObjectMapperFactory.createDefaultObjectMapper();

	private  Log log = LogFactory.getLog(AuthenticationFilter.class);

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest hreq = (HttpServletRequest) req;
		HttpServletResponse hres = (HttpServletResponse) res;

		// Set audit request context for capturing client IP
		AuditLogger.setRequestContext(hreq);

		try {
			// CORS is handled by SimpleCorsFilter in web.xml - do not add CORS headers here
			// to avoid duplicate header application

			// Handle CORS preflight requests (OPTIONS) - bypass authentication
			// SimpleCorsFilter handles the actual CORS headers, we just need to bypass auth
			if ("OPTIONS".equalsIgnoreCase(hreq.getMethod())) {
				log.debug("=== CORS: Bypassing authentication for OPTIONS preflight request ===");
				chain.doFilter(req, res);
				return;
			}

			// Check if this is a path that should bypass authentication
			String requestURI = hreq.getRequestURI();
			String servletPath = hreq.getServletPath();
			String pathInfo = hreq.getPathInfo();

			log.debug("=== AUTH DEBUG: requestURI=" + requestURI + ", servletPath=" + servletPath + ", pathInfo=" + pathInfo + " ===");

			// The public endpoints this filter is mapped over, matched whole (3.4.1, zero-trust P0). A part of the URI was
			// enough before: any path with "/ui/" in it skipped authentication and reached the resources unauthenticated
			// (/rest/repo/ui/user/list — the UI is served by its own servlet, outside this filter), and the other public
			// names were found wherever they stood in the URI.
			String publicEndpoint = publicEndpoint(hreq);
			if (publicEndpoint != null) {
				log.debug("Bypassing authentication for " + publicEndpoint + ": " + requestURI);
				chain.doFilter(req, res);
				return;
			}

			// NOTE: MCP cloud login completion endpoint requires authentication
			// The authenticated user's ID is used to complete the login, ensuring
			// only the logged-in user can bind their identity to the MCP session

			// Check if the target REST resource is disabled by configuration.
			// Reject early to avoid wasting resources on authentication for disabled endpoints.
			if (!checkResourceEnabled(hreq)) {
				String resourceName = extractResourceName(pathInfo);
				log.warn("REST resource disabled by configuration: " + resourceName + " (URI: " + requestURI + ")");
				hres.sendError(HttpServletResponse.SC_FORBIDDEN, "Resource is disabled by server configuration");
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
		} finally {
			// Clear audit request context
			AuditLogger.clearRequestContext();
		}
	}

	/**
	 * The endpoints that must answer before anyone has logged in, by the path the container routes the request to —
	 * the servlet path and the path info, decoded and normalised, without the context path, the query, or path
	 * parameters — each matched as a whole. A request whose path is not one of these is authenticated.
	 *
	 * <p>Only the paths web.xml maps this filter onto reach it; which REST resources that leaves outside is read by
	 * {@code RestResourcesAreBehindTheFilterTest}. {@code /rest/all/readiness} (the load-balancer probe) is outside and
	 * must stay so. {@code /rest/all/repositories} and {@code /rest/auth/config} are outside too; they stay listed so that
	 * mapping them would not lock out the login page. The ingest webhook receivers verify the webhook's HMAC themselves —
	 * but for the two handshakes a provider sends before any signature exists (Microsoft Graph's validationToken, Dropbox's
	 * challenge), which the controller bounds instead. Their {@code /subscribe} routes are an administrator's and are not
	 * public (connector ids may be e.g. {@code subscribe-slack}, so the route is matched by its segments, not by a
	 * substring). A trailing slash is not matched: {@code /api/v1/cmis/openapi.json/} is authenticated.
	 *
	 * <p>The API v1 application's own filter ({@code ApiAuthenticationFilter}) reads this table too.
	 */
	private static final java.util.Map<java.util.regex.Pattern, String> PUBLIC_ENDPOINTS;
	static {
		java.util.Map<java.util.regex.Pattern, String> m = new java.util.LinkedHashMap<>();
		m.put(java.util.regex.Pattern.compile("/rest/all/(repositories|build-info)"), "the login page's repository list and version");
		m.put(java.util.regex.Pattern.compile("/rest/auth/config"), "the SSO button settings");
		m.put(java.util.regex.Pattern.compile("/rest/repo/[^/]+/authtoken/[^/]+/login"), "the password login");
		m.put(java.util.regex.Pattern.compile("/rest/repo/[^/]+/authtoken/(saml|oidc|google|microsoft)/convert"), "an SSO token conversion");
		m.put(java.util.regex.Pattern.compile("/rest/repo/[^/]+/webauthn/authenticate/(begin|complete)"), "a passkey login");
		m.put(java.util.regex.Pattern.compile("/api/v1/cmis/auth/repositories/[^/]+/(login|saml|oidc)"), "an API v1 login");
		m.put(java.util.regex.Pattern.compile("/api/v1/cmis/openapi\\.(json|yaml)"), "the OpenAPI document");
		m.put(java.util.regex.Pattern.compile("/api/v1/setup/.*"), "the setup wizard");
		m.put(java.util.regex.Pattern.compile("/api/v1/ingest-webhook/[^/]+"), "an ingest webhook receiver");
		PUBLIC_ENDPOINTS = java.util.Collections.unmodifiableMap(m);
	}

	/** What the request reaches without logging in — one of {@link #PUBLIC_ENDPOINTS}, named — or null. */
	static String publicEndpoint(HttpServletRequest request) {
		return publicPath((request.getServletPath() == null ? "" : request.getServletPath())
				+ (request.getPathInfo() == null ? "" : request.getPathInfo()));
	}

	/** The public endpoint a path within the application is — matched whole — or null. */
	public static String publicPath(String path) {
		for (java.util.Map.Entry<java.util.regex.Pattern, String> endpoint : PUBLIC_ENDPOINTS.entrySet()) {
			if (endpoint.getKey().matcher(path).matches()) {
				return endpoint.getValue();
			}
		}
		return null;
	}

	public boolean login(HttpServletRequest request, HttpServletResponse response){
		final String repositoryId = getRepositoryId(request);

		log.debug("=== AUTH LOGIN: repositoryId=" + repositoryId + " ===");

		//Create simplified callContext without servlet dependencies
		CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId, null, null, null, null, null);

		// Check for AUTH_TOKEN header first (NemakiWare token-based authentication)
		// Support both legacy header name (nemaki_auth_token) and standard header name (AUTH_TOKEN)
		String authToken = request.getHeader(CallContextKey.AUTH_TOKEN);
		if (authToken == null || authToken.isEmpty()) {
			// Fallback to standard header name used by UI
			authToken = request.getHeader("AUTH_TOKEN");
		}
		// Also check for HttpOnly cookie (more secure than header for browser clients)
		if (authToken == null || authToken.isEmpty()) {
			authToken = getAuthTokenFromCookie(request);
		}
		String authTokenApp = request.getHeader(CallContextKey.AUTH_TOKEN_APP);
		if (authTokenApp == null || authTokenApp.isEmpty()) {
			authTokenApp = request.getHeader("AUTH_TOKEN_APP");
		}
		String app = (authTokenApp == null) ? "" : authTokenApp;

		if (authToken != null && !authToken.isEmpty()) {
			// Token-based authentication - validate token and get username
			log.debug("=== AUTH: AUTH_TOKEN header found, validating token for repository: " + repositoryId + " ===");

			if (tokenService != null) {
				String userName = tokenService.validateToken(app, repositoryId, authToken);
				if (userName != null) {
					log.debug("=== AUTH: Token validated successfully ===");
					ctxt.put(CallContext.USERNAME, userName);
					ctxt.put(CallContextKey.AUTH_TOKEN, authToken);
					ctxt.put(CallContextKey.AUTH_TOKEN_APP, authTokenApp);
				} else {
					log.debug("=== AUTH: Invalid or expired AUTH_TOKEN for repository: " + repositoryId + ", app: " + app + " ===");
					return false;
				}
			} else {
				log.warn("=== AUTH: TokenService not available for token validation ===");
				return false;
			}
		} else {
			// Check for X-API-Key header (persistent API key authentication for MCP clients)
			String apiKey = request.getHeader("X-API-Key");
			if (apiKey == null || apiKey.isEmpty()) {
				// Fallback to lowercase header name
				apiKey = request.getHeader("x-api-key");
			}

			if (apiKey != null && !apiKey.isEmpty()) {
				// API Key authentication - set it in context for AuthenticationService to validate
				log.debug("=== AUTH: X-API-Key header found, passing to AuthenticationService ===");
				ctxt.put(CallContextKey.API_KEY, apiKey);
				// AuthenticationService.loginWithApiKey() will validate the key and set username
			} else {
				// Check Authorization header for Basic or Bearer authentication
				String authHeader = request.getHeader("Authorization");
				if (authHeader != null && authHeader.startsWith("Bearer ")) {
					// Bearer token authentication (standard OAuth2/JWT format)
					String token = authHeader.substring("Bearer ".length()).trim();
					if (token.isEmpty()) {
						log.warn("Empty Bearer token");
						return false;
					}

					log.debug("=== AUTH: Bearer token found, validating for repository: " + repositoryId + " ===");

					if (tokenService != null) {
						String userName = tokenService.validateToken(app, repositoryId, token);
						if (userName != null) {
							log.debug("=== AUTH: Bearer token validated successfully ===");
							ctxt.put(CallContext.USERNAME, userName);
							ctxt.put(CallContextKey.AUTH_TOKEN, token);
						} else {
							log.debug("=== AUTH: Invalid or expired Bearer token for repository: " + repositoryId + " ===");
							return false;
						}
					} else {
						log.warn("=== AUTH: TokenService not available for Bearer token validation ===");
						return false;
					}
				} else if (authHeader != null && authHeader.startsWith("Basic ")) {
					// Basic authentication (username:password)
					try {
						String base64Credentials = authHeader.substring("Basic ".length()).trim();
						String credentials = new String(java.util.Base64.getDecoder().decode(base64Credentials), java.nio.charset.StandardCharsets.UTF_8);
						String[] values = credentials.split(":", 2);
						if (values.length == 2) {
							ctxt.put(CallContext.USERNAME, values[0]);
							ctxt.put(CallContext.PASSWORD, values[1]);
							log.debug("=== AUTH: Basic auth header parsed ===");
						}
					} catch (Exception e) {
						log.error("Failed to parse Basic auth header", e);
						return false;
					}
				} else {
					log.warn("No Authorization header, AUTH_TOKEN, or X-API-Key found");
					return false;
				}
			}
		}

		// auth
		boolean auth = false;
		try {
			if(ObjectUtils.equals(repositoryId, SystemConst.NEMAKI_CONF_DB)){
				auth = authenticationService.loginForNemakiConfDb(ctxt);
			}else{
				auth = authenticationService.login(ctxt);
			}
			log.debug("=== AUTH: Authentication result=" + auth + " ===");
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
				log.debug("=== AUTH: User admin status=" + isAdmin + " ===");
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
		log.debug("=== AUTH: getRepositoryId - pathInfo=" + pathInfo + " ===");

		if (pathInfo == null || pathInfo.isEmpty()) {
			log.warn("PathInfo is null or empty");
			return null;
		}

		// Remove leading slash and split
		if (pathInfo.startsWith("/")) {
			pathInfo = pathInfo.substring(1);
		}
		String[] pathFragments = pathInfo.split("/");
		log.debug("=== AUTH: pathFragments=" + java.util.Arrays.toString(pathFragments) + " ===");

        // OData binding (servlet mapped to /odata/*): pathInfo is
        // {repositoryId}/{rest...}. Detect via requestURI because pathInfo
        // alone has the same shape as /user/{repo} below, and we'd otherwise
        // pick the second fragment ("$metadata", "Documents", ...) as the
        // repositoryId — which is what made every authenticated OData call
        // 401 before this branch existed.
        String requestUri = request.getRequestURI();
        if (requestUri != null && requestUri.contains("/odata/")
                && pathFragments.length > 0 && StringUtils.isNotBlank(pathFragments[0])) {
            String repositoryId = pathFragments[0];
            log.debug("=== AUTH: Found repositoryId from /odata/ path=" + repositoryId + " ===");
            return repositoryId;
        }

        if(pathFragments.length > 0){
        	// Handle API v1 path pattern: /v1/repo/{repositoryId}/...
        	// pathFragments = ["v1", "repo", "bedroom", "renditions", ...]
        	if("v1".equals(pathFragments[0])){
        		if(pathFragments.length > 2 && ApiType.REPO.equals(pathFragments[1]) && StringUtils.isNotBlank(pathFragments[2])){
        			String repositoryId = pathFragments[2];
        			log.debug("=== AUTH: Found repositoryId from API v1 path=" + repositoryId + " ===");
        			return repositoryId;
        		}else if(pathFragments.length > 1 && "admin".equals(pathFragments[1])){
        			// /v1/admin/* endpoints are not tied to a repository in their
        			// URL. Only the per-repository admin surfaces — import-profiles
        			// and connector delegation governance — honour a client-named
        			// target repository via the X-Nemaki-Repository header, so a
        			// per-repository admin authenticates against their OWN
        			// repository (no same-name / default-repo carry-over). Every
        			// other /v1/admin/* surface (Purview, lineage,
        			// integration-settings, ingest jobs / scheduler, and the global
        			// connector CRUD catalogue) stays bound to the default
        			// repository and is therefore default-repository-admin only.
        			// NB: connector CRUD lives under /v1/admin/connectors too, but
        			// its controller additionally requires a default-repository
        			// admin, so honouring the header there only enables the
        			// repository-scoped governance sub-paths.
        			String defaultRepo = repositoryInfoMap.getDefaultRepositoryId();
        			boolean repoScopedAdmin = pathFragments.length > 2
        					&& ("import-profiles".equals(pathFragments[2])
        							|| "connectors".equals(pathFragments[2]));
        			if(repoScopedAdmin){
        				String requestedRepo = request.getHeader("X-Nemaki-Repository");
        				if(requestedRepo != null && !requestedRepo.isBlank() && repositoryInfoMap.contains(requestedRepo)){
        					log.debug("=== AUTH: Using client-specified repository for /v1/admin/ path=" + requestedRepo + " ===");
        					return requestedRepo;
        				}
        			}
        			log.debug("=== AUTH: Using default repository for /v1/admin/ path=" + defaultRepo + " ===");
        			return defaultRepo;
        		}else{
        			log.warn("Could not extract repositoryId from API v1 path: " + java.util.Arrays.toString(pathFragments));
        		}
        	}else if(ApiType.REPO.equals(pathFragments[0])){
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.debug("=== AUTH: Found repositoryId from repo path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			log.warn("repositoryId is not specified in URI.");
        		}
        	}else if(ApiType.ALL.equals(pathFragments[0])){
        		String superUserId = repositoryInfoMap.getSuperUsers().getId();
        		log.debug("=== AUTH: Using superuser ID for /all/ path=" + superUserId + " ===");
        		return superUserId;
        	}else if("repositories".equals(pathFragments[0])){
        		// Handle /api/v1/repositories/{repositoryId}/... pattern
        		// pathFragments = ["repositories", "{repositoryId}", ...]
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.debug("=== AUTH: Found repositoryId from /repositories/ path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			// For /repositories (list all) endpoint, use superuser
        			String superUserId = repositoryInfoMap.getSuperUsers().getId();
        			log.debug("=== AUTH: Using superuser ID for /repositories list path=" + superUserId + " ===");
        			return superUserId;
        		}
        	}else if("auth".equals(pathFragments[0])){
        		// Handle /auth/repositories/{repositoryId}/... pattern (API v1 authentication endpoints)
        		// pathFragments = ["auth", "repositories", "{repositoryId}", ...]
        		if(pathFragments.length > 2 && "repositories".equals(pathFragments[1]) && StringUtils.isNotBlank(pathFragments[2])){
        			String repositoryId = pathFragments[2];
        			log.debug("=== AUTH: Found repositoryId from /auth/repositories/ path=" + repositoryId + " ===");
        			return repositoryId;
        		}else{
        			log.warn("Could not extract repositoryId from auth path: " + java.util.Arrays.toString(pathFragments));
        		}
        	}else if("audit".equals(pathFragments[0]) ||
        			"health".equals(pathFragments[0])){
        		// Handle global endpoints that use default repository
        		// (audit, health)
        		String defaultRepo = repositoryInfoMap.getDefaultRepositoryId();
        		log.debug("=== AUTH: Using default repository for global path=" + defaultRepo + " ===");
        		return defaultRepo;
        	}else{
        		// For paths like /user/bedroom, /group/bedroom, etc.
        		// The repository ID is typically the second fragment
        		if(pathFragments.length > 1 && StringUtils.isNotBlank(pathFragments[1])){
        			String repositoryId = pathFragments[1];
        			log.debug("=== AUTH: Found repositoryId from standard REST path=" + repositoryId + " ===");
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
		// No cleanup required
	}

	private boolean checkResourceEnabled(HttpServletRequest request){
		String pathInfo = request.getPathInfo();
		String resourceName = extractResourceName(pathInfo);

		// If we can't determine the resource name, allow the request through
		// (defense in depth: individual endpoints have their own permission checks)
		if (resourceName == null) {
			return true;
		}

		String propertyKey = null;
		switch (resourceName) {
			case "user":
				propertyKey = PropertyKey.REST_USER_ENABLED;
				break;
			case "group":
				propertyKey = PropertyKey.REST_GROUP_ENABLED;
				break;
			case "type":
				propertyKey = PropertyKey.REST_TYPE_ENABLED;
				break;
			case "archive":
				propertyKey = PropertyKey.REST_ARCHIVE_ENABLED;
				break;
			case "search-engine":
				propertyKey = PropertyKey.REST_SOLR_ENABLED;
				break;
			case "authtoken":
				propertyKey = PropertyKey.REST_AUTHTOKEN_ENABLED;
				break;
			case "webhook":
				propertyKey = PropertyKey.WEBHOOK_ENABLED;
				break;
			case "rss":
				propertyKey = PropertyKey.RSS_ENABLED;
				break;
			default:
				// Resource not in the controlled list, allow by default
				return true;
		}

		String value = propertyManager.readValue(propertyKey);
		return !TOKEN_FALSE.equals(value);
	}

	/**
	 * Extract the REST resource name from pathInfo.
	 * Handles the following path patterns:
	 *   /repo/{repositoryId}/{resource}/...  → resource
	 *   /all/{resource}/...                  → resource
	 *   /v1/repo/{repositoryId}/{resource}/... → resource
	 *   /repositories/{repositoryId}/{resource}/... → resource (API v1 CMIS)
	 *
	 * @param pathInfo the servlet path info (may be null)
	 * @return the resource name, or null if it cannot be determined
	 */
	private String extractResourceName(String pathInfo) {
		if (pathInfo == null || pathInfo.isEmpty()) {
			return null;
		}
		String p = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
		String[] parts = p.split("/");
		if (parts.length == 0) {
			return null;
		}

		// /v1/repo/{id}/{resource}/...
		if ("v1".equals(parts[0]) && parts.length > 3 && ApiType.REPO.equals(parts[1])) {
			return parts[3];
		}
		// /repo/{id}/{resource}/...
		if (ApiType.REPO.equals(parts[0]) && parts.length > 2) {
			return parts[2];
		}
		// /all/{resource}/...
		if (ApiType.ALL.equals(parts[0]) && parts.length > 1) {
			return parts[1];
		}
		// /repositories/{id}/{resource}/... (API v1 CMIS endpoints)
		if ("repositories".equals(parts[0]) && parts.length > 2) {
			return parts[2];
		}
		return null;
	}

	/**
	 * Extract authentication token from HttpOnly cookie.
	 * This is the preferred method for browser clients as it's more secure than headers.
	 * 
	 * @param request The HTTP request
	 * @return The auth token from cookie, or null if not found
	 */
	private String getAuthTokenFromCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if ("nemaki_auth_token".equals(cookie.getName())) {
					String value = cookie.getValue();
					if (value != null && !value.isEmpty()) {
						log.debug("=== AUTH: Found auth token in HttpOnly cookie ===");
						return value;
					}
				}
			}
		}
		return null;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setAuthenticationService(AuthenticationService authenticationService) {
		this.authenticationService = authenticationService;
	}

	public void setTokenService(TokenService tokenService) {
		this.tokenService = tokenService;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}
}
