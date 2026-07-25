package jp.aegif.nemaki.rag.acl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.rag.util.SolrQuerySanitizer;

/**
 * ACL Expander for RAG indexing.
 *
 * Expands ACL to a list of readers (principals who have read permission).
 * This is used for ACL pre-expansion in Solr to enable efficient permission filtering
 * during vector search.
 *
 * Format of readers (repository-scoped since 3.2.1 so a same-named principal
 * in another repository is a distinct token):
 * - "user:{repositoryId}:{username}" for individual users
 * - "group:{repositoryId}:{groupname}" for groups
 * - "anyone:{repositoryId}" for public access within the repository
 * - "admin:{repositoryId}" — a ROLE token stamped as the null/empty-ACL
 *   fail-closed fallback; only a CURRENT admin is granted this token at query
 *   time, so demoting an admin revokes access immediately without re-indexing
 *   (the same revocation-safety property the group token has).
 *
 * <p>NOTE: this changes the indexed token format. After upgrading, the RAG
 * index must be rebuilt — old unscoped tokens ("user:alice") will not match the
 * new scoped query tokens ("user:bedroom:alice"), which is fail-closed (a
 * stale-format document simply stops appearing in RAG search until re-indexed;
 * it never leaks across repositories).
 *
 * The expansion logic:
 * 1. Get all ACEs (inherited and local) from the document
 * 2. Filter to those with read permission
 * 3. Emit ONLY the principal token that the ACE names (user/group/anyone) — do
 *    NOT expand a group's current members into user tokens. Group (and admin)
 *    membership is resolved at QUERY time instead, which makes revocation take
 *    effect immediately without re-indexing (see {@link #addReaderFromPrincipal}
 *    and {@link #buildReaderTokenSet}).
 * 4. Return the formatted list of readers
 */
@Component
public class ACLExpander {

    private static final Log log = LogFactory.getLog(ACLExpander.class);

    // CMIS read permission
    private static final String PERMISSION_READ = "cmis:read";
    private static final String PERMISSION_ALL = "cmis:all";

    // Special principal IDs
    private static final String PRINCIPAL_ANYONE = "cmis:anyone";
    private static final String PRINCIPAL_ANONYMOUS = "cmis:anonymous";

    // Reader format prefixes
    public static final String PREFIX_USER = "user:";
    public static final String PREFIX_GROUP = "group:";
    public static final String READER_ANYONE = "anyone";
    // Role token for the null/empty-ACL admin-only fallback. Resolved at query
    // time to the CURRENT admins only (revocation-safe on admin demotion).
    public static final String READER_ADMIN = "admin";

    private final PrincipalService principalService;
    private final ContentService contentService;

    @Autowired
    public ACLExpander(PrincipalService principalService, ContentService contentService) {
        this.principalService = principalService;
        this.contentService = contentService;
    }

    /**
     * Expand content ACL to a list of readers.
     *
     * Uses ContentService.calculateAcl() to properly include inherited ACLs from parent folders.
     *
     * @param repositoryId Repository ID
     * @param content Content object with ACL
     * @return List of readers in format: "user:xxx", "group:xxx", or "anyone"
     */
    public List<String> expandToReaders(String repositoryId, Content content) {
        return expandToReaders(repositoryId, content, false);
    }

    /**
     * As {@link #expandToReaders(String, Content)} but with {@code strict} mode for the
     * search-index ACL reconciliation re-drive: the inherited-ACL walk THROWS on an
     * unreadable parent (rather than degrading to local ACEs only), so a transient
     * ancestor-read failure makes the reconcile retry instead of persisting under-visible
     * readers and completing the task. NOTE: a null/deleted PRINCIPAL is still treated as
     * a legitimate omission — distinguishing a transient principal-lookup failure from a
     * genuinely-deleted principal needs tri-state on the principal DAO (a separate, larger
     * change, tracked in CLAUDE.md as the remaining strict-ACL residual).
     */
    public List<String> expandToReaders(String repositoryId, Content content, boolean strict) {
        Set<String> readers = new HashSet<>();

        // Use calculateAcl to get both local and inherited ACLs. Non-strict callers use
        // the original 2-arg method (behaviour + mockability unchanged); only the strict
        // reconcile path uses the 3-arg overload that throws on an unreadable inherited
        // parent instead of silently dropping inherited grants.
        Acl acl = strict
                ? contentService.calculateAcl(repositoryId, content, true)
                : contentService.calculateAcl(repositoryId, content);
        if (acl == null) {
            // No ACL = default to admin only for security
            log.warn(String.format("No ACL calculated for content %s, defaulting to admin-only access", content.getId()));
            return getAdminOnlyReaders(repositoryId);
        }

        // Project the effective ACEs to reader tokens through the SHARED semantics (design §5.3):
        // the read filter, the fail-closed admin-only fallback and the USER-then-GROUP principal
        // resolution all live in AclSemantics so the ACL-epoch side cannot re-implement them
        // differently. Behaviour is unchanged.
        List<String> tokens = AclSemantics.readerTokens(repositoryId, acl.getAllAces(),
                new AclSemantics.PrincipalResolver() {
                    @Override public boolean isUser(String repo, String principalId) {
                        return principalService.getUserById(repo, principalId) != null;
                    }
                    @Override public boolean isGroup(String repo, String principalId) {
                        return principalService.getGroupById(repo, principalId) != null;
                    }
                });
        if (log.isDebugEnabled()) {
            log.debug(String.format("Expanded ACL for %s to %d readers: %s",
                    content.getId(), tokens.size(), tokens));
        }
        return tokens;
    }

