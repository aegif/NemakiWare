package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.rest.purview.PurviewConnectionService;
import jp.aegif.nemaki.rest.purview.PurviewConnectionStatus;
import jp.aegif.nemaki.rest.purview.payload.CatalogPropertyMappingResolver;
import jp.aegif.nemaki.rest.purview.payload.CatalogPropertyMappingResolver.PropertyMapping;
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
 * REST controller for managing integration settings (OIDC, Google, Microsoft, SAML, Purview, Lineage).
 * Admin-only endpoints for reading and updating configuration stored in CouchDB nemaki_conf.
 */
@RestController
@RequestMapping("/v1/admin/integration-settings")
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
			"purview.endpoint",
			"purview.atlas.base-path",
			"purview.tenant.id",
			"purview.client.id",
			"purview.client.secret",
			"purview.collection",
			"purview.sync.cron"
	));

	private static final Set<String> LINEAGE_KEYS = new LinkedHashSet<>(Arrays.asList(
			"lineage.mode",
			"lineage.targets",
			"lineage.retention.days",
			"lineage.capture.version-events",
			"lineage.capture.generic-relationships",
			"lineage.purge.cron",
			"lineage.snapshot.max-name-length",
			"lineage.snapshot.max-path-length",
			"lineage.snapshot.capture-path",
			"lineage.backlog.max-retry-count",
			"lineage.backlog.max-retry-age-hours",
			"lineage.backlog.max-docs",
			"lineage.backlog.max-size-mb",
			"lineage.leader-election.enabled",
			"lineage.leader-election.heartbeat-seconds",
			"lineage.leader-election.ttl-seconds"
	));

	private static final Set<String> ATLAS_KEYS = new LinkedHashSet<>(Arrays.asList(
			"atlas.enabled",
			"atlas.endpoint",
			"atlas.username",
			"atlas.password",
			"atlas.collection",
			"atlas.sync.cron",
			"atlas.connect.timeout.ms",
			"atlas.read.timeout.ms"
	));

	private static final Set<String> DATAPLEX_KEYS = new LinkedHashSet<>(Arrays.asList(
			"dataplex.enabled",
			"dataplex.project-id",
			"dataplex.location",
			"dataplex.credentials-file"
	));

	private static final Set<String> MCP_KEYS = new LinkedHashSet<>(Arrays.asList(
			"mcp.tools.list.public"
	));

	/**
	 * Runtime defaults for lineage keys (mirrors @Value defaults in LineageConfig).
	 * Used to surface the effective value in the UI when no explicit configuration exists.
	 */
	private static final Map<String, String> LINEAGE_DEFAULTS = Map.ofEntries(
			Map.entry("lineage.mode", "disabled"),
			Map.entry("lineage.retention.days", "90"),
			Map.entry("lineage.capture.version-events", "false"),
			Map.entry("lineage.capture.generic-relationships", "false"),
			Map.entry("lineage.snapshot.max-name-length", "512"),
			Map.entry("lineage.snapshot.max-path-length", "2048"),
			Map.entry("lineage.snapshot.capture-path", "true"),
			Map.entry("lineage.backlog.max-retry-count", "5"),
			Map.entry("lineage.backlog.max-retry-age-hours", "72"),
			Map.entry("lineage.backlog.max-docs", "10000"),
			Map.entry("lineage.backlog.max-size-mb", "100"),
			Map.entry("lineage.leader-election.enabled", "false"),
			Map.entry("lineage.leader-election.heartbeat-seconds", "15"),
			Map.entry("lineage.leader-election.ttl-seconds", "60")
	);

	// Keys whose values should be masked in GET responses
	private static final Set<String> SENSITIVE_KEYS = new LinkedHashSet<>(Arrays.asList(
			"saml.idp.certificate",
			"purview.client.secret",
			"atlas.password"
	));

	private final IntegrationSettingsService settingsService;

	private HttpServletRequest httpRequest;

	@Autowired(required = false)
	private PurviewConnectionService purviewConnectionService;

	@Autowired(required = false)
	private CatalogPropertyMappingResolver propertyMappingResolver;

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
		return appendDualBackendWarningIfNeeded(ResponseEntity.ok(buildSettingsResponse(PURVIEW_KEYS)));
	}

	@PutMapping("/purview")
	public ResponseEntity<Map<String, Object>> updatePurviewSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return appendDualBackendWarningIfNeeded(handleUpdate(PURVIEW_KEYS, body));
	}

	// ==================== Lineage Journal ====================

	@GetMapping("/lineage")
	public ResponseEntity<Map<String, Object>> getLineageSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponseWithDefaults(LINEAGE_KEYS, LINEAGE_DEFAULTS));
	}

	@PutMapping("/lineage")
	public ResponseEntity<Map<String, Object>> updateLineageSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(LINEAGE_KEYS, body, LINEAGE_DEFAULTS);
	}

	// ==================== Atlas ====================

	@GetMapping("/atlas")
	public ResponseEntity<Map<String, Object>> getAtlasSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return appendDualBackendWarningIfNeeded(ResponseEntity.ok(buildSettingsResponse(ATLAS_KEYS)));
	}

	@PutMapping("/atlas")
	public ResponseEntity<Map<String, Object>> updateAtlasSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return appendDualBackendWarningIfNeeded(handleUpdate(ATLAS_KEYS, body));
	}

	// ==================== Dataplex ====================

	@GetMapping("/dataplex")
	public ResponseEntity<Map<String, Object>> getDataplexSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponse(DATAPLEX_KEYS));
	}

	@PutMapping("/dataplex")
	public ResponseEntity<Map<String, Object>> updateDataplexSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(DATAPLEX_KEYS, body);
	}

	// ==================== MCP ====================

	private static final Map<String, String> MCP_DEFAULTS = Map.of(
			"mcp.tools.list.public", "true"
	);

	@GetMapping("/mcp")
	public ResponseEntity<Map<String, Object>> getMcpSettings() {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return ResponseEntity.ok(buildSettingsResponseWithDefaults(MCP_KEYS, MCP_DEFAULTS));
	}

	@PutMapping("/mcp")
	public ResponseEntity<Map<String, Object>> updateMcpSettings(@RequestBody Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;
		return handleUpdate(MCP_KEYS, body, MCP_DEFAULTS);
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

	@PostMapping("/atlas/test-connection")
	public ResponseEntity<Map<String, Object>> testAtlasConnection(
			@RequestBody(required = false) Map<String, String> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;

		Map<String, Object> response = new LinkedHashMap<>();
		if (purviewConnectionService == null) {
			response.put("status", "failure");
			response.put("message", "Atlas connector is not available");
			return ResponseEntity.ok(response);
		}

		try {
			PurviewConnectionStatus status = purviewConnectionService.testAtlasConnection(body);
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
			response.put("message", "Atlas connection test failed: " + e.getMessage());
		}
		return ResponseEntity.ok(response);
	}

	// ==================== Property Mappings ====================

	/**
	 * Returns the property mapping configuration for a specific repository.
	 */
	@GetMapping("/property-mappings/{repositoryId}")
	public ResponseEntity<Map<String, Object>> getPropertyMappings(@PathVariable String repositoryId) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;

		Map<String, Object> response = new LinkedHashMap<>();
		if (propertyMappingResolver == null) {
			response.put("mappings", Map.of());
			response.put("message", "Property mapping resolver is not available");
			return ResponseEntity.ok(response);
		}
		Map<String, Map<String, PropertyMapping>> mappings = propertyMappingResolver.loadMappings(repositoryId);
		Map<String, Map<String, Map<String, Object>>> jsonMappings = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, PropertyMapping>> typeEntry : mappings.entrySet()) {
			Map<String, Map<String, Object>> typeMap = new LinkedHashMap<>();
			for (Map.Entry<String, PropertyMapping> propEntry : typeEntry.getValue().entrySet()) {
				Map<String, Object> fields = new LinkedHashMap<>();
				fields.put("enabled", propEntry.getValue().enabled());
				fields.put("catalogName", propEntry.getValue().catalogName());
				typeMap.put(propEntry.getKey(), fields);
			}
			jsonMappings.put(typeEntry.getKey(), typeMap);
		}
		response.put("mappings", jsonMappings);
		// Include cross-repo conflict warnings so the admin can see if any
		// of this repository's mappings are inactive due to type mismatches
		List<String> conflicts = propertyMappingResolver.detectCrossRepoConflicts(repositoryId);
		if (!conflicts.isEmpty()) {
			response.put("warnings", conflicts);
		}
		return ResponseEntity.ok(response);
	}

	/**
	 * Updates the property mapping configuration for a specific repository.
	 * Accepts the same format as returned by GET.
	 */
	@SuppressWarnings("unchecked")
	@PutMapping("/property-mappings/{repositoryId}")
	public ResponseEntity<Map<String, Object>> updatePropertyMappings(
			@PathVariable String repositoryId, @RequestBody Map<String, Object> body) {
		ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
		if (forbidden != null) return forbidden;

		Map<String, Object> response = new LinkedHashMap<>();
		if (propertyMappingResolver == null) {
			response.put("status", "error");
			response.put("message", "Property mapping resolver is not available");
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}

		try {
			Object mappingsObj = body.get("mappings");
			if (!(mappingsObj instanceof Map<?, ?>)) {
				response.put("status", "error");
				response.put("message", "Missing or invalid 'mappings' field");
				return ResponseEntity.badRequest().body(response);
			}
			Map<String, Object> rawMappings = (Map<String, Object>) mappingsObj;
			Map<String, Map<String, PropertyMapping>> parsed = new LinkedHashMap<>();
			for (Map.Entry<String, Object> typeEntry : rawMappings.entrySet()) {
				if (!(typeEntry.getValue() instanceof Map<?, ?>)) continue;
				Map<String, Object> typeMap = (Map<String, Object>) typeEntry.getValue();
				Map<String, PropertyMapping> typeMappings = new LinkedHashMap<>();
				for (Map.Entry<String, Object> propEntry : typeMap.entrySet()) {
					if (!(propEntry.getValue() instanceof Map<?, ?>)) continue;
					Map<String, Object> fields = (Map<String, Object>) propEntry.getValue();
					boolean enabled = Boolean.TRUE.equals(fields.get("enabled"));
					String catalogName = fields.get("catalogName") instanceof String s ? s : propEntry.getKey();
					typeMappings.put(propEntry.getKey(), new PropertyMapping(enabled, catalogName));
				}
				parsed.put(typeEntry.getKey(), typeMappings);
			}
			propertyMappingResolver.saveMappings(repositoryId, parsed);
			response.put("status", "success");
			response.put("message", "Property mappings updated successfully");
			// Check for cross-repository type conflicts and surface as warnings
			List<String> conflicts = propertyMappingResolver.detectCrossRepoConflicts(repositoryId);
			if (!conflicts.isEmpty()) {
				response.put("warnings", conflicts);
			}
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			response.put("status", "error");
			response.put("message", e.getMessage());
			return ResponseEntity.badRequest().body(response);
		} catch (Exception e) {
			log.error("Failed to update property mappings", e);
			response.put("status", "error");
			response.put("message", "Failed to update property mappings: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}

	// ==================== Helpers ====================

	/**
	 * Builds settings response with runtime defaults applied for unconfigured keys.
	 * When a key has no explicit value (source="none") and a runtime default exists,
	 * the default is surfaced so the UI shows the effective value.
	 */
	private Map<String, Object> buildSettingsResponseWithDefaults(Set<String> keys, Map<String, String> defaults) {
		Map<String, String> settings = settingsService.readSettings(keys);
		Map<String, String> sources = settingsService.readSettingSources(keys);

		for (Map.Entry<String, String> def : defaults.entrySet()) {
			String key = def.getKey();
			String defValue = def.getValue();
			if ("none".equals(sources.get(key)) && defValue != null && !defValue.isEmpty()) {
				settings.put(key, defValue);
				sources.put(key, "default");
			}
		}

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
		return handleUpdate(allowedKeys, body, Map.of());
	}

	private ResponseEntity<Map<String, Object>> handleUpdate(Set<String> allowedKeys, Map<String, String> body,
			Map<String, String> defaults) {
		// Filter to only allowed keys, skip [configured] values
		Map<String, String> toWrite = new LinkedHashMap<>();
		Set<String> toDelete = new LinkedHashSet<>();
		for (Map.Entry<String, String> entry : body.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (!allowedKeys.contains(key)) {
				continue;
			}
			if (MASK.equals(value)) {
				continue; // Don't overwrite sensitive value with mask placeholder
			}
			// Empty value for a key that has a runtime default → delete the CouchDB
			// override so the @Value default takes effect again.
			if ((value == null || value.isEmpty()) && defaults.containsKey(key)) {
				toDelete.add(key);
				continue;
			}
			toWrite.put(key, value);
		}

		if (toWrite.isEmpty() && toDelete.isEmpty()) {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "success");
			response.put("message", "No changes to save");
			return ResponseEntity.ok(response);
		}

		try {
			if (!toWrite.isEmpty()) {
				settingsService.writeSettings(toWrite);
			}
			if (!toDelete.isEmpty()) {
				settingsService.deleteSettings(toDelete);
			}
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("status", "success");
			response.put("message", "Settings updated successfully");
			List<String> affected = new ArrayList<>(toWrite.keySet());
			affected.addAll(toDelete);
			response.put("updatedKeys", affected);
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

	private ResponseEntity<Map<String, Object>> appendDualBackendWarningIfNeeded(
			ResponseEntity<Map<String, Object>> result) {
		if (result.getStatusCode() != HttpStatus.OK || result.getBody() == null) {
			return result;
		}
		String purviewEnabled = settingsService.readSetting("purview.enabled");
		String atlasEnabled = settingsService.readSetting("atlas.enabled");
		if ("true".equalsIgnoreCase(purviewEnabled) && "true".equalsIgnoreCase(atlasEnabled)) {
			Map<String, Object> body = new LinkedHashMap<>(result.getBody());
			body.put("warning", "Both Purview and Atlas backends are enabled. Only one backend can be active at a time; Purview takes priority.");
			return ResponseEntity.ok(body);
		}
		return result;
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
