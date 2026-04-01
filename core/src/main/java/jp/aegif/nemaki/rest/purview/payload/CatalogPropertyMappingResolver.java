package jp.aegif.nemaki.rest.purview.payload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves which CMIS custom properties should be synced to catalog backends
 * (Purview / Atlas / Dataplex).
 *
 * <p>Mappings are stored <b>per repository</b> in CouchDB nemaki_conf under
 * {@code catalog.sync.propertyMappings.{repositoryId}}. Each mapping stores
 * only {@code enabled} and {@code catalogName}; property type and cardinality
 * are resolved at runtime from the repository's current type definitions.
 */
public class CatalogPropertyMappingResolver {

    private static final Logger logger = LoggerFactory.getLogger(CatalogPropertyMappingResolver.class);

    static final String SETTINGS_KEY_PREFIX = "catalog.sync.propertyMappings.";

    /** Legacy global key — detected on load, triggers warning, never written to. */
    static final String LEGACY_GLOBAL_KEY = "catalog.sync.propertyMappings";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Core entity attribute names used by nemaki_document and nemaki_folder.
     * Custom catalogName values must not collide with these.
     */
    static final Set<String> RESERVED_ATTRIBUTE_NAMES = Set.of(
            "qualifiedName", "name", "description", "owner",
            "createTime", "modifiedTime",
            "repositoryId", "objectId", "parentId", "typeId", "folderPath",
            "versionSeriesId", "versionLabel", "isLatestVersion",
            "lifecycleState", "archiveState", "archiveId", "archivedAt",
            "cloudProvider", "externalFileId", "cloudFileUrl", "cloudLastSyncedAt");

    private final IntegrationSettingsService settingsService;
    private final TypeService typeService;
    private final RepositoryInfoMap repositoryInfoMap;

    // Per-publish-run cache — cleared by clearResolvedCache() between runs
    private volatile Map<String, ResolvedMapping> cachedGlobalUnion;
    private volatile Map<String, Map<String, ResolvedMapping>> cachedPerRepo = new LinkedHashMap<>();
    private volatile Map<String, Map<String, Map<String, PropertyMapping>>> cachedLoadedMappings = new LinkedHashMap<>();

    public CatalogPropertyMappingResolver(IntegrationSettingsService settingsService,
                                          TypeService typeService,
                                          RepositoryInfoMap repositoryInfoMap) {
        this.settingsService = settingsService;
        this.typeService = typeService;
        this.repositoryInfoMap = repositoryInfoMap;
    }

    /**
     * Clears the resolved mapping cache. Should be called at the start of each
     * publish run or when mappings/type definitions may have changed.
     */
    public void clearResolvedCache() {
        cachedGlobalUnion = null;
        cachedPerRepo = new LinkedHashMap<>();
        cachedLoadedMappings = new LinkedHashMap<>();
    }

    // ── Data structures ──────────────────────────────────────────────

    /**
     * Persisted mapping entry. Only {@code enabled} and {@code catalogName} are stored.
     */
    public record PropertyMapping(boolean enabled, String catalogName) {}

    /**
     * Resolved mapping with type information derived from the repository's
     * current type definitions — never persisted.
     */
    public record ResolvedMapping(String cmisPropertyId, String catalogName,
                                  PropertyType propertyType, Cardinality cardinality) {}

    // ── Read mappings ────────────────────────────────────────────────

    /**
     * Returns all enabled property mappings for a specific CMIS type in a repository.
     * Key = CMIS property ID, Value = catalog attribute name.
     */
    public Map<String, String> getEnabledMappings(String repositoryId, String typeId) {
        Map<String, Map<String, PropertyMapping>> all = loadMappings(repositoryId);
        Map<String, PropertyMapping> typeMappings = all.get(typeId);
        if (typeMappings == null || typeMappings.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, PropertyMapping> entry : typeMappings.entrySet()) {
            if (entry.getValue().enabled()) {
                result.put(entry.getKey(), entry.getValue().catalogName());
            }
        }
        return result;
    }

