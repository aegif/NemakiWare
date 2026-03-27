package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.rest.purview.PurviewConnectionService;
import jp.aegif.nemaki.rest.purview.PurviewConnectionStatus;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * REST controller for managing integration settings (OIDC, Google, Microsoft, SAML, Purview).
 * Admin-only endpoints for reading and updating configuration stored in CouchDB nemaki_conf.
 */
@RestController
@RequestMapping("/v1/admin/integration-settings")
@CrossOrigin(origins = "*", maxAge = 3600)
public class IntegrationSettingsController {

	private static final Log log = LogFactory.getLog(IntegrationSettingsController.class);

	private static final String MASK = "[configured]";

	// --- Setting key definitions per group ---

	private static final Set<String> OIDC_KEYS = new LinkedHashSet<>(Arrays.asList(
			"sso.oidc.enabled",
			"oidc.issuer",
			"oidc.clientId"
	));

	private static final Set<String> GOOGLE_AUTH_KEYS = new LinkedHashSet<>(Arrays.asList(
			"cloud.auth.google.enabled",
			"cloud.auth.google.clientId"
	));

	private static final Set<String> MICROSOFT_AUTH_KEYS = new LinkedHashSet<>(Arrays.asList(
			"cloud.auth.microsoft.enabled",
			"cloud.auth.microsoft.clientId",
			"cloud.auth.microsoft.tenantId"
	));

	private static final Set<String> SAML_KEYS = new LinkedHashSet<>(Arrays.asList(
			"sso.saml.enabled",
			"saml.idp.sso.url",
			"saml.sp.entity.id",
			"saml.idp.certificate",
			"saml.slo.url",
			"saml.attribute.mapping"
	));

	private static final Set<String> DIRECTORY_SYNC_KEYS = new LinkedHashSet<>(Arrays.asList(
			"directory.sync.schedule.enabled",
			"directory.sync.schedule.cron",
			"cloud.directory.sync.enabled",
			"cloud.directory.sync.cron"
	));

	private static final Set<String> PURVIEW_KEYS = new LinkedHashSet<>(Arrays.asList(
			"purview.enabled",
			"purview.auth.type",
			"purview.endpoint",
			"purview.atlas.base-path",
			"purview.tenant.id",
			"purview.client.id",
			"purview.client.secret",
			"purview.basic.username",
			"purview.basic.password",
			"purview.collection",
			"purview.sync.cron"
	));

	// Keys whose values should be masked in GET responses
	private static final Set<String> SENSITIVE_KEYS = new LinkedHashSet<>(Arrays.asList(
			"saml.idp.certificate",
			"purview.client.secret",
			"purview.basic.password"
	));

	private final IntegrationSettingsService settingsService;

	private HttpServletRequest httpRequest;

	@Autowired(required = false)
	private PurviewConnectionService purviewConnectionService;

	@Autowired
	public IntegrationSettingsController(IntegrationSettingsService settingsService) {
		this.settingsService = settingsService;
	}

	@Autowired
	public void setHttpRequest(HttpServletRequest httpRequest) {
		this.httpRequest = httpRequest;
	}

	// ==================== OIDC ====================

