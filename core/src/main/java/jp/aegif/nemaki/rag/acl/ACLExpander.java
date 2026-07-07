package jp.aegif.nemaki.rag.acl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
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
 * 3. Expand group membership to include transitive memberships
 * 4. Return formatted list of readers
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
        Set<String> readers = new HashSet<>();

        // Use calculateAcl to get both local and inherited ACLs
        Acl acl = contentService.calculateAcl(repositoryId, content);
        if (acl == null) {
            // No ACL = default to admin only for security
            log.warn(String.format("No ACL calculated for content %s, defaulting to admin-only access", content.getId()));
            return getAdminOnlyReaders(repositoryId);
        }

        // Get all ACEs (merged inherited and local)
        List<Ace> allAces = acl.getAllAces();
        if (allAces == null || allAces.isEmpty()) {
            // No ACEs = default to admin only for security
            log.warn(String.format("Empty ACL for content %s, defaulting to admin-only access", content.getId()));
            return getAdminOnlyReaders(repositoryId);
        }

        // Process each ACE
        for (Ace ace : allAces) {
            if (hasReadPermission(ace)) {
                String principalId = ace.getPrincipalId();
                addReaderFromPrincipal(repositoryId, principalId, readers);
            }
        }

        // If no readers found, default to admin only for security
        if (readers.isEmpty()) {
            return getAdminOnlyReaders(repositoryId);
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Expanded ACL for %s to %d readers: %s",
                    content.getId(), readers.size(), readers));
        }

        return new ArrayList<>(readers);
    }

    /**
     * Get admin-only readers list for fallback security.
     */
    private List<String> getAdminOnlyReaders(String repositoryId) {
        Set<String> readers = new HashSet<>();
        List<User> admins = principalService.getAdmins(repositoryId);
        if (admins != null) {
            for (User admin : admins) {
                readers.add(formatUserReader(repositoryId, admin.getUserId()));
            }
        }
        // Always include at least the system admin
        if (readers.isEmpty()) {
            readers.add(formatUserReader(repositoryId, "admin"));
        }
        return new ArrayList<>(readers);
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

        // Check if it's a group
        Group group = principalService.getGroupById(repositoryId, principalId);
        if (group != null) {
            readers.add(formatGroupReader(repositoryId, principalId));

            // Expand group members
            expandGroupMembers(repositoryId, group, readers);
        }
    }

    /**
     * Recursively expand group members.
     * Includes all users in the group and nested subgroups.
     *
     * Uses a visited set to prevent infinite recursion from circular group membership.
     */
    private void expandGroupMembers(String repositoryId, Group group, Set<String> readers) {
        expandGroupMembersInternal(repositoryId, group, readers, new HashSet<>());
    }

    /**
     * Internal recursive method with visited tracking to prevent infinite loops.
     *
     * @param repositoryId Repository ID
     * @param group Group to expand
     * @param readers Set of readers being built
     * @param visitedGroups Set of group IDs already visited (prevents cycles)
     */
    private void expandGroupMembersInternal(String repositoryId, Group group,
                                             Set<String> readers, Set<String> visitedGroups) {
        if (group == null) {
            return;
        }

        String groupId = group.getGroupId();
        if (groupId == null) {
            return;
        }

        // Check for circular reference
        if (visitedGroups.contains(groupId)) {
            if (log.isDebugEnabled()) {
                log.debug("Circular group membership detected, skipping: " + groupId);
            }
            return;
        }
        visitedGroups.add(groupId);

        List<String> memberIds = group.getUsers();
        if (memberIds != null) {
            for (String memberId : memberIds) {
                // Check if member is a user
                User user = principalService.getUserById(repositoryId, memberId);
                if (user != null) {
                    readers.add(formatUserReader(repositoryId, memberId));
                } else {
                    // Check if member is a subgroup
                    Group subgroup = principalService.getGroupById(repositoryId, memberId);
                    if (subgroup != null && !readers.contains(formatGroupReader(repositoryId, memberId))) {
                        readers.add(formatGroupReader(repositoryId, memberId));
                        // Recursively expand subgroup with visited tracking
                        expandGroupMembersInternal(repositoryId, subgroup, readers, visitedGroups);
                    }
                }
            }
        }

        // Nested subgroups live in the dedicated groups list (nemaki:groups), not in
        // the users list. Without this traversal, members of a nested subgroup were
        // missing from readers when only the parent group was granted read access.
        List<String> nestedGroupIds = group.getGroups();
        if (nestedGroupIds != null) {
            for (String nestedGroupId : nestedGroupIds) {
                Group subgroup = principalService.getGroupById(repositoryId, nestedGroupId);
                if (subgroup != null) {
                    readers.add(formatGroupReader(repositoryId, nestedGroupId));
                    expandGroupMembersInternal(repositoryId, subgroup, readers, visitedGroups);
                }
            }
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

        // Group tokens, repository-scoped: group:{repo}:{groupId}
        Set<String> groupIds = principalService.getGroupIdsContainingUser(repositoryId, userId);
        if (groupIds != null) {
            for (String groupId : groupIds) {
                String sanitizedGroupId = SolrQuerySanitizer.escape(groupId);
                query.append(" OR \"").append(PREFIX_GROUP).append(repositoryId).append(":")
                        .append(sanitizedGroupId).append("\"");
            }
        }

        query.append(")");
        return query.toString();
    }
}
