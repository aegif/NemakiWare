/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Directory Sync feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.sync.service;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.naming.NamingException;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.sync.connector.LdapDirectoryConnector;
import jp.aegif.nemaki.sync.connector.LdapMemberResolver;
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.model.DirectorySyncResult.SyncStatus;
import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;
import jp.aegif.nemaki.sync.util.PasswordEncryptionUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import jp.aegif.nemaki.model.Property;

public class DirectorySyncServiceImpl implements DirectorySyncService {

    private static final Log log = LogFactory.getLog(DirectorySyncServiceImpl.class);

    private ContentService contentService;
    private PropertyManager propertyManager;
    
    private final Map<String, DirectorySyncResult> lastSyncResults = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> repositoryLocks = new ConcurrentHashMap<>();

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long SYNC_LOCK_TIMEOUT_SECONDS = 300; // 5 minutes
    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
    private static final int GENERATED_PASSWORD_LENGTH = 32;

    private ReentrantLock getRepositoryLock(String repositoryId) {
        return repositoryLocks.computeIfAbsent(repositoryId, k -> new ReentrantLock());
    }

    @Override
    public DirectorySyncResult syncGroups(String repositoryId, boolean dryRun) {
        ReentrantLock lock = getRepositoryLock(repositoryId);
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(SYNC_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!lockAcquired) {
                DirectorySyncResult result = new DirectorySyncResult(repositoryId, dryRun);
                result.addError(null, "Another sync operation is already in progress for this repository. " +
                        "Please wait for it to complete or try again later. Timeout: " + SYNC_LOCK_TIMEOUT_SECONDS + " seconds.");
                result.complete(SyncStatus.FAILED);
                return result;
            }

            DirectorySyncResult result = new DirectorySyncResult(repositoryId, dryRun);
            
            try {
                DirectorySyncConfig config = getConfig(repositoryId);
                
                List<String> validationErrors = validateConfig(config);
                if (!validationErrors.isEmpty()) {
                    for (String error : validationErrors) {
                        result.addError(null, error);
                    }
                    result.complete(SyncStatus.FAILED);
                    return result;
                }
                
                if (!config.isEnabled()) {
                    result.addError(null, "Directory sync is not enabled for this repository");
                    result.complete(SyncStatus.FAILED);
                    return result;
                }

                log.info("AUDIT: Starting directory sync for repository: " + repositoryId + " (dryRun=" + dryRun + ")");

                LdapDirectoryConnector connector = createConnector(config);
                
                try {
                    connector.connect();
                    
                    List<LdapUser> ldapUsers = connector.searchUsers();
                    Map<String, UserState> userStates = performUserSync(repositoryId, ldapUsers, config, result, dryRun);
                    
                    List<LdapGroup> ldapGroups = connector.searchGroups();
                    // Members are looked up among the entries this sync read — their ids are the
                    // id attribute's values on those entries, not the DN's first RDN (R148).
                    LdapMemberResolver.resolve(ldapUsers, ldapGroups);
                    performGroupSync(repositoryId, ldapGroups, config, result, dryRun, userStates);
                    
                    if (result.getErrors().isEmpty()) {
                        result.complete(SyncStatus.SUCCESS);
                    } else {
                        result.complete(SyncStatus.PARTIAL);
                    }
                    
                } finally {
                    connector.disconnect();
                }

                log.info("Directory sync completed:" +
                        "users added=" + result.getUsersAdded() + 
                        ", users updated=" + result.getUsersUpdated() +
                        ", users removed=" + result.getUsersRemoved() +
                        ", groups created=" + result.getGroupsCreated() + 
                        ", groups updated=" + result.getGroupsUpdated() + 
                        ", groups deleted=" + result.getGroupsDeleted() +
                        ", groups skipped=" + result.getGroupsSkipped());

            } catch (NamingException e) {
                log.error("LDAP error during sync: " + e.getMessage(), e);
                result.addError(null, "LDAP error: " + e.getMessage());
                result.complete(SyncStatus.FAILED);
            } catch (Exception e) {
                log.error("Unexpected error during sync: " + e.getMessage(), e);
                result.addError(null, "Unexpected error: " + e.getMessage());
                result.complete(SyncStatus.FAILED);
            }

            lastSyncResults.put(repositoryId, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DirectorySyncResult result = new DirectorySyncResult(repositoryId, dryRun);
            result.addError(null, "Sync operation was interrupted while waiting for lock");
            result.complete(SyncStatus.FAILED);
            return result;
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    /**
     * The connector the sync reads the directory through. A seam, so that the sync's entry point
     * can be measured against a stand-in directory.
     */
    LdapDirectoryConnector createConnector(DirectorySyncConfig config) {
        return new LdapDirectoryConnector(config);
    }

    private List<String> validateConfig(DirectorySyncConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.getLdapUrl() == null || config.getLdapUrl().trim().isEmpty()) {
            errors.add("LDAP URL is required");
        }
        if (config.getLdapBaseDn() == null || config.getLdapBaseDn().trim().isEmpty()) {
            errors.add("LDAP Base DN is required");
        }
        if (config.getLdapBindDn() == null || config.getLdapBindDn().trim().isEmpty()) {
            errors.add("LDAP Bind DN is required");
        }
        if (config.getLdapBindPassword() == null || config.getLdapBindPassword().trim().isEmpty()) {
            errors.add("LDAP Bind Password is required");
        }
        
        return errors;
    }

    /**
     * Whether a directory user is a NemakiWare user after the user sync: it exists, or this sync
     * created it (would create it, in a preview); it does not; or its sync failed, so it is not
     * known. Group memberships are written only for users that exist (R148).
     */
    enum UserState { EXISTS, ABSENT, UNKNOWN }

    private Map<String, UserState> performUserSync(String repositoryId, List<LdapUser> ldapUsers,
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun) {
        Map<String, UserState> states = new HashMap<>();
        
        log.info("Syncing " + ldapUsers.size() + " users from LDAP");

        String configPrefix = config.getUserPrefix();
        final String userPrefix = (configPrefix == null || configPrefix.isEmpty()) ? "" : configPrefix;

        Set<String> ldapUserIds = ldapUsers.stream()
                .map(u -> userPrefix + u.getUserId())
                .collect(Collectors.toSet());

        Map<String, LdapUser> ldapUserMap = new HashMap<>();
        for (LdapUser user : ldapUsers) {
            ldapUserMap.put(userPrefix + user.getUserId(), user);
        }

        for (LdapUser ldapUser : ldapUsers) {
            String nemakiUserId = userPrefix + ldapUser.getUserId();
            states.put(nemakiUserId, UserState.UNKNOWN);
            
            try {
                UserItem existingUser = contentService.getUserItemById(repositoryId, nemakiUserId);
                
                if (existingUser == null) {
                    if (config.isCreateMissingUsers()) {
                        if (!dryRun) {
                            createUser(repositoryId, ldapUser, userPrefix, config);
                            log.info("AUDIT: User created via directory sync: " + nemakiUserId);
                        }
                        states.put(nemakiUserId, UserState.EXISTS);
                        result.incrementUsersAdded();
                        log.debug("User created: " + nemakiUserId);
                    } else {
                        states.put(nemakiUserId, UserState.ABSENT);
                        result.incrementUsersSkipped();
                        log.debug("User skipped (createMissingUsers=false): " + nemakiUserId);
                    }
                } else {
                    states.put(nemakiUserId, UserState.EXISTS);
                    if (config.isUpdateExistingUsers() && hasUserChanges(existingUser, ldapUser)) {
                        if (!dryRun) {
                            updateUser(repositoryId, existingUser, ldapUser);
                        }
                        result.incrementUsersUpdated();
                        log.debug("User updated: " + nemakiUserId);
                    } else {
                        result.incrementUsersSkipped();
                        log.debug("User unchanged or update disabled: " + nemakiUserId);
                    }
                }
            } catch (Exception e) {
                log.error("Error syncing user " + nemakiUserId + ": " + e.getMessage());
                result.addWarning(nemakiUserId, "User sync failed: " + e.getMessage());
            }
        }

        if (config.isDeleteOrphanUsers() && !userPrefix.isEmpty()) {
            List<UserItem> existingUsers = contentService.getUserItems(repositoryId);
            if (existingUsers == null) {
                existingUsers = new ArrayList<>();
            }
            for (UserItem existingUser : existingUsers) {
                String userId = existingUser.getUserId();
                if (userId != null && userId.startsWith(userPrefix) && !ldapUserIds.contains(userId)) {
                    try {
                        if (!dryRun) {
                            // The canonical delete, not a private copy of it: deleteUser
                            // strips the memberships through the reverse-lookup view, which
                            // refuses rather than shortening, and re-fetches each parent
                            // FRESH before rewriting it. The copy below scanned every group
                            // instead and skipped what it could not read.
                            contentService.deleteUser(repositoryId, userId);
                            log.info("AUDIT: User deleted via directory sync (orphan): " + userId);
                        }
                        result.incrementUsersRemoved();
                        log.debug("User deleted (orphan): " + userId);
                    } catch (Exception e) {
                        log.error("Error deleting orphan user " + userId + ": " + e.getMessage());
                        result.addWarning(userId, "Delete failed: " + e.getMessage());
                    }
                }
            }
        }
        return states;
    }


    private boolean hasUserChanges(UserItem existingUser, LdapUser ldapUser) {
        String existingName = existingUser.getName();
        String ldapDisplayName = ldapUser.getDisplayName();
        
        if (ldapDisplayName == null || ldapDisplayName.isEmpty()) {
            ldapDisplayName = ldapUser.getUserId();
        }
        
        if (existingName == null && ldapDisplayName != null) {
            return true;
        }
        if (existingName != null && !existingName.equals(ldapDisplayName)) {
            return true;
        }

        // Check firstName, lastName, email changes via subTypeProperties
        List<Property> subTypeProperties = existingUser.getSubTypeProperties();
        if (subTypeProperties == null) {
            subTypeProperties = new ArrayList<>();
        }
        Map<String, String> propMap = new HashMap<>();
        for (Property p : subTypeProperties) {
            propMap.put(p.getKey(), String.valueOf(p.getValue()));
        }

        if (ldapUser.getFirstName() != null && !ldapUser.getFirstName().equals(propMap.get("nemaki:firstName"))) {
            return true;
        }
        if (ldapUser.getLastName() != null && !ldapUser.getLastName().equals(propMap.get("nemaki:lastName"))) {
            return true;
        }
        if (ldapUser.getEmail() != null && !ldapUser.getEmail().equals(propMap.get("nemaki:email"))) {
            return true;
        }
        
        return false;
    }

    private void updateUser(String repositoryId, UserItem existingUser, LdapUser ldapUser) {
        String displayName = ldapUser.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = ldapUser.getUserId();
        }
        
        existingUser.setName(displayName);
        existingUser.setModifier("system");
        existingUser.setModified(new GregorianCalendar());

        // Update firstName, lastName, email from LDAP attributes
        List<Property> subTypeProperties = existingUser.getSubTypeProperties();
        if (subTypeProperties == null) {
            subTypeProperties = new ArrayList<>();
        }
        updateSubTypeProperty(subTypeProperties, "nemaki:firstName", ldapUser.getFirstName());
        updateSubTypeProperty(subTypeProperties, "nemaki:lastName", ldapUser.getLastName());
        updateSubTypeProperty(subTypeProperties, "nemaki:email", ldapUser.getEmail());
        existingUser.setSubTypeProperties(subTypeProperties);
        
        contentService.update(new SystemCallContext(repositoryId), repositoryId, existingUser);
    }