	@GetMapping("/oidc")
	public ResponseEntity<Map<String, Object>> getOidcSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(OIDC_KEYS));
	}

	@PutMapping("/oidc")
	public ResponseEntity<Map<String, Object>> updateOidcSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(OIDC_KEYS, body);
	}

	// ==================== Google Auth ====================

	@GetMapping("/google-auth")
	public ResponseEntity<Map<String, Object>> getGoogleAuthSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(GOOGLE_AUTH_KEYS));
	}

	@PutMapping("/google-auth")
	public ResponseEntity<Map<String, Object>> updateGoogleAuthSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(GOOGLE_AUTH_KEYS, body);
	}

	// ==================== Microsoft Auth ====================

	@GetMapping("/microsoft-auth")
	public ResponseEntity<Map<String, Object>> getMicrosoftAuthSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(MICROSOFT_AUTH_KEYS));
	}

	@PutMapping("/microsoft-auth")
	public ResponseEntity<Map<String, Object>> updateMicrosoftAuthSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(MICROSOFT_AUTH_KEYS, body);
	}

	// ==================== SAML ====================

	@GetMapping("/saml")
	public ResponseEntity<Map<String, Object>> getSamlSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(SAML_KEYS));
	}

	@PutMapping("/saml")
	public ResponseEntity<Map<String, Object>> updateSamlSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(SAML_KEYS, body);
	}

	// ==================== Directory Sync ====================

	@GetMapping("/directory-sync")
	public ResponseEntity<Map<String, Object>> getDirectorySyncSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(DIRECTORY_SYNC_KEYS));
	}

	@PutMapping("/directory-sync")
	public ResponseEntity<Map<String, Object>> updateDirectorySyncSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(DIRECTORY_SYNC_KEYS, body);
	}

	// ==================== Purview ====================

	@GetMapping("/purview")
	public ResponseEntity<Map<String, Object>> getPurviewSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(PURVIEW_KEYS));
	}

	@PutMapping("/purview")
	public ResponseEntity<Map<String, Object>> updatePurviewSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(PURVIEW_KEYS, body);
	}

	// ==================== Connection Tests ====================

	@PostMapping("/oidc/test-connection")
	public ResponseEntity<Map<String, Object>> testOidcConnection() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;

		Map<String, Object> response = new LinkedHashMap<>();
		String issuer = settingsService.readSetting("oidc.issuer");
		if (issuer == null || issuer.isBlank()) {
			response.put("status", "failure");
			response.put("message", "OIDC issuer URL is not configured");
			return ResponseEntity.ok(response);
		}

		String wellKnownUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(10))
					.build();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(wellKnownUrl))
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

			if (httpResponse.statusCode() == 200) {
				response.put("status", "success");
				response.put("message", "Successfully connected to OIDC provider");
				response.put("wellKnownUrl", wellKnownUrl);
			} else {
				response.put("status", "failure");
				response.put("message", "OIDC provider returned HTTP " + httpResponse.statusCode());
				response.put("wellKnownUrl", wellKnownUrl);
			}
		} catch (Exception e) {
			response.put("status", "failure");
			response.put("message", "Failed to connect to OIDC provider: " + e.getMessage());
			response.put("wellKnownUrl", wellKnownUrl);
		}
		return ResponseEntity.ok(response);
	}

	@PostMapping("/purview/test-connection")
	public ResponseEntity<Map<String, Object>> testPurviewConnection(
			@RequestBody(required = false) Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;

		Map<String, Object> response = new LinkedHashMap<>();
		if (purviewConnectionService == null) {
			response.put("status", "failure");
			response.put("message", "Purview connector is not available");
			return ResponseEntity.ok(response);
		}

		try {
			PurviewConnectionStatus status = (body != null && !body.isEmpty())
					? purviewConnectionService.testConnection(body)
					: purviewConnectionService.testConnection();
			String resultStatus;
			if (status.isConnected()) {
				resultStatus = "success";
			} else if (!status.isFeatureEnabled()) {
				resultStatus = "disabled";
			} else {
				resultStatus = "failure";
			}
			response.put("status", resultStatus);
			response.put("connected", status.isConnected());
			response.put("featureEnabled", status.isFeatureEnabled());
			response.put("endpoint", status.getEndpoint());
			response.put("message", status.getMessage());
		} catch (Exception e) {
			response.put("status", "failure");
			response.put("message", "Purview connection test failed: " + e.getMessage());
		}
		return ResponseEntity.ok(response);
	}

	// ==================== Helpers ====================

	private Map<String, Object> buildSettingsResponse(Set<String> keys) {
		Map<String, String> settings = settingsService.readSettings(keys);
		Map<String, String> sources = settingsService.readSettingSources(keys);

		// Mask sensitive values
		Map<String, String> maskedSettings = new LinkedHashMap<>(settings);
		for (String sensitiveKey : SENSITIVE_KEYS) {
			if (maskedSettings.containsKey(sensitiveKey)) {
				String val = maskedSettings.get(sensitiveKey);
				if (val != null && !val.isEmpty()) {
					maskedSettings.put(sensitiveKey, MASK);
				}
			}
		}

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("settings", maskedSettings);
		response.put("sources", sources);
		return response;
	}

	private ResponseEntity<Map<String, Object>> handleUpdate(Set<String> allowedKeys, Map<String, String> body) {
		// Filter to only allowed keys, skip [configured] values
		Map<String, String> toWrite = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : body.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (!allowedKeys.contains(key)) {
				continue;
			}
			if (MASK.equals(value)) {
				continue; // Don't overwrite sensitive value with mask placeholder
			}
			toWrite.put(key, value);
		}

		if (toWrite.isEmpty()) {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "success");
			response.put("message", "No changes to save");
			return ResponseEntity.ok(response);
		}

		try {
			settingsService.writeSettings(toWrite);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "success");
			response.put("message", "Settings updated successfully");
			response.put("updatedKeys", new ArrayList<>(toWrite.keySet()));
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("Failed to update integration settings", e);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "error");
			response.put("message", "Failed to update settings: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}

	private ResponseEntity<Map<String, Object>> requireAdminOrForbidden() {
		if (!isAdmin()) {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "error");
			response.put("message", "Admin access required");
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
		}
		return null;
	}

	private boolean isAdmin() {
		if (httpRequest == null) {
			return false;
		}
		CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
		if (callContext == null) {
			return false;
		}
		Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
		return isAdmin != null && isAdmin;
	}
}
