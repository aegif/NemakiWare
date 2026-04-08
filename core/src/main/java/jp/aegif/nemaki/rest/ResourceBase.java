/*****************************************************************************
 Copyright (c) 2013 aegif.

 This file is part of NemakiWare.

 NemakiWare is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 NemakiWare is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with NemakiWare.
 If not, see <http://www.gnu.org/licenses/>.

 Contributors:
 linzhixing(https://github.com/linzhixing) - initial API and implementation
 */
package jp.aegif.nemaki.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

public class ResourceBase {

	private static final ObjectMapper mapper = new ObjectMapper();

	static final String TYPE_GROUP = "group";
	static final String PARENTID = "/";
	static final String SUCCESS = "success";
	static final String FAILURE = "failure";
	static final String DOCNAME_VIEW="_design/_repo";
	static final String VIEW_ALL = "_all_dbs";
	static final String SPACE = "";

	static final String FILEPATH_VIEW = "views.json";

	static final String API_ADD = "add";
	static final String API_REMOVE = "remove";

	static final String FORM_USERNAME = "name";
	static final String FORM_PASSWORD = "password";
	static final String FORM_FIRSTNAME = "firstName";
	static final String FORM_LASTNAME = "lastName";
	static final String FORM_EMAIL = "email";
	static final String FORM_CREATOR = "creator";
	static final String FORM_MODIFIER = "modifier";
	static final String FORM_GROUPNAME = "name";
	static final String FORM_MEMBER_USERS = "users";
	static final String FORM_MEMBER_GROUPS = "groups";
	static final String FORM_ID = "id";

	static final String FORM_NEWPASSWORD = "newPassword";
	static final String FORM_OLDPASSWORD = "oldPassword";

	static final String ITEM_USERID = "userId";
	static final String ITEM_USER = "user";
	static final String ITEM_USERNAME = "userName";
	static final String ITEM_PASSWORD = "password";
	static final String ITEM_NEWPASSWORD = "newPassword";
	static final String ITEM_FIRSTNAME = "firstName";
	static final String ITEM_LASTNAME = "lastName";
	static final String ITEM_EMAIL = "email";
	static final String ITEM_PARENTID = "parentId";
	static final String ITEM_PATH = "path";
	static final String ITEM_TYPE = "type";
	static final String ITEM_CREATOR = "creator";
	static final String ITEM_CREATED = "created";
	static final String ITEM_MODIFIER = "modifier";
	static final String ITEM_MODIFIED = "modified";
	static final String ITEM_IS_ADMIN = "isAdmin";
	static final String ITEM_GROUPID = "groupId";
	static final String ITEM_GROUP = "group";
	static final String ITEM_GROUPNAME = "groupName";
	static final String ITEM_MEMBER_USERS = "users";
	static final String ITEM_MEMBER_GROUPS = "groups";
	static final String ITEM_MEMBER_USERSSIZE = "usersSize";
	static final String ITEM_MEMBER_GROUPSSIZE = "groupsSize";
	static final String ITEM_ALLUSERS = "users";
	static final String ITEM_ALLGROUPS = "groups";
	static final String ITEM_STATUS = "status";
	static final String ITEM_ERROR = "error";
	static final String ITEM_COUCHDBRESPONSE = "couchDbResponse";
	static final String ITEM_COUCHDBRESTURL = "couchDBRestTUrl";
	static final String ITEM_DATABASE = "database";
	static final String ITEM_URL = "url";
	static final String ITEM_DATABASES = "databases";
	static final String ITEM_VIEW = "view";
	static final String ITEM_PROPERTIESFILE = "propertiesFile";
	static final String ITEM_ARCHIVE = "archive";



	//Set daoService
	public ResourceBase(){

	}