    private void updateSubTypeProperty(List<Property> properties, String key, String value) {
        if (value == null) return;
        for (int i = 0; i < properties.size(); i++) {
            if (key.equals(properties.get(i).getKey())) {
                properties.set(i, new Property(key, value));
                return;
            }
        }
        properties.add(new Property(key, value));
    }

    private void createUser(String repositoryId, LdapUser ldapUser, String userPrefix, DirectorySyncConfig config) {
        Folder usersFolder = getOrCreateUsersFolder(repositoryId);
        if (usersFolder == null) {
            throw new RuntimeException("Failed to get or create users folder");
        }

        // Check for configured initial password
        String initialPassword = config.getInitialPassword();
        String passwordHash;
        String nemakiUserId = userPrefix + ldapUser.getUserId();
        
        if (initialPassword != null && !initialPassword.isEmpty()) {
            // Use configured initial password
            passwordHash = BCrypt.hashpw(initialPassword, BCrypt.gensalt());
            log.info("Created user '" + nemakiUserId + "' with configured initial password. " +
                    "User should change password on first login.");
        } else {
            // Generate random password - user will not be able to log in without password reset
            String randomPassword = generateSecurePassword();
            passwordHash = BCrypt.hashpw(randomPassword, BCrypt.gensalt());
            log.warn("Created user '" + nemakiUserId + "' with random password. " +
                    "User will NOT be able to log in until password is reset by admin. " +
                    "Consider setting 'directory.sync.user.initial.password' property or " +
                    "configuring LDAP authentication pass-through.");
        }

        String displayName = ldapUser.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = ldapUser.getUserId();
        }