    /**
     * Returns all enabled mappings for a repository, resolved with current
     * type definition metadata. Used by schema generation and payload generation.
     *
     * <p>Properties whose type definition cannot be found are skipped with a warning.
     *
     * @return outer key = catalogName, value = resolved mapping (deduplicated)
     */
    public Map<String, ResolvedMapping> getResolvedMappings(String repositoryId) {
        Map<String, ResolvedMapping> cached = cachedPerRepo.get(repositoryId);
        if (cached != null) {
            return cached;
        }
        Map<String, Map<String, PropertyMapping>> all = loadMappings(repositoryId);
        if (all.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ResolvedMapping> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, PropertyMapping>> typeEntry : all.entrySet()) {
            for (Map.Entry<String, PropertyMapping> propEntry : typeEntry.getValue().entrySet()) {
                PropertyMapping m = propEntry.getValue();
                if (!m.enabled()) {
                    continue;
                }
                String cmisPropertyId = propEntry.getKey();
                // Resolve type info from repository's current type definition.
                // If the definition cannot be found (deleted property, stale mapping),
                // skip the mapping entirely to avoid schema/payload mismatch.
                NemakiPropertyDefinitionCore core = null;
                if (typeService != null) {
                    try {
                        core = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, cmisPropertyId);
                    } catch (Exception e) {
                        logger.debug("Could not resolve property core for {}: {}", cmisPropertyId, e.getMessage());
                    }
                }
                if (core == null || core.getPropertyType() == null) {
                    logger.warn("Skipping property mapping '{}' in repository '{}': "
                            + "type definition not found. Remove or disable this mapping to suppress this warning.",
                            cmisPropertyId, repositoryId);
                    continue;
                }
                PropertyType propType = core.getPropertyType();
                Cardinality card = core.getCardinality() != null ? core.getCardinality() : Cardinality.SINGLE;
                ResolvedMapping resolved = new ResolvedMapping(cmisPropertyId, m.catalogName(), propType, card);
                ResolvedMapping existing = result.get(m.catalogName());
                if (existing != null && (existing.propertyType() != propType || existing.cardinality() != card)) {
                    logger.warn("Catalog attribute '{}' has conflicting type definitions across types: "
                            + "{}({}/{}) vs {}({}/{}); keeping first definition",
                            m.catalogName(),
                            existing.cmisPropertyId(), existing.propertyType(), existing.cardinality(),
                            cmisPropertyId, propType, card);
                    continue;
                }
                result.putIfAbsent(m.catalogName(), resolved);
            }
        }
        cachedPerRepo.put(repositoryId, result);
        return result;
    }

    /**
     * Returns the raw mapping configuration for a repository (including disabled entries).
     */
    public Map<String, Map<String, PropertyMapping>> loadMappings(String repositoryId) {
        if (settingsService == null || repositoryId == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, PropertyMapping>> cached = cachedLoadedMappings.get(repositoryId);
        if (cached != null) {
            return cached;
        }
        String json = settingsService.readSetting(SETTINGS_KEY_PREFIX + repositoryId);
        if (json == null || json.isBlank()) {
            // Check for legacy global key and warn
            String legacyJson = settingsService.readSetting(LEGACY_GLOBAL_KEY);
            if (legacyJson != null && !legacyJson.isBlank()) {
                logger.warn("Found legacy global property mapping (key='{}') which is no longer used. "
                        + "Property mappings are now stored per-repository under '{}{{repositoryId}}'. "
                        + "Please re-configure mappings via the admin UI.",
                        LEGACY_GLOBAL_KEY, SETTINGS_KEY_PREFIX);
            }
            cachedLoadedMappings.put(repositoryId, Collections.emptyMap());
            return Collections.emptyMap();
        }
        Map<String, Map<String, PropertyMapping>> result = parseMappingsJson(json);
        cachedLoadedMappings.put(repositoryId, result);
        return result;
    }

    private Map<String, Map<String, PropertyMapping>> parseMappingsJson(String json) {
        try {
            Map<String, Map<String, Map<String, Object>>> raw = OBJECT_MAPPER.readValue(json,
                    new TypeReference<>() {});
            Map<String, Map<String, PropertyMapping>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Map<String, Object>>> typeEntry : raw.entrySet()) {
                Map<String, PropertyMapping> typeMappings = new LinkedHashMap<>();
                for (Map.Entry<String, Map<String, Object>> propEntry : typeEntry.getValue().entrySet()) {
                    Map<String, Object> fields = propEntry.getValue();
                    boolean enabled = Boolean.TRUE.equals(fields.get("enabled"));
                    String catalogName = fields.get("catalogName") instanceof String s ? s : propEntry.getKey();
                    typeMappings.put(propEntry.getKey(), new PropertyMapping(enabled, catalogName));
                }
                result.put(typeEntry.getKey(), typeMappings);
            }
            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse property mapping configuration: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Returns the union of resolved mappings across all known repositories.
     * Used by schema generation which emits a single schema for the catalog backend.
     * Conflicting type definitions for the same catalogName are logged and the
     * first occurrence wins.
     */
    public Map<String, ResolvedMapping> getResolvedMappingsAllRepositories() {
        if (cachedGlobalUnion != null) {
            return cachedGlobalUnion;
        }
        if (repositoryInfoMap == null) {
            return Collections.emptyMap();
        }
        Map<String, ResolvedMapping> union = new LinkedHashMap<>();
        for (String repoId : repositoryInfoMap.keys()) {
            Map<String, ResolvedMapping> repoMappings = getResolvedMappings(repoId);
            for (Map.Entry<String, ResolvedMapping> entry : repoMappings.entrySet()) {
                ResolvedMapping existing = union.get(entry.getKey());
                if (existing != null) {
                    ResolvedMapping candidate = entry.getValue();
                    if (existing.propertyType() != candidate.propertyType()
                            || existing.cardinality() != candidate.cardinality()) {
                        logger.warn("Cross-repository conflict for catalog attribute '{}': "
                                + "{}({}/{}) vs {}({}/{}); keeping first definition",
                                entry.getKey(),
                                existing.cmisPropertyId(), existing.propertyType(), existing.cardinality(),
                                candidate.cmisPropertyId(), candidate.propertyType(), candidate.cardinality());
                    }
                } else {
                    union.put(entry.getKey(), entry.getValue());
                }
            }
        }
        cachedGlobalUnion = union;
        return union;
    }

    /**
     * Detects cross-repository type/cardinality conflicts for a given repository's mappings
     * against the global schema union. Returns human-readable warning messages.
     * An empty list means no conflicts.
     */
    public List<String> detectCrossRepoConflicts(String repositoryId) {
        Map<String, ResolvedMapping> globalUnion = getResolvedMappingsAllRepositories();
        Map<String, ResolvedMapping> local = getResolvedMappings(repositoryId);
        List<String> warnings = new java.util.ArrayList<>();
        for (Map.Entry<String, ResolvedMapping> entry : local.entrySet()) {
            String catalogName = entry.getKey();
            ResolvedMapping localMapping = entry.getValue();
            ResolvedMapping globalMapping = globalUnion.get(catalogName);
            if (globalMapping != null
                    && (localMapping.propertyType() != globalMapping.propertyType()
                        || localMapping.cardinality() != globalMapping.cardinality())) {
                warnings.add("Catalog attribute '" + catalogName + "' ("
                        + localMapping.cmisPropertyId() + ": "
                        + localMapping.propertyType() + "/" + localMapping.cardinality()
                        + ") conflicts with another repository's definition ("
                        + globalMapping.cmisPropertyId() + ": "
                        + globalMapping.propertyType() + "/" + globalMapping.cardinality()
                        + "). This mapping will be inactive until the conflict is resolved.");
            }
        }
        return warnings;
    }

    /**
     * Computes a fingerprint covering all repositories' mappings, for schema hash calculation.
     */
    public String computeMappingFingerprintAllRepositories() {
        Map<String, ResolvedMapping> resolved = getResolvedMappingsAllRepositories();
        return buildFingerprint(resolved);
    }

    // ── Write mappings ───────────────────────────────────────────────

    /**
     * Validates that no enabled mapping uses a reserved or blank attribute name.
     *
     * @return list of validation error messages (empty if valid)
     */
    public static List<String> validateMappings(Map<String, Map<String, PropertyMapping>> mappings) {
        List<String> errors = new java.util.ArrayList<>();
        for (Map.Entry<String, Map<String, PropertyMapping>> typeEntry : mappings.entrySet()) {
            for (Map.Entry<String, PropertyMapping> propEntry : typeEntry.getValue().entrySet()) {
                PropertyMapping m = propEntry.getValue();
                if (!m.enabled()) {
                    continue;
                }
                String loc = typeEntry.getKey() + "." + propEntry.getKey();
                if (m.catalogName() == null || m.catalogName().trim().isEmpty()) {
                    errors.add("Property " + loc + " has an empty catalog attribute name");
                    continue;
                }
                if (RESERVED_ATTRIBUTE_NAMES.contains(m.catalogName().trim())) {
                    errors.add("Property " + loc
                            + " uses reserved catalog attribute name '" + m.catalogName() + "'");
                }
            }
        }
        return errors;
    }

    /**
     * Saves the mapping configuration for a specific repository to CouchDB.
     *
     * @throws IllegalArgumentException if any enabled mapping uses a reserved or blank name
     */
    public void saveMappings(String repositoryId, Map<String, Map<String, PropertyMapping>> mappings) {
        List<String> errors = validateMappings(mappings);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid property mappings: " + String.join("; ", errors));
        }
        try {
            Map<String, Map<String, Map<String, Object>>> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, PropertyMapping>> typeEntry : mappings.entrySet()) {
                Map<String, Map<String, Object>> typeRaw = new LinkedHashMap<>();
                for (Map.Entry<String, PropertyMapping> propEntry : typeEntry.getValue().entrySet()) {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("enabled", propEntry.getValue().enabled());
                    fields.put("catalogName", propEntry.getValue().catalogName());
                    typeRaw.put(propEntry.getKey(), fields);
                }
                raw.put(typeEntry.getKey(), typeRaw);
            }
            String json = OBJECT_MAPPER.writeValueAsString(raw);
            settingsService.writeSetting(SETTINGS_KEY_PREFIX + repositoryId, json);
            // Invalidate caches so subsequent reads see the new data
            clearResolvedCache();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save property mapping configuration: " + e.getMessage(), e);
        }
    }

    // ── Catalog schema hash contribution ─────────────────────────────

    /**
     * Returns a stable, deterministic string representing the current enabled
     * mappings for a repository, including type information resolved from
     * the repository's current type definitions.
     *
     * <p>Changes in catalogName, property type, or cardinality all change
     * the fingerprint, triggering schema re-apply.
     */
    public String computeMappingFingerprint(String repositoryId) {
        return buildFingerprint(getResolvedMappings(repositoryId));
    }

    private static String buildFingerprint(Map<String, ResolvedMapping> resolved) {
        if (resolved.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> catalogNames = resolved.keySet().stream().sorted().toList();
        for (String catalogName : catalogNames) {
            ResolvedMapping m = resolved.get(catalogName);
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(m.cmisPropertyId()).append('=').append(catalogName)
                    .append('/').append(m.propertyType().value())
                    .append('/').append(m.cardinality().value());
        }
        return sb.toString();
    }

    // ── Type conversion ──────────────────────────────────────────────

    /**
     * Converts a CMIS PropertyType to the corresponding Atlas/Purview attribute type name.
     */
    public static String toAtlasTypeName(PropertyType cmisType) {
        if (cmisType == null) {
            return "string";
        }
        return switch (cmisType) {
            case STRING, HTML, URI, ID -> "string";
            case INTEGER -> "long";
            case BOOLEAN -> "boolean";
            case DATETIME -> "long";
            case DECIMAL -> "double";
        };
    }
}