	//Utility methods
	@SuppressWarnings("unchecked")
	protected JSONArray addErrMsg(JSONArray errMsg, String item, String msg){
		JSONObject obj = new JSONObject();
		obj.put(item, msg);
		errMsg.add(obj);
		return errMsg;
	}
	@SuppressWarnings("unchecked")
	protected JSONObject makeResult(boolean status, JSONObject result, JSONArray errMsg){
		if(status && errMsg.size() == 0){
			result.put(ITEM_STATUS, SUCCESS);
		}else{
			result.put(ITEM_STATUS, FAILURE);
			result.put(ITEM_ERROR, errMsg);
		}
		return result;
	}

	protected boolean checkAdmin(JSONArray errMsg, HttpServletRequest request){
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		
		// Check if callContext is null
		if (callContext == null) {
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, "unknown");
			return false;
		}
		
		Boolean _isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
		boolean isAdmin = _isAdmin != null && _isAdmin;
		if(!isAdmin){
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, callContext.getRepositoryId());
		}
		return isAdmin;
	}

	protected ArrayNode addErrMsg(ArrayNode errMsg, String item, String msg){
		ObjectNode obj = mapper.createObjectNode();
		obj.put(item, msg);
		errMsg.add(obj);
		return errMsg;
	}

	protected ObjectNode makeResult(boolean status, ObjectNode result, ArrayNode errMsg){
		if(status && errMsg.size() == 0){
			result.put(ITEM_STATUS, SUCCESS);
		}else{
			result.put(ITEM_STATUS, FAILURE);
			result.set(ITEM_ERROR, errMsg);
		}
		return result;
	}

	protected boolean checkAdmin(ArrayNode errMsg, HttpServletRequest request){
		CallContext callContext = (CallContext) request.getAttribute("CallContext");
		
		// Check if callContext is null
		if (callContext == null) {
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, "unknown");
			return false;
		}
		
		Boolean _isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
		boolean isAdmin = _isAdmin != null && _isAdmin;
		if(!isAdmin){
			addErrMsg(errMsg, ErrorCode.ERR_ONLY_ALLOWED_FOR_ADMIN, callContext.getRepositoryId());
		}
		return isAdmin;
	}

	protected boolean nonZeroString(String param){
		return param != null && !param.equals("");
	}
	protected GregorianCalendar millisToCalendar(long millis) {
		GregorianCalendar calendar = new GregorianCalendar();
		calendar.setTimeZone(TimeZone.getTimeZone("GMT"));
		calendar.setTimeInMillis(millis);
		return calendar;
	}

	protected void setFirstSignature(HttpServletRequest request, NodeBase nodeBase){
		CallContext callContext = (CallContext)request.getAttribute("CallContext");
		nodeBase.setCreator(callContext.getUsername());
		nodeBase.setCreated(millisToCalendar(System.currentTimeMillis()));
		nodeBase.setModifier(callContext.getUsername());
		nodeBase.setModified(millisToCalendar(System.currentTimeMillis()));
	}

	protected void setModifiedSignature(HttpServletRequest request, NodeBase nodeBase){
		CallContext callContext = (CallContext)request.getAttribute("CallContext");
		nodeBase.setModifier(callContext.getUsername());
		nodeBase.setModified(millisToCalendar(System.currentTimeMillis()));
	}

	protected String getCallContextUsername(HttpServletRequest request) {
		CallContext ctx = (CallContext) request.getAttribute("CallContext");
		return ctx != null ? ctx.getUsername() : null;
	}

	/**
	 * Validate Origin/Referer/X-Requested-With for state-changing requests.
	 * Returns null when valid, otherwise an error key string.
	 */
	protected String validateCsrfProtection(HttpServletRequest request) {
		// Explicit header-based authentication is not vulnerable to browser CSRF in the
		// same way as ambient cookie auth, so keep legacy REST clients compatible.
		if (hasExplicitAuthHeaders(request)) {
			return null;
		}

		OriginParts expected = getEffectiveOriginFromRequest(request);
		String serverHost = expected.host;
		int serverPort = expected.port;
		String scheme = expected.scheme;

		String origin = request.getHeader("Origin");
		if (origin != null && !origin.isEmpty()) {
			if (isOriginValid(origin, scheme, serverHost, serverPort)) {
				return null;
			}
			return "invalid origin";
		}

		String referer = request.getHeader("Referer");
		if (referer != null && !referer.isEmpty()) {
			try {
				java.net.URI refererUri = new java.net.URI(referer);
				String refererScheme = refererUri.getScheme();
				String refererHost = refererUri.getHost();
				int refererPort = refererUri.getPort();
				int normalizedRefererPort = normalizePort(refererScheme, refererPort);
				int normalizedServerPort = normalizePort(scheme, serverPort);

				if (scheme.equals(refererScheme)
						&& serverHost.equals(refererHost)
						&& normalizedServerPort == normalizedRefererPort) {
					return null;
				}
				if (isLocalhostDev(serverHost, refererHost)
						&& normalizedServerPort == normalizedRefererPort) {
					return null;
				}
			} catch (Exception ignored) {
				// ignore
			}
			return "invalid referer";
		}

		String xRequestedWith = request.getHeader("X-Requested-With");
		if ("XMLHttpRequest".equals(xRequestedWith)) {
			return null;
		}
		return "missing origin verification headers";
	}

	private boolean isOriginValid(String origin, String expectedScheme, String expectedHost, int expectedPort) {
		try {
			java.net.URI originUri = new java.net.URI(origin);
			String originScheme = originUri.getScheme();
			String originHost = originUri.getHost();
			int originPort = originUri.getPort();
			if (originScheme == null || originHost == null || expectedScheme == null || expectedHost == null) {
				return false;
			}
			originScheme = originScheme.toLowerCase(Locale.ROOT);
			originHost = originHost.toLowerCase(Locale.ROOT);
			expectedScheme = expectedScheme.toLowerCase(Locale.ROOT);
			expectedHost = expectedHost.toLowerCase(Locale.ROOT);

			int normalizedOriginPort = normalizePort(originScheme, originPort);
			int normalizedExpectedPort = normalizePort(expectedScheme, expectedPort);
			if (!expectedScheme.equals(originScheme)) return false;
			if (!expectedHost.equals(originHost) && !isLocalhostDev(expectedHost, originHost)) return false;
			return normalizedExpectedPort == normalizedOriginPort;
		} catch (Exception e) {
			return false;
		}
	}

	private boolean hasExplicitAuthHeaders(HttpServletRequest request) {
		return hasNonBasicAuthorization(request)
				|| nonEmptyHeader(request, CallContextKey.AUTH_TOKEN)
				|| nonEmptyHeader(request, "AUTH_TOKEN")
				|| nonEmptyHeader(request, CallContextKey.AUTH_TOKEN_APP)
				|| nonEmptyHeader(request, "AUTH_TOKEN_APP")
				|| nonEmptyHeader(request, "X-API-Key");
	}

	private boolean hasNonBasicAuthorization(HttpServletRequest request) {
		String auth = request.getHeader("Authorization");
		if (auth == null || auth.isEmpty()) return false;
		String trimmed = auth.trim();
		// Browser-managed Basic auth remains CSRF-relevant and must not bypass checks.
		return !trimmed.regionMatches(true, 0, "Basic ", 0, 6);
	}

	private boolean nonEmptyHeader(HttpServletRequest request, String headerName) {
		String value = request.getHeader(headerName);
		return value != null && !value.isEmpty();
	}

	private OriginParts getEffectiveOriginFromRequest(HttpServletRequest request) {
		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();

		// RFC 7239 Forwarded (highest priority)
		String forwarded = request.getHeader("Forwarded");
		if (forwarded != null && !forwarded.isEmpty()) {
			OriginParts parsed = parseForwardedOrigin(forwarded);
			if (parsed != null) {
				return parsed;
			}
		}

		// Legacy proxy headers
		String xfProto = firstHeaderToken(request.getHeader("X-Forwarded-Proto"));
		String xfHost = firstHeaderToken(request.getHeader("X-Forwarded-Host"));
		String xfPort = firstHeaderToken(request.getHeader("X-Forwarded-Port"));

		if (xfProto != null && !xfProto.isEmpty()) {
			scheme = xfProto.trim().toLowerCase(Locale.ROOT);
		}
		if (xfHost != null && !xfHost.isEmpty()) {
			String[] hostPort = splitHostPort(xfHost.trim());
			host = hostPort[0];
			if (hostPort[1] != null) {
				try {
					port = Integer.parseInt(hostPort[1]);
				} catch (NumberFormatException ignored) {
					// keep current port
				}
			}
		}
		if (xfPort != null && !xfPort.isEmpty()) {
			try {
				port = Integer.parseInt(xfPort.trim());
			} catch (NumberFormatException ignored) {
				// keep current port
			}
		}

		return new OriginParts(
				scheme != null ? scheme.trim() : null,
				host != null ? host.trim().toLowerCase(Locale.ROOT) : null,
				port);
	}

	private OriginParts parseForwardedOrigin(String forwardedHeader) {
		try {
			String firstEntry = firstHeaderToken(forwardedHeader);
			if (firstEntry == null) return null;

			String scheme = null;
			String host = null;
			int port = -1;

			String[] directives = firstEntry.split(";");
			for (String directive : directives) {
				String[] kv = directive.trim().split("=", 2);
				if (kv.length != 2) continue;
				String key = kv[0].trim().toLowerCase();
				String value = kv[1].trim();
				if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
					value = value.substring(1, value.length() - 1);
				}
				if ("proto".equals(key)) {
					scheme = value.trim().toLowerCase(Locale.ROOT);
				} else if ("host".equals(key)) {
					String[] hostPort = splitHostPort(value.trim());
					host = hostPort[0];
					if (hostPort[1] != null) {
						try {
							port = Integer.parseInt(hostPort[1]);
						} catch (NumberFormatException ignored) {
							// keep unset
						}
					}
				}
			}

			if (scheme == null || host == null) return null;
			if (port == -1) port = normalizePort(scheme, -1);
			return new OriginParts(scheme, host.toLowerCase(Locale.ROOT), port);
		} catch (Exception e) {
			return null;
		}
	}

	private String firstHeaderToken(String raw) {
		if (raw == null) return null;
		String token = raw.split(",")[0].trim();
		return token.isEmpty() ? null : token;
	}

	private String[] splitHostPort(String hostHeader) {
		if (hostHeader == null || hostHeader.isEmpty()) {
			return new String[] { null, null };
		}
		// Bracketed IPv6: [::1]:8080
		if (hostHeader.startsWith("[")) {
			int closing = hostHeader.indexOf(']');
			if (closing > 0) {
				String host = hostHeader.substring(1, closing);
				String port = null;
				if (closing + 1 < hostHeader.length() && hostHeader.charAt(closing + 1) == ':') {
					port = hostHeader.substring(closing + 2);
				}
				return new String[] { host, port };
			}
		}
		int colon = hostHeader.lastIndexOf(':');
		if (colon > -1 && hostHeader.indexOf(':') == colon) {
			return new String[] { hostHeader.substring(0, colon), hostHeader.substring(colon + 1) };
		}
		return new String[] { hostHeader, null };
	}

	private static final class OriginParts {
		private final String scheme;
		private final String host;
		private final int port;

		private OriginParts(String scheme, String host, int port) {
			this.scheme = scheme;
			this.host = host;
			this.port = port;
		}
	}

	private int normalizePort(String scheme, int port) {
		if ("https".equalsIgnoreCase(scheme)) {
			return (port == -1 || port == 443) ? 443 : port;
		}
		if ("http".equalsIgnoreCase(scheme)) {
			return (port == -1 || port == 80) ? 80 : port;
		}
		return port;
	}

	private boolean isLocalhostDev(String host1, String host2) {
		return (("localhost".equals(host1) || "127.0.0.1".equals(host1))
				&& ("localhost".equals(host2) || "127.0.0.1".equals(host2)));
	}

	protected boolean isAdmin(HttpServletRequest request) {
		CallContext ctx = (CallContext) request.getAttribute("CallContext");
		if (ctx == null) return false;
		Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
		return admin != null && admin;
	}
}
