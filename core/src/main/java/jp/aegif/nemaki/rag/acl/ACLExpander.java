package jp.aegif.nemaki.rag.acl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;

/**
 * ACL Expander for RAG indexing.
 *
 * Expands ACL to a list of readers (principals who have read permission).
 * This is used for ACL pre-expansion in Solr to enable efficient permission filtering
 * during vector search.
 *
 * Format of readers:
 * - "user:username" for individual users
 * - "group:groupname" for groups
 * - "anyone" for public access
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

    @Autowired
    public ACLExpander(PrincipalService principalService) {
        this.principalService = principalService;
    }

    /**
     * Expand content ACL to a list of readers.
     *
     * @param repositoryId Repository ID
     * @param content Content object with ACL
     * @return List of readers in format: "user:xxx", "group:xxx", or "anyone"
     */
    public List<String> expandToReaders(String repositoryId, Content content) {
        Set<String> readers = new HashSet<>();

        Acl acl = content.getAcl();
        if (acl == null) {
            // No ACL = public access
            readers.add(READER_ANYONE);
            return new ArrayList<>(readers);
        }

        // Get all ACEs (merged inherited and local)
        List<Ace> allAces = acl.getAllAces();
        if (allAces == null || allAces.isEmpty()) {
            // No ACEs = public access
            readers.add(READER_ANYONE);
            return new ArrayList<>(readers);
        }

        // Process each ACE
        for (Ace ace : allAces) {
            if (hasReadPermission(ace)) {
                String principalId = ace.getPrincipalId();
                addReaderFromPrincipal(repositoryId, principalId, readers);
            }
        }

        // If no readers found, default to admin only
        if (readers.isEmpty()) {
            // Get admin users
            List<User> admins = principalService.getAdmins(repositoryId);
            if (admins != null) {
                for (User admin : admins) {
                    readers.add(PREFIX_USER + admin.getUserId());
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Expanded ACL for %s to %d readers: %s",
                    content.getId(), readers.size(), readers));
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
            readers.add(READER_ANYONE);
            return;
        }

        // Check if it's a user
        User user = principalService.getUserById(repositoryId, principalId);
        if (user != null) {
            readers.add(PREFIX_USER + principalId);
            return;
        }

        // Check if it's a group
        Group group = principalService.getGroupById(repositoryId, principalId);
        if (group != null) {
            readers.add(PREFIX_GROUP + principalId);

            // Expand group members
            expandGroupMembers(repositoryId, group, readers);
        }
    }

    /**
     * Recursively expand group members.
     * Includes all users in the group and nested subgroups.
     */
    private void expandGroupMembers(String repositoryId, Group group, Set<String> readers) {
        if (group == null) {
            return;
        }

        List<String> memberIds = group.getUsers();
        if (memberIds != null) {
            for (String memberId : memberIds) {
                // Check if member is a user
                User user = principalService.getUserById(repositoryId, memberId);
                if (user != null) {
                    readers.add(PREFIX_USER + memberId);
                } else {
                    // Check if member is a subgroup
                    Group subgroup = principalService.getGroupById(repositoryId, memberId);
                    if (subgroup != null && !readers.contains(PREFIX_GROUP + memberId)) {
                        readers.add(PREFIX_GROUP + memberId);
                        // Recursively expand subgroup
                        expandGroupMembers(repositoryId, subgroup, readers);
                    }
                }
            }
        }
    }

    /**
     * Format a user ID as a reader.
     */
    public static String formatUserReader(String userId) {
        return PREFIX_USER + userId;
    }

    /**
     * Format a group ID as a reader.
     */
    public static String formatGroupReader(String groupId) {
        return PREFIX_GROUP + groupId;
    }

    /**
     * Build a Solr filter query for the given user.
     * Includes the user, their groups, and "anyone".
     *
     * Values containing colons are quoted to prevent Solr from interpreting
     * them as field:value queries.
     *
     * @param repositoryId Repository ID
     * @param userId User ID
     * @return Solr filter query string
     */
    public String buildReaderFilterQuery(String repositoryId, String userId) {
        StringBuilder query = new StringBuilder();
        query.append("readers:(");

        // Always include "anyone" (no colon, doesn't need quoting)
        query.append(READER_ANYONE);

        // Include user (quoted because it contains colon)
        query.append(" OR \"").append(PREFIX_USER).append(userId).append("\"");

        // Include user's groups (quoted because they contain colons)
        Set<String> groupIds = principalService.getGroupIdsContainingUser(repositoryId, userId);
        if (groupIds != null) {
            for (String groupId : groupIds) {
                query.append(" OR \"").append(PREFIX_GROUP).append(groupId).append("\"");
            }
        }

        query.append(")");
        return query.toString();
    }
}
