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
    /**
     * CMIS properties that may never be projected, whatever the mapping calls the output.
     *
     * <p>The reserved-name rule guards the <em>output</em> side: it stops a mapping from writing
     * over {@code cloudFileUrl}. It says nothing about a mapping that reads
     * {@code nemaki:cloudFileUrl} and writes it somewhere innocuous —
     * {@code nemaki:cloudFileUrl -> legacyCloudUrl} passes the output check, passes the payload
     * boundary because no such attribute exists yet, and puts the raw URL in Atlas anyway.
     *
     * <p>That property really is present on documents: {@code CloudDriveResource} still reads
     * cloud metadata out of {@code subTypeProperties} as a legacy fallback, so it is stored there
     * on older documents. The URL is the value increment A-1g removed from the catalog entirely;
     * a custom mapping must not be a second door to it.
     */
    static final Set<String> FORBIDDEN_SOURCE_PROPERTY_IDS = Set.of("nemaki:cloudFileUrl");

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

    /** (type, property, name) triples already logged, so the WARN is not repeated per parse. */
    private final Set<String> warnedRejectedMappings =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.atomic.AtomicLong rejectedMappingCount =
            new java.util.concurrent.atomic.AtomicLong();

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
                    // Enforced on read, not only in saveMappings. saveMappings is one way a
                    // mapping arrives; a configuration written before this rule existed, a
                    // restore, a hand-edited CouchDB document and a corrupted value are the
                    // others, and every one of them reaches the payload through this parse.
                    // A reserved catalogName lets a custom property overwrite a core attribute
                    // — including cloudFileUrl, which increment A-1g sets to null precisely so
                    // that no stored URL reaches the catalog.
                    if (enabled && isUnusableMapping(propEntry.getKey(), catalogName)) {
                        warnOnceAboutRejectedMapping(typeEntry.getKey(), propEntry.getKey(),
                                catalogName);
                        // this mapping only; the rest of the projection is unaffected
                        continue;
                    }
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
     * A catalogName no mapping may use: blank, or one of a core entity attribute's.
     *
     * <p>Trimmed before comparison because {@code appendCustomPropertyValues} uses the raw value
     * as the map key — {@code " cloudFileUrl"} would not collide, but a configuration containing
     * it is a mistake either way, and {@link #validateMappings} has always trimmed.
     */
    static boolean isUnusableCatalogName(String catalogName) {
        if (catalogName == null || catalogName.trim().isEmpty()) {
            return true;
        }
        return RESERVED_ATTRIBUTE_NAMES.contains(catalogName.trim());
    }

    /** A source property that must not be projected under any output name. */
    static boolean isForbiddenSourceProperty(String cmisPropertyId) {
        return cmisPropertyId != null
                && FORBIDDEN_SOURCE_PROPERTY_IDS.contains(cmisPropertyId.trim());
    }

    /**
     * The whole predicate: both ends of the mapping.
     *
     * <p>One method so that save and load cannot check different things — the previous split was
     * how the output rule ended up enforced in two places and the input rule in none.
     */
    static boolean isUnusableMapping(String cmisPropertyId, String catalogName) {
        return isForbiddenSourceProperty(cmisPropertyId) || isUnusableCatalogName(catalogName);
    }

    /**
     * One WARN per (type, property, name), because this runs on every parse of every repository's
     * configuration and an operator needs to see it without it drowning the log.
     */
    private void warnOnceAboutRejectedMapping(String typeId, String cmisPropertyId,
                                              String catalogName) {
        String key = typeId + "\u0000" + cmisPropertyId + "\u0000" + catalogName;
        if (warnedRejectedMappings.add(key)) {
            logger.warn("Ignoring property mapping '{}.{}' -> catalog attribute '{}': the name is"
                    + " blank or reserved for a core entity attribute. The mapping is skipped;"
                    + " other mappings still project. Remove it via the admin UI.",
                    typeId, cmisPropertyId, catalogName);
        }
        rejectedMappingCount.incrementAndGet();
    }

    /** How many mappings have been rejected on load, for operator visibility. */
    public long getRejectedMappingCount() {
        return rejectedMappingCount.get();
    }

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
                // same predicate the load path uses, so save and load cannot disagree
                if (isForbiddenSourceProperty(propEntry.getKey())) {
                    errors.add("Property " + loc + " may not be projected to the catalog under any"
                            + " name: '" + propEntry.getKey() + "' is a forbidden source property");
                    continue;
                }
                if (isUnusableCatalogName(m.catalogName())) {
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
