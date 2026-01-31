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
 *     NemakiWare Project
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Authentication configuration resource for SSO button visibility.
 * This endpoint is public (no authentication required) because it needs
 * to be accessed before the user logs in.
 */
@Path("/auth/config")
public class AuthConfigResource extends ResourceBase {

	private static final Log log = LogFactory.getLog(AuthConfigResource.class);

	private PropertyManager propertyManager;

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	private PropertyManager getPropertyManager() {
		if (propertyManager != null) {
			return propertyManager;
		}
		// Fallback: Jersey may create its own instance bypassing Spring DI
		try {
			PropertyManager pm = SpringContext.getApplicationContext()
					.getBean("propertyManager", PropertyManager.class);
			if (pm != null) {
				log.debug("PropertyManager retrieved from SpringContext fallback");
				this.propertyManager = pm;
				return pm;
			}
		} catch (Exception e) {
			log.error("Failed to get PropertyManager from SpringContext: " + e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Get SSO configuration for login page button visibility.
	 * Returns whether OIDC and SAML login buttons should be displayed.
	 *
	 * @return JSON object with oidcEnabled and samlEnabled booleans
	 */
	@SuppressWarnings("unchecked")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public String getAuthConfig() {
		JSONObject result = new JSONObject();

		try {
			// Read SSO settings from properties (default: false)
			boolean oidcEnabled = readBooleanProperty(PropertyKey.SSO_OIDC_ENABLED, false);
			boolean samlEnabled = readBooleanProperty(PropertyKey.SSO_SAML_ENABLED, false);

			result.put("oidcEnabled", oidcEnabled);
			result.put("samlEnabled", samlEnabled);
			result.put("status", "success");

			log.debug("Auth config requested: OIDC=" + oidcEnabled + ", SAML=" + samlEnabled);
		} catch (Exception e) {
			log.error("Failed to read auth config: " + e.getMessage(), e);
			// Return safe defaults on error (buttons hidden)
			result.put("oidcEnabled", false);
			result.put("samlEnabled", false);
			result.put("status", "error");
			result.put("message", "Failed to read configuration");
		}

		return result.toJSONString();
	}

	/**
	 * Read a boolean property value with a default fallback.
	 */
	private boolean readBooleanProperty(String key, boolean defaultValue) {
		PropertyManager pm = getPropertyManager();
		if (pm == null) {
			log.warn("PropertyManager is null, returning default value for " + key);
			return defaultValue;
		}

		String value = pm.readValue(key);
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}

		return "true".equalsIgnoreCase(value.trim());
	}
}
