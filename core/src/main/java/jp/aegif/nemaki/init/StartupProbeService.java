package jp.aegif.nemaki.init;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;

import jp.aegif.nemaki.util.PropertyManager;

/**
 * Probes CouchDB connection state at startup and exposes setup-required flag.
 *
 * Executes before DatabasePreInitializer (@Order(1)) so that other initializers
 * can check {@link #isSetupRequired()} to decide whether to skip patch execution.
 */
@Component
@Order(0)
public class StartupProbeService implements ApplicationListener<ContextRefreshedEvent> {

    private static final Log log = LogFactory.getLog(StartupProbeService.class);

    public enum StartupState {
        DB_UNREACHABLE,
        DB_CONNECTED_EMPTY,
        DB_CONNECTED_CURRENT,
        DB_CONNECTED_LEGACY
    }

    private final AtomicReference<StartupState> currentState = new AtomicReference<>(StartupState.DB_UNREACHABLE);
    private final AtomicBoolean setupRequired = new AtomicBoolean(true);
    private final AtomicBoolean probed = new AtomicBoolean(false);

    /** Setup token for authenticating Setup API requests during Setup Mode. */
    private final String setupToken = UUID.randomUUID().toString();

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Required view counts (shared with DatabasePreInitializer).
     *
     * <p><b>Deprecated as the source-of-truth in RC4 (R3)</b>. The
     * shipped dump now ships 40 main / 8 closet views; bumping the
     * dump without keeping these constants in sync used to silently
     * leave installs under-checked. The probe falls back to these
     * counts only when {@link #expectedViewNamesFromDump} cannot read
     * the dump file (classpath-stripped builds etc). The new check is
     * a NAME-SET subset comparison so a dump that adds a view will
     * automatically tighten the gate without a constant bump, and
     * custom user-added views are tolerated as before.
     */
    static final int REQUIRED_VIEWS_MAIN = 38;
    static final int REQUIRED_VIEWS_CLOSET = 8;

    /**
     * Cached set of view names that the shipped main-repo dump
     * declares. Read once from {@code /initialization/bedroom_init.dump}
     * on first call and memoised. Empty set means the dump couldn't be
     * read; callers fall back to the integer threshold.
     */
    private static volatile Set<String> CACHED_MAIN_VIEW_NAMES = null;
    private static volatile Set<String> CACHED_CLOSET_VIEW_NAMES = null;

    /**
     * Reads the design-document view names from a shipped dump file
     * on the classpath. Returns an empty set if the dump can't be
     * loaded — callers should fall back to the integer threshold.
     *
     * <p>The result is memoised at JVM scope; dump files don't change
     * at runtime so re-reading is wasteful.
     */
    static Set<String> expectedViewNamesFromDump(String classpathResource, java.util.concurrent.atomic.AtomicReference<Set<String>> cacheRef) {
        Set<String> cached = cacheRef.get();
        if (cached != null) return cached;
        Set<String> resolved = new java.util.HashSet<>();
        try (java.io.InputStream in = StartupProbeService.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                log.warn("Cannot read dump file from classpath: " + classpathResource + " — falling back to integer threshold");
            } else {
                JsonNode arr = mapper.readTree(in);
                if (arr != null && arr.isArray()) {
                    for (JsonNode entry : arr) {
                        JsonNode doc = entry.get("document");
                        if (doc == null) continue;
                        JsonNode id = doc.get("_id");
                        if (id == null || !id.asText().startsWith("_design/")) continue;
                        JsonNode views = doc.get("views");
                        if (views == null || !views.isObject()) continue;
                        java.util.Iterator<String> names = views.fieldNames();
                        while (names.hasNext()) resolved.add(names.next());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse dump for expected view names (" + classpathResource + "): " + e.getMessage());
        }
        cacheRef.compareAndSet(null, java.util.Collections.unmodifiableSet(resolved));
        return cacheRef.get();
    }

    private static final java.util.concurrent.atomic.AtomicReference<Set<String>> MAIN_DUMP_VIEWS_REF =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Set<String>> CLOSET_DUMP_VIEWS_REF =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static Set<String> expectedMainViewNames() {
        Set<String> s = CACHED_MAIN_VIEW_NAMES;
        if (s != null) return s;
        s = expectedViewNamesFromDump("/initialization/bedroom_init.dump", MAIN_DUMP_VIEWS_REF);
        CACHED_MAIN_VIEW_NAMES = s;
        return s;
    }

    public static Set<String> expectedClosetViewNames() {
        Set<String> s = CACHED_CLOSET_VIEW_NAMES;
        if (s != null) return s;
        s = expectedViewNamesFromDump("/initialization/archive_init.dump", CLOSET_DUMP_VIEWS_REF);
        CACHED_CLOSET_VIEW_NAMES = s;
        return s;
    }

    /** Cached set of main repository IDs (populated by discoverNemakiDatabases). */
    private volatile Set<String> mainRepositoryIds = null;

    @Autowired(required = false)
    private PropertyManager propertyManager;

    // Setter for Spring XML injection (fallback)
    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!probed.compareAndSet(false, true)) {
            return;
        }
        log.info("=== StartupProbeService: probing CouchDB connection ===");

        String url = resolveCouchDbUrl();
        String user = resolveCouchDbUser();
        String pass = resolveCouchDbPassword();

        CouchDbConnectionResult conn = testConnection(url, user, pass);
        if (!conn.isReachable()) {
            currentState.set(StartupState.DB_UNREACHABLE);
            setupRequired.set(true);
            log.warn("StartupProbeService: CouchDB unreachable (" + conn.getErrorMessage() + ") → setupRequired=true");
        } else {
            // CouchDB reachable — check all main repository DBs
            List<RepositoryOverview> repos = probeRepositories(url, user, pass);
            evaluateRepositoryState(repos, "startup");
        }

        // Always publish token when entering Setup Mode — including DB_UNREACHABLE.
        // Without this, operators have no way to authenticate Setup API requests.
        logSetupToken();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    public boolean isSetupRequired() {
        return setupRequired.get();
    }

    public StartupState getCurrentState() {
        return currentState.get();
    }

    /**
     * Returns the setup token. Used by SetupModeGuardFilter to validate requests.
     */
    public String getSetupToken() {
        return setupToken;
    }

    /**
     * Mark setup as complete (called by SetupApplyResource after successful apply).
     */
    public void markSetupComplete() {
        StartupState previous = currentState.get();
        setupRequired.set(false);
        currentState.set(StartupState.DB_CONNECTED_CURRENT);
        log.info("StartupProbeService: setup marked complete → setupRequired=false (was " + previous + ")");
    }

    /**
     * Re-evaluate CouchDB state using the configured connection.
     *
     * Called after DatabasePreInitializer completes DB initialization so that
     * the startup state reflects the actual post-init reality. Without this,
     * a startup that begins with DB_UNREACHABLE or DB_CONNECTED_EMPTY would
     * stay in that state even after the initializer successfully creates and
     * populates databases.
     *
     * Updates both currentState AND setupRequired flag.
     * For Setup Mode flows that must not flip setupRequired, use {@link #reprobeState()} instead.
     */
    public void reprobe() {
        String url = resolveCouchDbUrl();
        String user = resolveCouchDbUser();
        String pass = resolveCouchDbPassword();

        StartupState previous = currentState.get();
        boolean wasSetupRequired = setupRequired.get();

        CouchDbConnectionResult conn = testConnection(url, user, pass);
        if (!conn.isReachable()) {
            currentState.set(StartupState.DB_UNREACHABLE);
            setupRequired.set(true);
            logStateTransition(previous, StartupState.DB_UNREACHABLE, wasSetupRequired, true);
            // Re-announce token if entering Setup Mode from Normal Mode
            if (!wasSetupRequired) {
                logSetupToken();
            }
            return;
        }

        List<RepositoryOverview> repos = probeRepositories(url, user, pass);
        evaluateRepositoryState(repos, "reprobe");
        logStateTransition(previous, currentState.get(), wasSetupRequired, setupRequired.get());

        // Re-announce token if entering Setup Mode from Normal Mode
        if (!wasSetupRequired && setupRequired.get()) {
            logSetupToken();
        }
    }

    /**
     * Re-evaluate CouchDB state WITHOUT changing the setupRequired flag.
     *
     * Used by SetupApplyResource to verify DB readiness before deferred
     * initialization. Unlike {@link #reprobe()}, this method only updates
     * currentState so that Setup Mode remains open if deferred init fails,
     * allowing the user to retry via /apply/mark-complete.
     *
     * The setupRequired flag is only cleared by {@link #markSetupComplete()}
     * after ALL initialization phases have succeeded.
     */
    public void reprobeState() {
        String url = resolveCouchDbUrl();
        String user = resolveCouchDbUser();
        String pass = resolveCouchDbPassword();

        StartupState previous = currentState.get();

        CouchDbConnectionResult conn = testConnection(url, user, pass);
        if (!conn.isReachable()) {
            currentState.set(StartupState.DB_UNREACHABLE);
            log.info("reprobeState: " + previous + " → DB_UNREACHABLE (setupRequired unchanged)");
            return;
        }

        List<RepositoryOverview> repos = probeRepositories(url, user, pass);

        // Evaluate state from main repositories only (as defined in repositories.yml)
        List<RepositoryOverview> mainRepos = repos.stream()
                .filter(r -> isMainRepository(r.getRepositoryId()))
                .toList();

        boolean anyMainCurrent = mainRepos.stream().anyMatch(r -> "current".equals(r.getJudgment()));
        boolean allMainEmpty = mainRepos.isEmpty() || mainRepos.stream().allMatch(r -> "empty".equals(r.getJudgment()));
        boolean anyMainLegacy = mainRepos.stream().anyMatch(r -> "legacy".equals(r.getJudgment()));

        if (allMainEmpty) {
            currentState.set(StartupState.DB_CONNECTED_EMPTY);
        } else if (anyMainCurrent && !anyMainLegacy) {
            currentState.set(StartupState.DB_CONNECTED_CURRENT);
        } else {
            currentState.set(StartupState.DB_CONNECTED_LEGACY);
        }
        log.info("reprobeState: " + previous + " → " + currentState.get() + " (setupRequired unchanged)");
    }

    /**
     * Check if a database name is a main repository (not an archive or config DB).
     * Uses the cached set from repositories.yml when available; otherwise falls back
     * to heuristic (not ending with _closet and not nemaki_conf).
     */
    public boolean isMainRepository(String dbName) {
        Set<String> cached = this.mainRepositoryIds;
        if (cached != null && !cached.isEmpty()) {
            return cached.contains(dbName);
        }
        // Heuristic fallback (covers fresh install before repositories.yml is parsed)
        return !"nemaki_conf".equals(dbName) && !dbName.endsWith("_closet");
    }

    /**
     * Evaluate repository state from probed overviews and set currentState + setupRequired.
     * Used by onApplicationEvent() and reprobe().
     */
    private void evaluateRepositoryState(List<RepositoryOverview> repos, String context) {
        List<RepositoryOverview> mainRepos = repos.stream()
                .filter(r -> isMainRepository(r.getRepositoryId()))
                .toList();

        boolean anyMainCurrent = mainRepos.stream().anyMatch(r -> "current".equals(r.getJudgment()));
        boolean allMainEmpty = mainRepos.isEmpty() || mainRepos.stream().allMatch(r -> "empty".equals(r.getJudgment()));
        boolean anyMainLegacy = mainRepos.stream().anyMatch(r -> "legacy".equals(r.getJudgment()));

        if (allMainEmpty) {
            currentState.set(StartupState.DB_CONNECTED_EMPTY);
            setupRequired.set(true);
            log.info("StartupProbeService (" + context + "): all main repos empty or absent → setupRequired=true");
        } else if (anyMainCurrent && !anyMainLegacy) {
            currentState.set(StartupState.DB_CONNECTED_CURRENT);
            setupRequired.set(false);
            log.info("StartupProbeService (" + context + "): main repos current → setupRequired=false (Normal Mode)");
        } else {
            currentState.set(StartupState.DB_CONNECTED_LEGACY);
            setupRequired.set(true);
            log.info("StartupProbeService (" + context + "): main repos legacy → setupRequired=true");
        }
    }

    private void logStateTransition(StartupState from, StartupState to,
                                     boolean wasSetupRequired, boolean isSetupRequired) {
        if (from == to && wasSetupRequired == isSetupRequired) {
            log.info("StartupProbeService reprobe: state unchanged (" + to
                    + ", setupRequired=" + isSetupRequired + ")");
        } else {
            log.info("StartupProbeService reprobe: state transition " + from + " → " + to
                    + ", setupRequired " + wasSetupRequired + " → " + isSetupRequired);
        }
    }

    /**
     * Test CouchDB connection with explicit credentials.
     */
    public CouchDbConnectionResult testConnection(String url, String user, String pass) {
        CouchDbConnectionResult result = new CouchDbConnectionResult();
        long start = System.currentTimeMillis();
        String authHeader = basicAuth(user, pass);
        HttpURLConnection conn = null;
        try {
            // Step 1: GET / — verify it is actually CouchDB (requires "couchdb" field)
            URL endpoint = new URL(url + "/");
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setInstanceFollowRedirects(false);  // SSRF: prevent redirect to internal/metadata IPs
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", authHeader);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            result.setResponseTimeMs(System.currentTimeMillis() - start);

            if (code != 200) {
                result.setReachable(false);
                result.setErrorMessage("HTTP " + code);
                conn.disconnect();
                return result;
            }

            String body = readResponse(conn);
            conn.disconnect();
            conn = null;
            JsonNode root = mapper.readTree(body);

            // Require "couchdb" field to confirm the endpoint is a CouchDB instance
            if (!root.has("couchdb")) {
                result.setReachable(false);
                result.setErrorMessage("Not a CouchDB endpoint (missing 'couchdb' field)");
                return result;
            }
            if (root.has("version")) {
                result.setCouchDbVersion(root.get("version").asText());
            }

            // Step 2: GET /_session — verify credentials are accepted
            conn = openGet(url + "/_session", authHeader);
            int sessionCode = conn.getResponseCode();
            if (sessionCode != 200) {
                result.setReachable(false);
                result.setErrorMessage("Credential check failed (/_session HTTP " + sessionCode + ")");
                conn.disconnect();
                return result;
            }
            String sessionBody = readResponse(conn);
            conn.disconnect();
            conn = null;
            JsonNode sessionRoot = mapper.readTree(sessionBody);

            // CouchDB returns {"ok":true,"userCtx":{"name":"admin","roles":["_admin"]}}
            // for valid credentials and {"ok":true,"userCtx":{"name":null,...}} for invalid.
            JsonNode userCtx = sessionRoot.path("userCtx");
            JsonNode nameNode = userCtx.path("name");
            if (nameNode.isMissingNode() || nameNode.isNull()) {
                result.setReachable(false);
                result.setErrorMessage("Invalid credentials (authentication failed)");
                return result;
            }

            result.setReachable(true);
        } catch (Exception e) {
            result.setReachable(false);
            result.setResponseTimeMs(System.currentTimeMillis() - start);
            result.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    /**
     * Probe all known repository databases and return overviews.
     * Uses repositories.yml as the authoritative source for database discovery.
     */
    public List<RepositoryOverview> probeRepositories(String url, String user, String pass) {
        List<RepositoryOverview> list = new ArrayList<>();
        List<String> dbNames = discoverNemakiDatabases(url, user, pass);
        log.info("probeRepositories: discovered databases: " + dbNames);

        for (String dbName : dbNames) {
            RepositoryOverview ov = probeOneRepository(url, user, pass, dbName);
            list.add(ov);
        }
        return list;
    }

    /**
     * Discover NemakiWare-related databases using repositories.yml as the
     * authoritative source.
     *
     * <p>Priority:
     * <ol>
     *   <li>Parse {@code repositories.yml} (path from {@code -Drepositories.yml}
     *       system property, or {@code PropertyManager} key {@code repository.definition})
     *       → extract {@code id} (main DB) and {@code archive} (archive DB) per repository</li>
     *   <li>If repositories.yml cannot be loaded and
     *       {@code -Dnemaki.startup.require-repositories-yml=false},
     *       fall back to the legacy default list</li>
     *   <li>Otherwise throw IllegalStateException</li>
     * </ol>
     *
     * <p>{@code nemaki_conf} is always included.
     *
     * <p>Note: {@code url}, {@code user}, {@code pass} parameters are currently unused
     * (retained for API compatibility with existing callers).
     *
     * @return ordered list of database names (main repos first, then their archives, then nemaki_conf)
     * @throws IllegalStateException if repositories.yml cannot be loaded and fail-fast is enabled
     */
    public List<String> discoverNemakiDatabases(String url, String user, String pass) {
        // Try repositories.yml (authoritative source)
        List<String> fromYaml = loadDbNamesFromRepositoriesYaml();
        if (fromYaml != null && !fromYaml.isEmpty()) {
            log.info("discoverNemakiDatabases: loaded from repositories.yml: " + fromYaml);
            return fromYaml;
        }

        // Feature flag: allow legacy fallback during migration period
        // Set -Dnemaki.startup.require-repositories-yml=false to use legacy default list
        String requireYaml = System.getProperty("nemaki.startup.require-repositories-yml", "true");
        if ("false".equalsIgnoreCase(requireYaml)) {
            log.warn("discoverNemakiDatabases: repositories.yml not found — using legacy default list " +
                    "(nemaki.startup.require-repositories-yml=false)");
            return Arrays.asList("bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf");
        }

        // repositories.yml is required — fail explicitly
        String yamlPath = resolveRepositoriesYamlPath();
        String msg = "repositories.yml could not be loaded (resolved path: " + yamlPath + "). " +
                "This file is required for NemakiWare to determine which databases to manage. " +
                "Set -Dnemaki.startup.require-repositories-yml=false to use legacy defaults during migration.";
        log.error(msg);
        throw new IllegalStateException(msg);
    }

    /**
     * Parse repositories.yml and extract the list of DB names
     * (main repo id + archive id per repository, plus nemaki_conf).
     *
     * <p>YAML structure expected:
     * <pre>
     * repositories:
     *   - id: bedroom
     *     archive: bedroom_closet
     *   - id: canopy
     *     archive: canopy_closet
     * </pre>
     *
     * @return list of DB names, or null if repositories.yml is unreadable
     */
    @SuppressWarnings("unchecked")
    private List<String> loadDbNamesFromRepositoriesYaml() {
        // Resolve the YAML file path
        String yamlPath = resolveRepositoriesYamlPath();
        if (yamlPath == null) {
            log.debug("loadDbNamesFromRepositoriesYaml: no repositories.yml path resolved");
            return null;
        }

        try {
            java.io.InputStream is = null;

            // Strip "file:" / "file://" prefix that Maven Jetty plugin adds when
            // system properties are passed as "file:${project.basedir}/...".
            String resolvedPath = yamlPath;
            if (resolvedPath.startsWith("file://")) {
                resolvedPath = resolvedPath.substring(7);
            } else if (resolvedPath.startsWith("file:")) {
                resolvedPath = resolvedPath.substring(5);
            }

            // Try as file system path first (Docker: -Drepositories.yml=/usr/local/tomcat/conf/repositories.yml)
            java.io.File file = new java.io.File(resolvedPath);
            if (file.isFile()) {
                is = new java.io.FileInputStream(file);
            }

            // Try as URL (handles file:, http:, etc.)
            if (is == null && yamlPath.contains(":")) {
                try {
                    is = new java.net.URI(yamlPath).toURL().openStream();
                } catch (Exception e) {
                    log.debug("loadDbNamesFromRepositoriesYaml: not a valid URL: " + yamlPath);
                }
            }

            // Try as classpath resource (e.g. "repositories.yml" in WEB-INF/classes)
            if (is == null) {
                is = getClass().getClassLoader().getResourceAsStream(yamlPath);
            }

            if (is == null) {
                log.debug("loadDbNamesFromRepositoriesYaml: file not found: " + yamlPath);
                return null;
            }

            // Parse YAML using jvyaml (same library as YamlManager)
            java.io.Reader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
            Object yamlData = org.jvyaml.YAML.load(reader);
            reader.close();
            is.close();

            if (!(yamlData instanceof java.util.Map)) {
                log.debug("loadDbNamesFromRepositoriesYaml: unexpected YAML structure");
                return null;
            }
            java.util.Map<String, Object> root = (java.util.Map<String, Object>) yamlData;
            Object reposObj = root.get("repositories");
            if (!(reposObj instanceof java.util.List)) {
                log.debug("loadDbNamesFromRepositoriesYaml: 'repositories' is not a list");
                return null;
            }

            List<String> dbNames = new ArrayList<>();
            Set<String> mainIds = ConcurrentHashMap.newKeySet();
            java.util.List<Object> repos = (java.util.List<Object>) reposObj;
            for (Object entry : repos) {
                if (entry instanceof java.util.Map) {
                    java.util.Map<String, Object> repo = (java.util.Map<String, Object>) entry;
                    String id = repo.get("id") != null ? repo.get("id").toString() : null;
                    String archive = repo.get("archive") != null ? repo.get("archive").toString() : null;
                    if (id != null && !id.isEmpty()) {
                        dbNames.add(id);
                        mainIds.add(id);
                        if (archive != null && !archive.isEmpty()) {
                            dbNames.add(archive);
                        }
                    }
                }
            }

            // Cache main repository IDs for use in evaluateRepositoryState
            if (!mainIds.isEmpty()) {
                this.mainRepositoryIds = Collections.unmodifiableSet(mainIds);
            }

            // Always include nemaki_conf
            if (!dbNames.contains("nemaki_conf")) {
                dbNames.add("nemaki_conf");
            }

            return dbNames.isEmpty() ? null : dbNames;
        } catch (Exception e) {
            log.debug("loadDbNamesFromRepositoriesYaml: parse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the file system path or classpath name for repositories.yml.
     *
     * <p>Checks in order:
     * <ol>
     *   <li>{@code -Drepositories.yml} system property (Docker compose sets this)</li>
     *   <li>{@code PropertyManager} key {@code repository.definition}</li>
     * </ol>
     */
    private String resolveRepositoriesYamlPath() {
        // 1. System property (set by docker-compose CATALINA_OPTS: -Drepositories.yml=<path>)
        String sysProp = System.getProperty("repositories.yml");
        if (sysProp != null && !sysProp.trim().isEmpty()) {
            return sysProp.trim();
        }

        // 2. PropertyManager (reads from nemakiware.properties: repository.definition=repositories.yml)
        if (propertyManager != null) {
            String val = propertyManager.readValue("repository.definition");
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Writes the setup token to a well-known file and logs a masked reference.
     *
     * <p>The full token is persisted to {@code <catalina.base>/conf/setup-token}
     * (with fallbacks to {@code <catalina.base>/work/} then {@code java.io.tmpdir}).
     * Log messages only show the first 8 hex characters.
     *
     * <p>If <b>all</b> file-write attempts fail, the full token is written to
     * the log as a last resort to prevent operator lockout.  This is acceptable
     * because a file-system failure in a container already implies an abnormal
     * environment where log confidentiality is the lesser concern.
     *
     * <p>Operators retrieve the full token via:
     * <pre>
     *   docker exec &lt;container&gt; cat /usr/local/tomcat/conf/setup-token
     * </pre>
     */
    private void logSetupToken() {
        if (!setupRequired.get()) {
            return;
        }
        String masked = setupToken.length() >= 8
                ? setupToken.substring(0, 8) + "..."
                : setupToken;
        String tokenFilePath = writeTokenFile();

        log.warn("========================================================");
        log.warn("  SETUP MODE ACTIVE");
        log.warn("  Setup Token (masked): " + masked);
        if (tokenFilePath != null) {
            log.warn("  Full token file: " + tokenFilePath);
        } else {
            // Last resort: log the full token to prevent operator lockout.
            // This path is reached only when ALL file-write candidates fail.
            log.warn("  Setup Token: " + setupToken);
            log.warn("  (File write failed — token logged as fallback)");
        }
        log.warn("  Use header: X-Setup-Token: <full-token>");
        log.warn("========================================================");
    }

    /**
     * Writes the full setup token to a file readable only by the container operator.
     *
     * <p>Tries multiple directories in order:
     * <ol>
     *   <li>{@code <catalina.base>/conf/}</li>
     *   <li>{@code <catalina.base>/work/}</li>
     *   <li>{@code java.io.tmpdir}</li>
     * </ol>
     *
     * @return the absolute path on success, {@code null} if all attempts fail
     */
    String writeTokenFile() {
        String base = System.getProperty("catalina.base");
        // Build candidate directories in priority order
        List<Path> candidates = new ArrayList<>();
        if (base != null) {
            candidates.add(Path.of(base, "conf"));
            candidates.add(Path.of(base, "work"));
        }
        String tmpDir = System.getProperty("java.io.tmpdir");
        if (tmpDir != null) {
            candidates.add(Path.of(tmpDir));
        }

        for (Path dir : candidates) {
            try {
                Files.createDirectories(dir);
                Path tokenFile = dir.resolve("setup-token");
                Files.writeString(tokenFile, setupToken + "\n", StandardCharsets.UTF_8);
                // Best-effort permission restriction (owner-only read)
                tokenFile.toFile().setReadable(false, false);
                tokenFile.toFile().setReadable(true, true);
                tokenFile.toFile().setWritable(false, false);
                tokenFile.toFile().setWritable(true, true);
                return tokenFile.toAbsolutePath().toString();
            } catch (Exception e) {
                log.debug("Failed to write setup-token to " + dir + ": " + e.getMessage());
            }
        }
        log.warn("Failed to write setup-token file to any candidate directory");
        return null;
    }

    private RepositoryOverview probeOneRepository(String url, String user, String pass, String dbName) {
        RepositoryOverview ov = new RepositoryOverview();
        ov.setRepositoryId(dbName);
        ov.setName(dbName);
        ov.setMainRepository(isMainRepository(dbName));

        int requiredViews;
        if (isMainRepository(dbName)) {
            requiredViews = REQUIRED_VIEWS_MAIN;
        } else if ("nemaki_conf".equals(dbName)) {
            requiredViews = 0;
        } else {
            // Archive DBs (closet or custom archive names)
            requiredViews = REQUIRED_VIEWS_CLOSET;
        }
        ov.setRequiredViewCount(requiredViews);

        String authHeader = basicAuth(user, pass);

        // Check DB existence + doc_count
        try {
            HttpURLConnection conn = openGet(url + "/" + dbName, authHeader);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                ov.setDocumentCount(0);
                ov.setViewCount(0);
                ov.setJudgment("empty");
                // 404 = DB does not exist yet (normal for fresh install).
                // 401/403 = authentication failure (misconfiguration).
                // Other codes = unexpected error.
                if (code == 401 || code == 403) {
                    ov.setError("Authentication failed (HTTP " + code + ")");
                } else if (code != 404) {
                    ov.setError("Unexpected response (HTTP " + code + ")");
                }
                return ov;
            }
            String body = readResponse(conn);
            conn.disconnect();
            JsonNode dbInfo = mapper.readTree(body);
            long docCount = dbInfo.has("doc_count") ? dbInfo.get("doc_count").asLong() : 0;
            ov.setDocumentCount(docCount);

            if (docCount == 0) {
                ov.setJudgment("empty");
                return ov;
            }
        } catch (Exception e) {
            log.debug("probeOneRepository: error checking " + dbName + ": " + e.getMessage());
            ov.setJudgment("empty");
            ov.setError("Connection failed: " + e.getMessage());
            return ov;
        }

        // Check design doc views
        int viewCount = 0;
        try {
            HttpURLConnection conn = openGet(url + "/" + dbName + "/_design/_repo", authHeader);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readResponse(conn);
                JsonNode designDoc = mapper.readTree(body);
                if (designDoc.has("views")) {
                    viewCount = designDoc.get("views").size();
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            log.debug("probeOneRepository: error reading design doc for " + dbName + ": " + e.getMessage());
        }
        ov.setViewCount(viewCount);

        // Check .system folder (only for main repos)
        boolean hasSystem = false;
        if (isMainRepository(dbName)) {
            hasSystem = checkSystemFolder(url, dbName, authHeader);
        }
        ov.setHasSystemFolder(hasSystem);

        // Judge: view count is the authoritative check for repository health.
        // hasSystemFolder is informational only — the _find query can time out
        // on large databases (100K+ docs) without a Mango index, causing false
        // "legacy" judgments that block Normal Mode startup.
        if (requiredViews > 0 && viewCount >= requiredViews) {
            ov.setJudgment("current");
        } else if (ov.getDocumentCount() > 0) {
            ov.setJudgment("legacy");
        } else {
            ov.setJudgment("empty");
        }
        return ov;
    }

    private boolean checkSystemFolder(String url, String dbName, String authHeader) {
        try {
            // Use CouchDB Mango query (_find) to check for .system folder.
            // The bedroom _design/_repo does NOT have an "all" view, so we cannot
            // use /_view/all.  Mango queries are supported since CouchDB 2.x.
            URL findUrl = new URL(url + "/" + dbName + "/_find");
            HttpURLConnection conn = (HttpURLConnection) findUrl.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", authHeader);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            String query = "{\"selector\":{\"type\":\"cmis:folder\",\"name\":\".system\"},\"limit\":1,\"fields\":[\"_id\"]}";
            try (java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(
                    conn.getOutputStream(), StandardCharsets.UTF_8)) {
                out.write(query);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readResponse(conn);
                JsonNode result = mapper.readTree(body);
                if (result.has("docs") && result.get("docs").size() > 0) {
                    conn.disconnect();
                    return true;
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            log.debug("checkSystemFolder: " + e.getMessage());
        }
        return false;
    }

    private HttpURLConnection openGet(String urlStr, String authHeader) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(false);  // SSRF: prevent redirect to internal/metadata IPs
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Authorization", authHeader);
        conn.setRequestMethod("GET");
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String basicAuth(String user, String pass) {
        String credentials = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveCouchDbUrl() {
        if (propertyManager != null) {
            String val = propertyManager.readValue("db.couchdb.url");
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        // Env var override (Docker compose)
        String env = System.getenv("DB_COUCHDB_URL");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return "http://couchdb:5984";
    }

    private static final String CONFIG_PLACEHOLDER = "OVERRIDE_VIA_SYSTEM_PROPERTY";

    private String resolveCouchDbUser() {
        return resolveRequiredCredential("db.couchdb.auth.username");
    }

    private String resolveCouchDbPassword() {
        return resolveRequiredCredential("db.couchdb.auth.password");
    }

    private String resolveRequiredCredential(String key) {
        // Prefer the propertyManager when wired (production path) so the
        // resolution honours all PropertyManager precedence (system → env →
        // CouchDB dynamic → properties file). When propertyManager is not
        // wired (e.g. unit tests that instantiate StartupProbeService
        // directly), fall back to System.getProperty / getenv so the same
        // precedence still works for the first two layers — otherwise this
        // would throw immediately even when a perfectly valid -D system
        // property has been supplied.
        String val = null;
        if (propertyManager != null) {
            val = propertyManager.readValue(key);
        } else {
            val = System.getProperty(key);
            if (val == null || val.isEmpty()) {
                val = System.getenv(key.toUpperCase().replace('.', '_'));
            }
        }
        if (val != null && !val.trim().isEmpty()
                && !CONFIG_PLACEHOLDER.equals(val.trim())) {
            return val.trim();
        }
        throw new IllegalStateException(
            key + " not configured: must be set via -D system property, env var, "
            + "or nemakiware.properties (placeholder values are rejected).");
    }
}
