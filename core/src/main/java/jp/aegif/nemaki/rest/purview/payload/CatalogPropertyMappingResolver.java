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
     * The <em>input</em> half of {@link #rejectionFor}: CMIS properties that may never be
     * projected, whatever the mapping calls the output.
     *
     * <p>The reserved-name rule guards the output side: it stops a mapping from writing over
     * {@code cloudFileUrl}. It says nothing about a mapping that reads
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

    /**
     * The <em>output</em> half of {@link #rejectionFor}: core entity attribute names used by
     * {@code nemaki_document} and {@code nemaki_folder}, which a custom mapping may not write
     * over.
     *
     * <p>Both halves are needed. A mapping can carry a forbidden property out under a name that
     * is not on this list at all, which is what {@link #FORBIDDEN_SOURCE_PROPERTY_IDS} catches.
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
     *
     * @param catalogName null only on a <em>disabled</em> mapping whose stored value was absent or
     *                    not a string. An enabled mapping in that shape is dropped at parse, so
     *                    nothing that projects ever carries a null here.
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
                    // Null when the stored value is absent or not a string. It used to fall back
                    // to the CMIS property id, which invented an output name nobody configured —
                    // "nemaki:dept" as an Atlas attribute — and did it silently. There is no
                    // compatibility contract for that fallback, so a malformed value is now a
                    // configuration error like any other: this one mapping is dropped, the rest
                    // still project.
                    boolean catalogNamePresent = fields.containsKey("catalogName");
                    Object rawCatalogName = fields.get("catalogName");
                    String catalogName = rawCatalogName instanceof String text ? text : null;
                    // Enforced on read, not only in saveMappings. saveMappings is one way a
                    // mapping arrives; a configuration written before this rule existed, a
                    // restore, a hand-edited CouchDB document and a corrupted value are the
                    // others, and every one of them reaches the payload through this parse.
                    // A reserved catalogName lets a custom property overwrite a core attribute
                    // — including cloudFileUrl, which increment A-1g sets to null precisely so
                    // that no stored URL reaches the catalog.
                    // A disabled mapping projects nothing, so it is kept verbatim as the
                    // operator's configuration — the admin UI still has to show and fix it — and
                    // is never counted as rejected.
                    Rejection rejection = enabled
                            ? rejectionForStored(propEntry.getKey(), catalogName,
                                    catalogNamePresent, rawCatalogName)
                            : null;
                    if (rejection != null) {
                        warnOnceAboutRejectedMapping(typeEntry.getKey(), propEntry.getKey(),
                                catalogName, rejection);
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
     * Why a mapping may not be projected, or {@code null} if it may.
     *
     * <p><b>The one entry point for the live rules</b> — forbidden source property, blank name,
     * reserved name. Its callers, precisely: {@code saveMappings}, the payload boundary
     * ({@code appendCustomPropertyValues}), and the admin PUT
     * ({@code IntegrationSettingsController.updatePropertyMappings}, enabled mappings only —
     * a disabled mapping may omit its name). The <em>load</em> path calls
     * {@link #rejectionForStored} instead, which applies these same rules and additionally
     * distinguishes the two stored-shape defects a live caller cannot produce:
     * {@code MISSING_CATALOG_NAME} (no field / JSON null) and {@code MALFORMED_CATALOG_NAME}
     * (a non-string value). One rule set, two entry points, split only by what their inputs can
     * be malformed as — the previous arrangement (a boolean for load, two booleans for save) is
     * exactly the split that let the output-name rule be enforced in two places while the input
     * rule was enforced in none.
     *
     * <p>It returns a reason rather than a boolean so that {@link #validateMappings} can still
     * tell an operator which end of the mapping is wrong without re-deriving it.
     *
     * <p>Names are trimmed before comparison because {@code appendCustomPropertyValues} uses the
     * raw value as the map key: {@code " cloudFileUrl"} would not collide, but a configuration
     * containing it is a mistake either way.
     */
    public static Rejection rejectionFor(String cmisPropertyId, String catalogName) {
        if (cmisPropertyId != null
                && FORBIDDEN_SOURCE_PROPERTY_IDS.contains(cmisPropertyId.trim())) {
            return Rejection.FORBIDDEN_SOURCE_PROPERTY;
        }
        if (catalogName == null || catalogName.trim().isEmpty()) {
            return Rejection.BLANK_CATALOG_NAME;
        }
        if (RESERVED_ATTRIBUTE_NAMES.contains(catalogName.trim())) {
            return Rejection.RESERVED_CATALOG_NAME;
        }
        return null;
    }

    /**
     * {@link #rejectionFor} plus the two shapes only stored JSON can produce.
     *
     * <p>{@code rejectionFor} takes a {@code String} and so cannot distinguish "the field was
     * absent" from "the field held a number". The parse can, and an operator fixing a
     * hand-edited document needs to be told which.
     */
    private static Rejection rejectionForStored(String cmisPropertyId, String catalogName,
                                                boolean present, Object raw) {
        Rejection rejection = rejectionFor(cmisPropertyId, catalogName);
        if (rejection != Rejection.BLANK_CATALOG_NAME) {
            return rejection;   // a forbidden input outranks the shape of the output name
        }
        if (!present || raw == null) {
            return Rejection.MISSING_CATALOG_NAME;
        }
        return catalogName == null ? Rejection.MALFORMED_CATALOG_NAME : rejection;
    }

    /** Why {@link #rejectionFor} refused a mapping; each spells its own operator message. */
    public enum Rejection {

        /** The CMIS property may not leave the repository under any name. */
        FORBIDDEN_SOURCE_PROPERTY(
                "that CMIS property is not projected to the catalog under any name"),

        BLANK_CATALOG_NAME("the catalog attribute name is empty"),

        /** The stored configuration has no {@code catalogName} field, or it is JSON null. */
        MISSING_CATALOG_NAME("the stored configuration has no catalog attribute name"),

        /** The stored {@code catalogName} is a number, object, array or boolean. */
        MALFORMED_CATALOG_NAME("the stored catalog attribute name is not a string"),

        /** The name belongs to a core entity attribute, which a custom value must not replace. */
        RESERVED_CATALOG_NAME(
                "the catalog attribute name is reserved for a core entity attribute");

        private final String reason;

        Rejection(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * One WARN per (type, property, name), because this runs on every parse of every repository's
     * configuration and an operator needs to see it without it drowning the log.
     */
    private void warnOnceAboutRejectedMapping(String typeId, String cmisPropertyId,
                                              String catalogName, Rejection rejection) {
        String key = typeId + "\u0000" + cmisPropertyId + "\u0000" + catalogName;
        if (warnedRejectedMappings.add(key)) {
            // the reason comes from the rejection, so a forbidden source property is not
            // reported as a blank or reserved name
            logger.warn("Ignoring property mapping '{}.{}' -> catalog attribute '{}': {}."
                    + " The mapping is skipped; other mappings still project."
                    + " Remove it via the admin UI.",
                    typeId, cmisPropertyId, catalogName, rejection.reason());
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
                // the same entry point load and the payload boundary use, so the three cannot
                // drift apart; the reason is what makes the message specific
                Rejection rejection = rejectionFor(propEntry.getKey(), m.catalogName());
                if (rejection != null) {
                    errors.add("Property " + loc + " -> '" + m.catalogName() + "': "
                            + rejection.reason());
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
