package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.CmisPermission;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Authorization gate for folder-scoped delegation of External Ingestion
 * configuration. Lets a non-admin who holds {@code cmis:all} on a folder
 * manage import profiles bound to that folder, using only connectors that
 * an admin has explicitly delegated.
 *
 * <p><b>Why a separate service.</b> {@code PermissionServiceImpl.checkPermission}
 * is action-keyed (e.g. {@code CAN_APPLY_ACL_OBJECT}) and resolves the
 * required permission via the repository's {@code PermissionMapping}. That
 * makes it sensitive to the operator's permission-mapping configuration —
 * if {@code CAN_APPLY_ACL_OBJECT} were ever remapped to allow {@code cmis:write},
 * a delegation gate built on that key would silently widen. For a security
 * gate we want the explicit invariant "the user effectively holds
 * {@code cmis:all} on this folder", and we want it independent of
 * {@code PermissionMapping} drift. So we resolve principals (username +
 * group expansion + the repository's Anyone principal) ourselves and look
 * for {@code cmis:all} directly in the merged ACL.
 *
 * <p><b>Fail-closed.</b> Any null / missing / failure path returns
 * {@code false}. There is no path where an exception turns into "allowed".
 */
public class IngestAuthorizationService {

    private static final Logger logger = LoggerFactory.getLogger(IngestAuthorizationService.class);

    /** Default ancestor-chain walk cap. Most real folder hierarchies are
     *  shallow (<20 levels); 128 leaves wide headroom while still acting as
     *  a circuit breaker against pathological cycles. Operators can tune
     *  via {@code nemakiware.ingest.ancestorWalk.maxHops} in
     *  {@code nemakiware.properties}. */
    static final int DEFAULT_MAX_ANCESTOR_HOPS = 128;
    static final String MAX_HOPS_PROPERTY = "nemakiware.ingest.ancestorWalk.maxHops";

    private ContentService contentService;
    private PrincipalService principalService;
    private PropertyManager propertyManager;
    /** Resolved lazily on first use; recomputed if property changes mid-flight. */
    private volatile int cachedMaxHops = -1;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPrincipalService(PrincipalService principalService) {
        this.principalService = principalService;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
        // Force re-read on next use
        this.cachedMaxHops = -1;
    }

    /**
     * Returns the configured ancestor-walk cap. Resolves once from
     * {@link PropertyManager}; falls back to {@link #DEFAULT_MAX_ANCESTOR_HOPS}
     * for missing / invalid / negative values. Logs a WARN at most once
     * per JVM lifetime for invalid values so a misconfigured properties
     * file is visible without log spam.
     */
    int maxAncestorHops() {
        int cached = cachedMaxHops;
        if (cached > 0) return cached;
        int resolved = DEFAULT_MAX_ANCESTOR_HOPS;
        if (propertyManager != null) {
            String v = propertyManager.readValue(MAX_HOPS_PROPERTY);
            if (v != null && !v.isBlank()) {
                try {
                    int n = Integer.parseInt(v.trim());
                    if (n > 0) {
                        resolved = n;
                    } else {
                        logger.warn("{}={} is non-positive, falling back to default {}",
                                MAX_HOPS_PROPERTY, v, DEFAULT_MAX_ANCESTOR_HOPS);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("{}='{}' is not an integer, falling back to default {}",
                            MAX_HOPS_PROPERTY, v, DEFAULT_MAX_ANCESTOR_HOPS);
                }
            }
        }
        cachedMaxHops = resolved;
        return resolved;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public boolean isAdmin(CallContext callContext) {
        if (callContext == null) return false;
        Object flag = callContext.get(CallContextKey.IS_ADMIN);
        if (flag instanceof Boolean b && b) return true;
        // Fallback: re-derive from UserItem in case the filter didn't populate
        // the CallContextKey (e.g. internal calls). Stays fail-closed because
        // missing user → !isAdmin.
        try {
            String username = callContext.getUsername();
            if (username == null || username.isBlank()) return false;
            String repositoryId = callContext.getRepositoryId();
            if (repositoryId == null || repositoryId.isBlank()) return false;
            UserItem u = contentService.getUserItemById(repositoryId, username);
            return u != null && Boolean.TRUE.equals(u.isAdmin());
        } catch (RuntimeException e) {
            logger.debug("isAdmin fallback failed, treating as non-admin: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Normalises {@code targetFolderId} / {@code targetFolderPath} into a
     * concrete folder ID, looking up the path against {@code ContentService}
     * if necessary. Returns {@code null} if neither resolves — caller
     * decides whether that's a 400 (missing input) or a 404 (path not found).
     */
    public String resolveFolderId(String repositoryId, String folderId, String folderPath) {
        if (folderId != null && !folderId.isBlank()) return folderId;
        if (folderPath == null || folderPath.isBlank()) return null;
        try {
            Content content = contentService.getContentByPath(repositoryId, folderPath);
            if (content == null || !content.isFolder()) return null;
            return content.getId();
        } catch (RuntimeException e) {
            logger.debug("Path resolution failed for {}/{}: {}", repositoryId, folderPath, e.getMessage());
            return null;
        }
    }

    /**
     * True iff the caller may create / edit / delete a delegated import
     * profile bound to {@code folderId}: admin always passes; otherwise
     * the user must effectively hold {@code cmis:all} on the folder.
     */
    public boolean canManageProfileForFolder(CallContext callContext, String repositoryId, String folderId) {
        if (callContext == null || repositoryId == null || folderId == null || folderId.isBlank()) {
            return false;
        }
        if (isAdmin(callContext)) return true;
        Folder folder = loadFolder(repositoryId, folderId);
        if (folder == null) return false;
        return hasEffectiveCmisAll(callContext, repositoryId, folder);
    }

    /**
     * True iff a delegated profile bound to {@code targetFolderId} may
     * reference {@code connector}, evaluated for {@code callContext}'s user.
     *
     * <p>Order of checks (every step is fail-closed):
     * <ol>
     *   <li>Connector must be enabled and {@code delegated=true}.</li>
     *   <li>Either {@code delegateAllFolders=true}, OR
     *       {@code targetFolderId} is in {@code allowedFolderIds} OR a
     *       descendant of one of them (ancestor chain via parent IDs;
     *       no path-prefix matching).</li>
     *   <li>If {@code allowedPrincipalIds} is non-empty, the user's
     *       expanded principal set (username + groups, fail-closed if
     *       group lookup blows up) must intersect it.</li>
     * </ol>
     *
     * <p>Admins are NOT short-circuited here — admin-owned profiles use
     * the admin-only API path and don't pass through this check at all.
     */
    public boolean canUseConnectorForDelegatedProfile(
            CallContext callContext, String repositoryId,
            ConnectorDefinition connector, String targetFolderId) {
        if (connector == null || !connector.isEnabled() || !connector.isDelegated()) return false;
        if (callContext == null || repositoryId == null || targetFolderId == null || targetFolderId.isBlank()) {
            return false;
        }

        if (!connector.isDelegateAllFolders()) {
            List<String> allowed = connector.getAllowedFolderIds();
            if (allowed == null || allowed.isEmpty()) return false;
            if (!isFolderInAllowedScope(repositoryId, targetFolderId, allowed)) return false;
        }

        List<String> allowedPrincipals = connector.getAllowedPrincipalIds();
        if (allowedPrincipals != null && !allowedPrincipals.isEmpty()) {
            Set<String> userPrincipals = expandPrincipals(repositoryId, callContext.getUsername());
            boolean intersects = false;
            for (String p : allowedPrincipals) {
                if (p != null && userPrincipals.contains(p)) {
                    intersects = true;
                    break;
                }
            }
            if (!intersects) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Internal helpers (package-private for unit testing)
    // ------------------------------------------------------------------

    Folder loadFolder(String repositoryId, String folderId) {
        try {
            return contentService.getFolder(repositoryId, folderId);
        } catch (RuntimeException e) {
            logger.debug("Folder load failed for {}/{}: {}", repositoryId, folderId, e.getMessage());
            return null;
        }
    }

    /**
     * Walks {@code targetFolderId} and its ancestors via parent IDs. Returns
     * true if any node in the chain (inclusive) matches an entry in
     * {@code allowedFolderIds}. Bounded loop to avoid pathological cycles.
     */
    boolean isFolderInAllowedScope(String repositoryId, String targetFolderId, List<String> allowedFolderIds) {
        Set<String> allowed = new HashSet<>(allowedFolderIds);
        String cursor = targetFolderId;
        Set<String> visited = new HashSet<>();
        int hop = 0;
        int cap = maxAncestorHops();
        while (cursor != null && hop++ < cap) {
            if (allowed.contains(cursor)) return true;
            if (!visited.add(cursor)) {
                logger.warn("Cycle detected while walking ancestors of folder {} in repository {}", targetFolderId, repositoryId);
                return false;
            }
            try {
                Folder parent = contentService.getParent(repositoryId, cursor);
                cursor = parent != null ? parent.getId() : null;
            } catch (RuntimeException e) {
                logger.debug("Parent lookup failed at {} (chain from {}): {}", cursor, targetFolderId, e.getMessage());
                return false;
            }
        }
        if (cursor != null) {
            // Reached the cap without finding the allowed ancestor (or a
            // null root). Either the folder hierarchy is deeper than the
            // configured cap or the chain is broken. Either way: treat as
            // not-in-scope (fail-closed) and surface a WARN so the operator
            // can tune the property if a legitimate deep tree is involved.
            logger.warn("Ancestor walk for folder {} in repository {} exceeded the configured cap of {} hops "
                    + "without resolving — treating as not-in-scope. "
                    + "Increase {} in nemakiware.properties if your hierarchy is legitimately deeper.",
                    targetFolderId, repositoryId, cap, MAX_HOPS_PROPERTY);
        }
        return false;
    }

    /**
     * Effective {@code cmis:all} check: union the user's principals with the
     * repository's "Anyone" principal, then check whether any matching ACE
     * carries {@code cmis:all}. Inheritance is honoured because we use
     * {@link Acl#getAllAces()}, which merges local + inherited ACEs in
     * NemakiWare's ACL model.
     */
    boolean hasEffectiveCmisAll(CallContext callContext, String repositoryId, Folder folder) {
        Acl acl;
        try {
            acl = contentService.calculateAcl(repositoryId, folder);
        } catch (RuntimeException e) {
            logger.debug("calculateAcl failed for folder {}: {}", folder.getId(), e.getMessage());
            return false;
        }
        if (acl == null) return false;

        Set<String> principals = expandPrincipals(repositoryId, callContext.getUsername());
        // The repository's Anyone principal name is configurable per repo;
        // PrincipalService exposes it. Falls back to the CMIS canonical
        // "anyone" only if the repo doesn't have one configured.
        String anyoneId = null;
        try {
            anyoneId = principalService.getAnyone(repositoryId);
        } catch (RuntimeException e) {
            logger.debug("PrincipalService.getAnyone({}) failed; will not match Anyone ACEs: {}", repositoryId, e.getMessage());
        }
        if (anyoneId != null && !anyoneId.isBlank()) principals.add(anyoneId);

        List<Ace> aces = acl.getAllAces();
        if (aces == null || aces.isEmpty()) return false;
        for (Ace ace : aces) {
            if (ace == null || ace.getPrincipalId() == null) continue;
            if (!principals.contains(ace.getPrincipalId())) continue;
            List<String> perms = ace.getPermissions();
            if (perms != null && perms.contains(CmisPermission.ALL)) return true;
        }
        return false;
    }

    /**
     * {@code username} ∪ groups containing the user. Fail-closed on group
     * lookup errors: returns just the username, which means the user can
     * still match direct user ACEs but not group ACEs. We intentionally do
     * NOT include "Anyone" here — that's handled separately so the caller
     * can decide whether Anyone matters in the surrounding context.
     */
    Set<String> expandPrincipals(String repositoryId, String username) {
        Set<String> principals = new HashSet<>();
        if (username == null || username.isBlank()) return principals;
        principals.add(username);
        try {
            Set<String> groups = principalService.getGroupIdsContainingUser(repositoryId, username);
            if (groups != null) principals.addAll(groups);
        } catch (RuntimeException e) {
            logger.debug("Group expansion failed for user {} (fail-closed to direct user ACEs only): {}",
                    username, e.getMessage());
        }
        return principals;
    }
}