        UserItem newUser = new UserItem(
            null,
            NemakiObjectType.nemakiUser,
            nemakiUserId,
            displayName,
            passwordHash,
            false,
            usersFolder.getId()
        );

        // Set firstName, lastName, email from LDAP attributes
        List<Property> subTypeProperties = new ArrayList<>();
        if (ldapUser.getFirstName() != null) subTypeProperties.add(new Property("nemaki:firstName", ldapUser.getFirstName()));
        if (ldapUser.getLastName() != null) subTypeProperties.add(new Property("nemaki:lastName", ldapUser.getLastName()));
        if (ldapUser.getEmail() != null) subTypeProperties.add(new Property("nemaki:email", ldapUser.getEmail()));
        newUser.setSubTypeProperties(subTypeProperties);

        newUser.setCreator("system");
        newUser.setModifier("system");
        newUser.setCreated(new GregorianCalendar());
        newUser.setModified(new GregorianCalendar());

        contentService.createUserItem(
            new SystemCallContext(repositoryId),
            repositoryId,
            newUser
        );
    }


    /**
     * Get the system folder for a repository, with fallback for PropertyManager issues.
     * This method consolidates the system folder lookup logic used by both
     * getOrCreateUsersFolder and getOrCreateGroupsFolder.
     * 
     * @param repositoryId The repository ID
     * @return The system folder
     * @throws RuntimeException if the system folder cannot be found
     */
    private Folder getSystemFolderWithFallback(String repositoryId) {
        Folder systemFolder = contentService.getSystemFolder(repositoryId);

        // Fallback: if PropertyManager fails to read system.folder config, try direct lookup
        if (systemFolder == null) {
            log.warn("System folder not found via PropertyManager for repository: " + repositoryId + 
                    ", attempting fallback lookup");
            try {
                // Try to find .system folder by name from root
                Content rootContent = contentService.getContentByPath(repositoryId, "/");
                if (rootContent != null && rootContent instanceof Folder) {
                    Folder rootFolder = (Folder) rootContent;
                    List<Content> rootChildren = contentService.getChildren(repositoryId, rootFolder.getId());
                    if (rootChildren != null) {
                        for (Content child : rootChildren) {
                            if (".system".equals(child.getName()) && child instanceof Folder) {
                                systemFolder = (Folder) child;
                                log.info("Found .system folder via root folder lookup: ID=" + systemFolder.getId());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to find .system folder via fallback lookup", e);
            }
        }

        if (systemFolder == null) {
            String errorMsg = "System folder (.system) not found for repository: " + repositoryId + 
                    ". Directory sync requires the system folder to exist. " +
                    "Please ensure the repository is properly initialized.";
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        return systemFolder;
    }

    private Folder getOrCreateUsersFolder(String repositoryId) {
        Folder systemFolder = getSystemFolderWithFallback(repositoryId);

        // Check for existing users folder
        List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        // Find-or-CREATE over a short listing creates a duplicate: the existing folder may BE
        // the row that would not decode. Same shape Patch_SystemFolderSetup was corrected for.
        // Throwing fails this sync run; the next one retries against a repaired listing.
        if (contentService.lastUnreadableChildCount() > 0) {
            throw new IllegalStateException("the .system folder's listing is incomplete ("
                    + contentService.lastUnreadableChildCount() + " child row(s) could not be "
                    + "read), so whether a 'users' folder already exists is unknown; refusing "
                    + "to create a possible duplicate");
        }
        if (children != null) {
            for (Content child : children) {
                if ("users".equals(child.getName()) && child instanceof Folder) {
                    return (Folder) child;
                }
            }
        }

        // Create users folder if it doesn't exist
        log.info("Creating 'users' folder under .system folder for repository: " + repositoryId);
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:name", "users"));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
        
        Folder usersFolder = contentService.createFolder(
            new SystemCallContext(repositoryId),
            repositoryId,
            properties,
            systemFolder,
            null, null, null, null
        );
        log.info("Created 'users' folder with ID: " + usersFolder.getId());
        return usersFolder;
    }

    /**
     * Writes the LDAP groups. A membership this sync could not establish is not written, neither as
     * "no members" nor as guessed ids (R148), and no parent is given a nesting through which
     * someone the directory does not name would gain its permissions (R149):
     *
     * <ul>
     * <li>A group is <em>established</em> when this sync wrote it, or found it unchanged, and every
     *     group it nests afterwards is established too: its effective membership is then a subset
     *     of what LDAP names. A refused group, one whose write failed, and one that keeps a nesting
     *     to a group that is not established, are not.
     * <li>Where nested groups are synced, a parent newly nests only an established group. A nesting
     *     it already has to a group NemakiWare has is kept as it was — removing it would take the
     *     parent's permissions from members LDAP still names — and the parent is then not
     *     established. A nesting that names no group in NemakiWare is removed: whoever later takes
     *     the id would gain the parent's permissions.
     * <li>Children are written before their parents, so a parent knows which of its nested groups
     *     are established. Groups LDAP nests in a cycle cannot be stored, and which nesting to drop
     *     is not the sync's to choose: none of them is written.
     * </ul>
     *
     * <p>Established is what this sync wrote: a change made to a group by someone else while the
     * sync runs (another replica's sync, the admin screen, the API) is not seen. Run one sync at a
     * time, and do not change synced groups while it runs.
     */
    private void performGroupSync(String repositoryId, List<LdapGroup> ldapGroups, 
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun,
            Map<String, UserState> userStates) {
        
        String configPrefix = config.getGroupPrefix();
        final String groupPrefix = (configPrefix == null) ? DirectorySyncConfig.DEFAULT_GROUP_PREFIX : configPrefix;
        final String userPrefix = config.getUserPrefix() != null ? config.getUserPrefix() : "";
        final boolean syncNestedGroups = config.isSyncNestedGroups();

        List<GroupItem> existingGroups = contentService.getGroupItems(repositoryId);
        if (existingGroups == null) {
            // Which groups NemakiWare has decides which nestings are kept and which are dropped as
            // naming no group: "no groups" is not what an unanswered list says.
            throw new IllegalStateException("the group list of '" + repositoryId + "' could not be read, "
                    + "so which groups exist is not known; the groups are not synced");
        }
        Set<String> present = existingGroups.stream()
                .map(GroupItem::getGroupId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        
        Map<String, GroupItem> syncedGroupMap = existingGroups.stream()
                .filter(g -> g.getGroupId() != null && g.getGroupId().startsWith(groupPrefix))
                .collect(Collectors.toMap(GroupItem::getGroupId, g -> g));

        Map<String, LdapGroup> ldapGroupMap = new HashMap<>();
        for (LdapGroup lg : ldapGroups) {
            String nemakiGroupId = groupPrefix + lg.getGroupId();
            ldapGroupMap.put(nemakiGroupId, lg);
        }

        // Which groups' memberships are known at all (R148).
        Map<String, String> refusals = new HashMap<>();
        for (LdapGroup ldapGroup : ldapGroups) {
            String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
            String unknown = unknownMembership(ldapGroup, nemakiGroupId, userPrefix, userStates, syncNestedGroups);
            if (unknown != null) {
                refusals.put(nemakiGroupId, unknown);
            }
        }

        List<LdapGroup> order = ldapGroups;
        if (syncNestedGroups) {
            // LDAP's nesting among the groups this sync writes. A group naming itself is no cycle:
            // it adds no one, and its write leaves that member out.
            Map<String, List<String>> nesting = new LinkedHashMap<>();
            Map<String, LdapGroup> byId = new HashMap<>();
            for (LdapGroup ldapGroup : ldapGroups) {
                String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
                if (refusals.containsKey(nemakiGroupId)) {
                    continue;
                }
                List<String> nested = new ArrayList<>();
                for (String groupId : memberGroupIdsOf(ldapGroup)) {
                    nested.add(groupPrefix + groupId);
                }
                nesting.put(nemakiGroupId, nested);
                byId.put(nemakiGroupId, ldapGroup);
            }
            for (List<String> cycle : nestingCycles(nesting)) {
                for (String nemakiGroupId : cycle) {
                    List<String> others = new ArrayList<>(cycle);
                    others.remove(nemakiGroupId);
                    refusals.put(nemakiGroupId, "group " + nemakiGroupId + ": LDAP nests it in a cycle with "
                            + sample(others) + ", which NemakiWare cannot store, and which nesting to drop is not "
                            + "known; the group is not written");
                    nesting.remove(nemakiGroupId);
                }
            }
            // Refused groups first (each only reports), then children before their parents.
            order = new ArrayList<>();
            for (LdapGroup ldapGroup : ldapGroups) {
                if (refusals.containsKey(groupPrefix + ldapGroup.getGroupId())) {
                    order.add(ldapGroup);
                }
            }
            for (String nemakiGroupId : childrenFirst(nesting)) {
                order.add(byId.get(nemakiGroupId));
            }
        }

        Set<String> established = new HashSet<>();
        for (LdapGroup ldapGroup : order) {
            String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
            
            try {
                GroupItem existingGroup = syncedGroupMap.get(nemakiGroupId);
                String unknown = refusals.get(nemakiGroupId);
                if (unknown != null) {
                    log.warn("AUDIT: Group not written by directory sync: " + unknown);
                    result.addError(nemakiGroupId, unknown);
                    continue;
                }
                if (existingGroup == null && (userStates.get(nemakiGroupId) == UserState.EXISTS
                        || contentService.getUserItemById(repositoryId, nemakiGroupId) != null)) {
                    // Creating it is refused (validateGroupItem); said here so that the preview says
                    // it too, and so that no parent is written before the refusal is known.
                    String clash = "group " + nemakiGroupId + ": a NemakiWare user has this id, so the group "
                            + "cannot be created; the group is not written";
                    log.warn("AUDIT: Group not written by directory sync: " + clash);
                    result.addError(nemakiGroupId, clash);
                    continue;
                }
                // Directory users this sync can name but that are not NemakiWare users (it did not
                // create them). Writing them would leave an id for whoever takes it later.
                List<String> users = new ArrayList<>();
                List<String> notUsers = new ArrayList<>();
                for (String userId : ldapGroup.getMemberUserIds()) {
                    if (userStates.get(userPrefix + userId) == UserState.EXISTS) {
                        users.add(userId);
                    } else {
                        notUsers.add(userId);
                    }
                }
                ldapGroup.setMemberUserIds(users);
                if (!notUsers.isEmpty()) {
                    result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + notUsers.size()
                            + " member(s) are directory users that are not NemakiWare users (this sync did not "
                            + "create them) and were not written, for example " + sample(notUsers));
                }
                boolean isEstablished = true;
                if (syncNestedGroups) {
                    Set<String> had = existingGroup == null || existingGroup.getGroups() == null
                            ? Set.of() : new HashSet<>(existingGroup.getGroups());
                    List<String> nested = new ArrayList<>();
                    List<String> kept = new ArrayList<>();
                    List<String> notAdded = new ArrayList<>();
                    List<String> namingNoGroup = new ArrayList<>();
                    boolean namesItself = false;
                    for (String groupId : memberGroupIdsOf(ldapGroup)) {
                        String nestedId = groupPrefix + groupId;
                        if (nestedId.equals(nemakiGroupId)) {
                            namesItself = true;
                        } else if (established.contains(nestedId)) {
                            nested.add(groupId);
                        } else if (had.contains(nestedId) && present.contains(nestedId)) {
                            nested.add(groupId);
                            kept.add(nestedId);
                        } else if (had.contains(nestedId)) {
                            namingNoGroup.add(nestedId);
                        } else {
                            notAdded.add(nestedId);
                        }
                    }
                    ldapGroup.setMemberGroupIds(nested);
                    if (namesItself) {
                        result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": the group names itself as "
                                + "a member, which adds no one; that member is not written");
                    }
                    if (!notAdded.isEmpty()) {
                        result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + notAdded.size()
                                + " nested group(s) were not added, because this sync did not establish their "
                                + "membership (not written, or nesting a group whose membership it did not "
                                + "establish), for example " + sample(notAdded) + "; a later sync adds them once it does");
                    }
                    if (!kept.isEmpty()) {
                        result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + kept.size()
                                + " nested group(s) were kept as they were, though this sync did not establish "
                                + "their membership, for example " + sample(kept) + "; a group that would newly "
                                + "nest this one does not");
                    }
                    if (!namingNoGroup.isEmpty()) {
                        result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + namingNoGroup.size()
                                + " nested group id(s) named no group in NemakiWare and were removed, for example "
                                + sample(namingNoGroup));
                    }
                    isEstablished = kept.isEmpty();
                }
                if (existingGroup != null) {
                    if (hasGroupChanges(existingGroup, ldapGroup, groupPrefix, config)) {
                        if (!dryRun) {
                            updateGroup(repositoryId, existingGroup, ldapGroup, groupPrefix, config);
                        }
                        result.incrementGroupsUpdated();
                        log.debug("Group updated: " + nemakiGroupId);
                    } else {
                        result.incrementGroupsSkipped();
                        log.debug("Group unchanged: " + nemakiGroupId);
                    }
                } else {
                    if (!dryRun) {
                        createGroup(repositoryId, ldapGroup, groupPrefix, config);
                        log.info("AUDIT: Group created via directory sync: " + nemakiGroupId);
                    }
                    result.incrementGroupsCreated();
                    log.debug("Group created: " + nemakiGroupId);
                }
                if (isEstablished) {
                    established.add(nemakiGroupId);
                }
            } catch (Exception e) {
                log.error("Error syncing group " + nemakiGroupId + ": " + e.getMessage());
                result.addError(nemakiGroupId, e.getMessage());
            }
        }

        if (config.isDeleteOrphanGroups()) {
            for (GroupItem existingGroup : syncedGroupMap.values()) {
                if (!ldapGroupMap.containsKey(existingGroup.getGroupId())) {
                    try {
                        if (!dryRun) {
                            // deleteGroup, not a bare delete: the group side had NO nested
                            // reference stripping at all, so an orphan group removed by
                            // directory sync left its id in every parent group's nested
                            // list — and re-creating the same group id later silently
                            // revived the nesting.
                            contentService.deleteGroup(repositoryId, existingGroup.getGroupId());
                            log.info("AUDIT: Group deleted via directory sync (orphan): " + existingGroup.getGroupId());
                        }
                        result.incrementGroupsDeleted();
                        log.debug("Group deleted (orphan): " + existingGroup.getGroupId());
                    } catch (Exception e) {
                        log.error("Error deleting orphan group " + existingGroup.getGroupId() + ": " + e.getMessage());
                        result.addError(existingGroup.getGroupId(), "Delete failed: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static List<String> memberGroupIdsOf(LdapGroup ldapGroup) {
        return ldapGroup.getMemberGroupIds() == null ? List.of() : ldapGroup.getMemberGroupIds();
    }

    /** The groups in post-order — each after the groups it nests — starting from each in turn. */
    private static List<String> childrenFirst(Map<String, List<String>> nesting) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String root : nesting.keySet()) {
            if (!seen.add(root)) {
                continue;
            }
            Deque<Map.Entry<String, Iterator<String>>> path = new ArrayDeque<>();
            path.push(Map.entry(root, nesting.get(root).iterator()));
            while (!path.isEmpty()) {
                Iterator<String> next = path.peek().getValue();
                if (next.hasNext()) {
                    String child = next.next();
                    if (nesting.containsKey(child) && seen.add(child)) {
                        path.push(Map.entry(child, nesting.get(child).iterator()));
                    }
                } else {
                    order.add(path.pop().getKey());
                }
            }
        }
        return order;
    }

    /**
     * The cycles in the nesting: its strongly connected components of two or more groups
     * (Kosaraju's, without recursion — a directory's nesting can be deep).
     */
    private static List<List<String>> nestingCycles(Map<String, List<String>> nesting) {
        List<String> finished = childrenFirst(nesting);
        Map<String, List<String>> parents = new HashMap<>();
        for (Map.Entry<String, List<String>> edges : nesting.entrySet()) {
            for (String child : edges.getValue()) {
                if (nesting.containsKey(child)) {
                    parents.computeIfAbsent(child, k -> new ArrayList<>()).add(edges.getKey());
                }
            }
        }
        List<List<String>> cycles = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (int i = finished.size() - 1; i >= 0; i--) {
            String root = finished.get(i);
            if (!assigned.add(root)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            Deque<String> todo = new ArrayDeque<>();
            todo.push(root);
            while (!todo.isEmpty()) {
                String group = todo.pop();
                component.add(group);
                for (String parent : parents.getOrDefault(group, List.of())) {
                    if (assigned.add(parent)) {
                        todo.push(parent);
                    }
                }
            }
            if (component.size() > 1) {
                cycles.add(component);
            }
        }
        return cycles;
    }

    private boolean hasGroupChanges(GroupItem existingGroup, LdapGroup ldapGroup, String groupPrefix, DirectorySyncConfig config) {
        String userPrefix = config.getUserPrefix() != null ? config.getUserPrefix() : "";
        
        Set<String> existingUsers = new HashSet<>(existingGroup.getUsers() != null ? existingGroup.getUsers() : new ArrayList<>());
        Set<String> ldapUsersWithPrefix = new HashSet<>();
        if (ldapGroup.getMemberUserIds() != null) {
            for (String userId : ldapGroup.getMemberUserIds()) {
                ldapUsersWithPrefix.add(userPrefix + userId);
            }
        }
        
        if (!existingUsers.equals(ldapUsersWithPrefix)) {
            return true;
        }

        if (config.isSyncNestedGroups()) {
            // Compared as stored, the way the users are: the sync writes the nested groups with the
            // prefix. Stripping it took a local group that shares an LDAP group's id for that group,
            // and the nesting was left as it was (R148 (c)).
            Set<String> existingSubGroups = new HashSet<>(existingGroup.getGroups() != null ? existingGroup.getGroups() : new ArrayList<>());
            Set<String> ldapSubGroupsWithPrefix = new HashSet<>();
            if (ldapGroup.getMemberGroupIds() != null) {
                for (String subGroupId : ldapGroup.getMemberGroupIds()) {
                    ldapSubGroupsWithPrefix.add(groupPrefix + subGroupId);
                }
            }

            if (!existingSubGroups.equals(ldapSubGroupsWithPrefix)) {
                return true;
            }
        }

        String existingName = existingGroup.getName();
        String ldapName = ldapGroup.getGroupName();
        if (existingName == null && ldapName != null) {
            return true;
        }
        if (existingName != null && !existingName.equals(ldapName)) {
            return true;
        }

        return false;
    }

    /**
     * Why the group's membership is not known well enough to write, or null. Each case used to be
     * written — as "no members", or as ids guessed from the DN (R148). A member value this sync
     * cannot name could be anyone, so writing the members it can name would drop whoever that is.
     */
    private static String unknownMembership(LdapGroup ldapGroup, String nemakiGroupId, String userPrefix,
            Map<String, UserState> userStates, boolean syncNestedGroups) {
        String group = "group " + nemakiGroupId + ": ";
        if (ldapGroup.isIdShared()) {
            return group + "another entry in the directory has the same group id, so which entry is this "
                    + "group is not known; the group is not written";
        }
        if (ldapGroup.isMembersRanged()) {
            return group + "the directory returned the member attribute in ranges (Active Directory's "
                    + "member;range=...), which this sync does not read, so the membership is not known; "
                    + "the group is not written";
        }
        if (!ldapGroup.isMemberAttributePresent()) {
            return group + "the directory returned no member attribute. An empty group and one whose members "
                    + "the bind account may not read look the same, so the group is not written (create it, or "
                    + "empty it, by hand)";
        }
        List<String> ambiguous = ldapGroup.getAmbiguousMembers() == null ? List.of() : ldapGroup.getAmbiguousMembers();
        List<String> ambiguousGroups = ldapGroup.getAmbiguousGroupMembers() == null ? List.of()
                : ldapGroup.getAmbiguousGroupMembers();
        List<String> unresolved = ldapGroup.getUnresolvedMembers() == null ? List.of() : ldapGroup.getUnresolvedMembers();
        List<String> values = ldapGroup.getMemberDns() == null ? List.of() : ldapGroup.getMemberDns();
        boolean noUsers = ldapGroup.getMemberUserIds() == null || ldapGroup.getMemberUserIds().isEmpty();
        boolean noGroups = ldapGroup.getMemberGroupIds() == null || ldapGroup.getMemberGroupIds().isEmpty();
        if (!values.isEmpty() && noUsers && noGroups && unresolved.isEmpty() && ambiguous.isEmpty()
                && ambiguousGroups.isEmpty()) {
            // Values that came back with none of them accounted for were never looked up: "no
            // members" is not what the directory said.
            return group + "its " + values.size() + " member value(s) were not looked up; the group is not written";
        }
        if (!ambiguous.isEmpty()) {
            return group + ambiguous.size() + " member value(s) could name more than one entry (an entry both "
                    + "searches returned, or an id more than one entry has), for example " + sample(ambiguous)
                    + "; the group is not written";
        }
        if (syncNestedGroups && !ambiguousGroups.isEmpty()) {
            // Where nested groups are not synced these are not written either way, so they refuse
            // nothing (R149 (c)).
            return group + ambiguousGroups.size() + " nested group value(s) name a group entry whose id another "
                    + "group entry also has, for example " + sample(ambiguousGroups) + "; which group is meant is "
                    + "not known, so the group is not written";
        }
        if (!unresolved.isEmpty()) {
            return group + unresolved.size() + " member value(s) are not a user or group this sync read, for "
                    + "example " + sample(unresolved) + ". A member it cannot name could be anyone, so the group "
                    + "is not written (widen the user or group search, or remove the member)";
        }
        List<String> unknownUsers = new ArrayList<>();
        for (String userId : ldapGroup.getMemberUserIds()) {
            UserState state = userStates.get(userPrefix + userId);
            if (state == null || state == UserState.UNKNOWN) {
                unknownUsers.add(userId);
            }
        }
        if (!unknownUsers.isEmpty()) {
            return group + "the sync of member user(s) " + sample(unknownUsers) + " failed, so whether they are "
                    + "NemakiWare users is not known; the group is not written";
        }
        return null;
    }

    private static List<String> sample(List<String> values) {
        return values.subList(0, Math.min(3, values.size()));
    }

    private void createGroup(String repositoryId, LdapGroup ldapGroup, String groupPrefix, DirectorySyncConfig config) {
        String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
        
        String userPrefix = config.getUserPrefix() != null ? config.getUserPrefix() : "";
        List<String> users = new ArrayList<>();
        if (ldapGroup.getMemberUserIds() != null) {
            for (String userId : ldapGroup.getMemberUserIds()) {
                users.add(userPrefix + userId);
            }
        }
        
        List<String> subGroups = new ArrayList<>();
        if (config.isSyncNestedGroups() && ldapGroup.getMemberGroupIds() != null) {
            for (String subGroupId : ldapGroup.getMemberGroupIds()) {
                subGroups.add(groupPrefix + subGroupId);
            }
        }

        Folder groupsFolder = getOrCreateGroupsFolder(repositoryId);
        if (groupsFolder == null) {
            throw new RuntimeException("Failed to get or create groups folder");
        }

        GroupItem newGroup = new GroupItem(
            null,
            NemakiObjectType.nemakiGroup,
            nemakiGroupId,
            ldapGroup.getGroupName(),
            users,
            subGroups
        );
        newGroup.setParentId(groupsFolder.getId());
        newGroup.setCreator("system");
        newGroup.setModifier("system");
        newGroup.setCreated(new GregorianCalendar());
        newGroup.setModified(new GregorianCalendar());

        contentService.createGroupItem(
            new SystemCallContext(repositoryId),
            repositoryId,
            newGroup
        );
    }

    private void updateGroup(String repositoryId, GroupItem existingGroup, LdapGroup ldapGroup, String groupPrefix, DirectorySyncConfig config) {
        String userPrefix = config.getUserPrefix() != null ? config.getUserPrefix() : "";
        List<String> users = new ArrayList<>();
        if (ldapGroup.getMemberUserIds() != null) {
            for (String userId : ldapGroup.getMemberUserIds()) {
                users.add(userPrefix + userId);
            }
        }
        
        List<String> subGroups = new ArrayList<>();
        if (config.isSyncNestedGroups() && ldapGroup.getMemberGroupIds() != null) {
            for (String subGroupId : ldapGroup.getMemberGroupIds()) {
                subGroups.add(groupPrefix + subGroupId);
            }
        }

        existingGroup.setName(ldapGroup.getGroupName());
        existingGroup.setUsers(users);
        // Nested groups are LDAP's to replace only when they are synced — the same condition
        // hasGroupChanges compares them under. Writing the empty list otherwise removed every
        // nested group an administrator had added, and every permission granted through them.
        // Until 3.4.1 this method never reached the store: the groups it was handed came from
        // the group list, which carried no revision, so every update was refused.
        if (config.isSyncNestedGroups()) {
            existingGroup.setGroups(subGroups);
        }
        existingGroup.setModifier("system");
        existingGroup.setModified(new GregorianCalendar());
        
        contentService.update(new SystemCallContext(repositoryId), repositoryId, existingGroup);
    }

    private Folder getOrCreateGroupsFolder(String repositoryId) {
        Folder systemFolder = getSystemFolderWithFallback(repositoryId);

        // Check for existing groups folder
        List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        if (contentService.lastUnreadableChildCount() > 0) {
            throw new IllegalStateException("the .system folder's listing is incomplete ("
                    + contentService.lastUnreadableChildCount() + " child row(s) could not be "
                    + "read), so whether a 'groups' folder already exists is unknown; refusing "
                    + "to create a possible duplicate");
        }
        if (children != null) {
            for (Content child : children) {
                if ("groups".equals(child.getName()) && child instanceof Folder) {
                    return (Folder) child;
                }
            }
        }

        // Create groups folder if it doesn't exist
        log.info("Creating 'groups' folder under .system folder for repository: " + repositoryId);
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:name", "groups"));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
        
        Folder groupsFolder = contentService.createFolder(
            new SystemCallContext(repositoryId),
            repositoryId,
            properties,
            systemFolder,
            null, null, null, null
        );
        log.info("Created 'groups' folder with ID: " + groupsFolder.getId());
        return groupsFolder;
    }

    @Override
    public DirectorySyncResult previewSync(String repositoryId) {
        return syncGroups(repositoryId, true);
    }

    @Override
    public DirectorySyncConfig getConfig(String repositoryId) {
        DirectorySyncConfig config = new DirectorySyncConfig();
        config.setRepositoryId(repositoryId);
        
        config.setEnabled(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_ENABLED));
        
        config.setLdapUrl(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_URL));
        config.setLdapBaseDn(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BASE_DN));
        config.setLdapBindDn(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BIND_DN));
        
        String rawPassword = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BIND_PASSWORD);
        config.setLdapBindPassword(PasswordEncryptionUtil.resolvePassword(rawPassword));
        config.setUseTls(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_LDAP_USE_TLS));
        config.setUseStartTls(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_LDAP_USE_STARTTLS));
        
        String connTimeout = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_CONNECTION_TIMEOUT);
        if (connTimeout != null && !connTimeout.isEmpty()) {
            try {
                config.setConnectionTimeout(Integer.parseInt(connTimeout));
            } catch (NumberFormatException e) {
                log.warn("Invalid connection timeout value: " + connTimeout + ", using default");
            }
        }
        String readTimeout = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_READ_TIMEOUT);
        if (readTimeout != null && !readTimeout.isEmpty()) {
            try {
                config.setReadTimeout(Integer.parseInt(readTimeout));
            } catch (NumberFormatException e) {
                log.warn("Invalid read timeout value: " + readTimeout + ", using default");
            }
        }
        
        config.setGroupSearchBase(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_SEARCH_BASE));
        config.setGroupSearchFilter(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_SEARCH_FILTER));
        config.setUserSearchBase(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_SEARCH_BASE));
        config.setUserSearchFilter(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_SEARCH_FILTER));
        
        config.setGroupIdAttribute(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_ID_ATTRIBUTE));
        config.setGroupNameAttribute(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_NAME_ATTRIBUTE));
        config.setGroupMemberAttribute(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_MEMBER_ATTRIBUTE));
        config.setUserIdAttribute(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_ID_ATTRIBUTE));
        
        config.setSyncNestedGroups(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS));
        config.setCreateMissingUsers(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_CREATE_MISSING_USERS));
        config.setUpdateExistingUsers(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_UPDATE_EXISTING_USERS));
        config.setDeleteOrphanGroups(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS));
        config.setDeleteOrphanUsers(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_USERS));
        
        String groupPrefix = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_GROUP_PREFIX);
        config.setGroupPrefix(groupPrefix != null && !groupPrefix.isEmpty() ? groupPrefix : DirectorySyncConfig.DEFAULT_GROUP_PREFIX);
        
        String userPrefix = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX);
        config.setUserPrefix(userPrefix != null ? userPrefix : DirectorySyncConfig.DEFAULT_USER_PREFIX);

        // Initial password for newly created users (optional)
        config.setInitialPassword(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_INITIAL_PASSWORD));

        config.setScheduleEnabled(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_SCHEDULE_ENABLED));
        config.setCronExpression(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_CRON));
        
        DirectorySyncResult lastResult = lastSyncResults.get(repositoryId);
        if (lastResult != null) {
            config.setLastSyncTime(lastResult.getEndTime());
            config.setLastSyncStatus(lastResult.getStatus() != null ? lastResult.getStatus().name() : null);
        }
        
        return config;
    }

    @Override
    public void saveConfig(String repositoryId, DirectorySyncConfig config) {
        throw new UnsupportedOperationException(
            "Configuration save is not supported. Directory sync configuration is managed via nemakiware.properties file. " +
            "Please update the properties file directly and restart the application to apply changes.");
    }

    @Override
    public boolean testConnection(String repositoryId) {
        DirectorySyncConfig config = getConfig(repositoryId);
        
        List<String> validationErrors = validateConfig(config);
        if (!validationErrors.isEmpty()) {
            log.warn("Configuration validation failed: " + String.join(", ", validationErrors));
            return false;
        }
        
        LdapDirectoryConnector connector = createConnector(config);
        return connector.testConnection();
    }

    @Override
    public DirectorySyncResult getLastSyncResult(String repositoryId) {
        return lastSyncResults.get(repositoryId);
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }
    
    private String generateSecurePassword() {
        StringBuilder password = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            int index = SECURE_RANDOM.nextInt(PASSWORD_CHARS.length());
            password.append(PASSWORD_CHARS.charAt(index));
        }
        return password.toString();
    }
}