    /**
     * Get admin-only readers list for fallback security.
     *
     * <p>Stamps the single {@code admin:{repo}} ROLE token rather than expanding
     * the current admins into individual {@code user:} tokens. Expanding admins
     * had the same revocation hole as expanding group members: a demoted admin
     * kept a stale {@code user:} token on every fallback document until it was
     * re-indexed, so the CMIS numFound stayed inflated and the RAG seed/similar
     * paths stayed matchable until reindex. The role token is resolved at query
     * time to the CURRENT admins only (see {@link #buildReaderTokenSet}), so
     * demotion takes effect immediately without re-indexing.
     */
    private List<String> getAdminOnlyReaders(String repositoryId) {
        List<String> readers = new ArrayList<>();
        readers.add(formatAdminReader(repositoryId));
        return readers;
    }

    /**
     * Check if an ACE grants read permission.
     */
    private boolean hasReadPermission(Ace ace) {
        List<String> permissions = ace.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }

        for (String permission : permissions) {
            if (PERMISSION_READ.equalsIgnoreCase(permission) ||
                    PERMISSION_ALL.equalsIgnoreCase(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add reader from a principal ID.
     * Handles users, groups, and special principals.
     */
    private void addReaderFromPrincipal(String repositoryId, String principalId, Set<String> readers) {
        if (principalId == null || principalId.isEmpty()) {
            return;
        }

        // Handle special principals
        if (PRINCIPAL_ANYONE.equalsIgnoreCase(principalId) ||
                PRINCIPAL_ANONYMOUS.equalsIgnoreCase(principalId)) {
            readers.add(formatAnyoneReader(repositoryId));
            return;
        }

        // Check if it's a user
        User user = principalService.getUserById(repositoryId, principalId);
        if (user != null) {
            readers.add(formatUserReader(repositoryId, principalId));
            return;
        }

        // Check if it's a group. Index ONLY the group token that the ACL names —
        // do NOT expand the group's current members into user tokens.
        //
        // Expanding members at index time was a revocation hole: a removed member
        // (or a removed nested-subgroup member, or a demoted admin) kept a stale
        // `user:{repo}:{id}` token on every document until it was re-indexed, so
        // the query — which always includes the caller's own user token —
        // continued to match. On the CMIS path getFiltered removed the result but
        // the stale tokens inflated numFound and could trip the ACL scan cap; on
        // the RAG path this surfaced document names / paths / chunk text (the RAG
        // result stage is authorized by PermissionService, but the numFound / seed
        // exposure remained). Instead, index only `group:{repo}:{id}` and let the
        // QUERY resolve the caller's groups at request time
        // (getGroupIdsContainingUser is transitive over nested groups on both the
        // CMIS and RAG paths), which is inherently revocation-safe.
        Group group = principalService.getGroupById(repositoryId, principalId);
        if (group != null) {
            readers.add(formatGroupReader(repositoryId, principalId));
        }
    }

    /**
     * Format a user ID as a repository-scoped reader token
     * ({@code user:{repositoryId}:{userId}}).
     */
    public static String formatUserReader(String repositoryId, String userId) {
        return PREFIX_USER + repositoryId + ":" + userId;
    }

    /**
     * Format a group ID as a repository-scoped reader token
     * ({@code group:{repositoryId}:{groupId}}).
     */
    public static String formatGroupReader(String repositoryId, String groupId) {
        return PREFIX_GROUP + repositoryId + ":" + groupId;
    }

    /**
     * The repository-scoped public-access reader token
     * ({@code anyone:{repositoryId}}).
     */
    public static String formatAnyoneReader(String repositoryId) {
        return READER_ANYONE + ":" + repositoryId;
    }

    /**
     * The repository-scoped admin role token ({@code admin:{repositoryId}}),
     * stamped as the null/empty-ACL fallback and matched at query time only by
     * a current admin.
     */
    public static String formatAdminReader(String repositoryId) {
        return READER_ADMIN + ":" + repositoryId;
    }

    /**
     * True if {@code userId} is a CURRENT admin of the repository. Evaluated live
     * (from the principal store) so admin demotion is reflected immediately.
     */
    private boolean isAdminUser(String repositoryId, String userId) {
        if (userId == null) {
            return false;
        }
        try {
            User u = principalService.getUserById(repositoryId, userId);
            return u != null && Boolean.TRUE.equals(u.isAdmin());
        } catch (Exception e) {
            // Fail-closed: an admin-lookup failure is treated as non-admin.
            return false;
        }
    }

    /**
     * Build the caller's live reader-token set: {@code anyone:{repo}}, the
     * caller's own {@code user:} token, one {@code group:} token per group that
     * currently contains the caller (transitive over nested groups), and
     * {@code admin:{repo}} iff the caller is currently an admin. All membership is
     * resolved live here (not from the index), which is what makes revocation —
     * group departure, admin demotion — take effect without re-indexing.
     *
     * <p>Returned tokens are RAW (unsanitized), intended for in-memory
     * {@code equals} comparison against a document's stored readers (see
     * {@link #isReadableByTokens}); the Solr-query form is built separately by
     * {@link #buildReaderFilterQuery}, which escapes the user/group parts.
     */
    public Set<String> buildReaderTokenSet(String repositoryId, String userId) {
        Set<String> tokens = new HashSet<>();
        tokens.add(formatAnyoneReader(repositoryId));
        if (userId != null) {
            tokens.add(formatUserReader(repositoryId, userId));
            Set<String> groupIds = principalService.getGroupIdsContainingUser(repositoryId, userId);
            if (groupIds != null) {
                for (String groupId : groupIds) {
                    tokens.add(formatGroupReader(repositoryId, groupId));
                }
            }
            if (isAdminUser(repositoryId, userId)) {
                tokens.add(formatAdminReader(repositoryId));
            }
        }
        return tokens;
    }

    /**
     * Final live-ACL check for a RAG search hit: load the content and test
     * whether its CURRENT readers ({@link #expandToReaders}, computed live from
     * calculateAcl) intersect the caller's live token set. This is the RAG analog
     * of the CMIS in-memory {@code getFiltered} gate — it re-verifies every hit
     * against live ACL so that a stale, over-permissive {@code readers} value in
     * the Solr index (e.g. during the async re-index window after a move /
     * applyAcl, or a relationship whose endpoint ACL changed) can never leak a
     * document name / path / chunk. Fail-closed: a missing (deleted) content or
     * any lookup error drops the hit.
     */
    public boolean isReadableByTokens(String repositoryId, String documentId, Set<String> callerTokens) {
        if (documentId == null || callerTokens == null || callerTokens.isEmpty()) {
            return false;
        }
        try {
            Content content = contentService.getContent(repositoryId, documentId);
            if (content == null) {
                return false;
            }
            List<String> live = expandToReaders(repositoryId, content);
            return live != null && !Collections.disjoint(live, callerTokens);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build a Solr filter query for the given user.
     * Includes the user, their groups, and "anyone".
     *
     * SECURITY: All user-provided values (userId, groupId) are sanitized to prevent
     * Solr query injection attacks. Values are escaped and quoted to handle special
     * characters safely.
     *
     * @param repositoryId Repository ID
     * @param userId User ID
     * @return Solr filter query string
     */
    public String buildReaderFilterQuery(String repositoryId, String userId) {
        StringBuilder query = new StringBuilder();
        query.append("readers:(");

        // All tokens are repository-scoped since 3.2.1, so every token now
        // contains a colon and must be quoted (an unquoted colon would be parsed
        // as a nested field:value). The repositoryId is a validated repository
        // name (repositories.yml) with no quote/backslash, and the index stores
        // it verbatim, so it is embedded raw to match the stored token exactly;
        // only the user/group parts (which may come from an external IdP) are
        // escaped to prevent Solr query injection.

        // Public-access token, repository-scoped: anyone:{repo}
        query.append("\"").append(READER_ANYONE).append(":").append(repositoryId).append("\"");

        // User token, repository-scoped: user:{repo}:{userId}
        String sanitizedUserId = SolrQuerySanitizer.escape(userId);
        query.append(" OR \"").append(PREFIX_USER).append(repositoryId).append(":")
                .append(sanitizedUserId).append("\"");

        // Group tokens, repository-scoped: group:{repo}:{groupId}. Membership is
        // resolved live and transitively over nested groups (cycle-safe: the
        // recursion carries a visited set, so an A->B->A cycle can no longer
        // StackOverflow the reader-filter build for a non-member searcher).
        Set<String> groupIds = principalService.getGroupIdsContainingUser(repositoryId, userId);
        if (groupIds != null) {
            for (String groupId : groupIds) {
                String sanitizedGroupId = SolrQuerySanitizer.escape(groupId);
                query.append(" OR \"").append(PREFIX_GROUP).append(repositoryId).append(":")
                        .append(sanitizedGroupId).append("\"");
            }
        }

        // Admin role token, repository-scoped: admin:{repo}. Only a CURRENT admin
        // is granted it, so it matches the null/empty-ACL fallback documents; a
        // demoted admin stops matching immediately (no re-index needed). The
        // repositoryId is embedded raw (validated repo name), matching the stored
        // token exactly.
        if (isAdminUser(repositoryId, userId)) {
            query.append(" OR \"").append(READER_ADMIN).append(":").append(repositoryId).append("\"");
        }

        query.append(")");
        return query.toString();
    }
}
