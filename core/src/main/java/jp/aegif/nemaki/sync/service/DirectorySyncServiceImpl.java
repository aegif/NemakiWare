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
import java.util.Collections;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                    // The group side is read before any user is written (R153 (a)): a sync that stops on
                    // the groups — the directory, an id attribute with two values, the group list — stops
                    // before it has created anyone whose id a group of this sync listed from before.
                    List<LdapGroup> ldapGroups = connector.searchGroups();
                    // Members are looked up among the entries this sync read — their ids are the
                    // id attribute's values on those entries, not the DN's first RDN (R148).
                    LdapMemberResolver.resolve(ldapUsers, ldapGroups);
                    List<GroupItem> groupsBefore = contentService.getGroupItems(repositoryId);
                    if (groupsBefore == null) {
                        throw new IllegalStateException("the group list of '" + repositoryId + "' could not be "
                                + "read, so which groups list the ids of the users this sync would create is not "
                                + "known; nothing is synced");
                    }
                    UserSyncOutcome users = performUserSync(repositoryId, ldapUsers, ldapGroups, groupsBefore,
                            config, result, dryRun);
                    // The groups are read again: the user side changes them (it takes ids out, and deleting
                    // an orphan user takes the id out of every group), and a copy from before would write
                    // those ids back, or fail on its revision.
                    performGroupSync(repositoryId, ldapGroups, config, result, dryRun, users.states, users.attempted,
                            users.takenOut);
                    
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
     * Whether a directory user is a NemakiWare user after the user sync: it exists; this sync
     * created it (would create it, in a preview); it does not; or its sync failed, so it is not
     * known. Group memberships are written only for users that exist or were created (R148). A
     * created user is told apart: an id a group already listed was nobody until now (R151).
     */
    enum UserState { EXISTS, CREATED, ABSENT, UNKNOWN }

    private static boolean isUser(UserState state) {
        return state == UserState.EXISTS || state == UserState.CREATED;
    }

    /** What the user side of the sync leaves the group side (R153 (a)). */
    static final class UserSyncOutcome {
        final Map<String, UserState> states = new HashMap<>();
        /** The ids whose creation this sync attempted (in a preview, would attempt) — whatever came of it. */
        final Set<String> attempted = new HashSet<>();
        /** Per group, the ids taken out of it before their users were created — what a preview projects. */
        final Map<String, Set<String>> takenOut = new HashMap<>();
    }

    /**
     * The users: looked up, then — before any is created — the ids of those to create are taken out of the groups of
     * this sync that list them from before (R153 (a)): a group listing an id no user has lists nobody, and creating
     * the user would give her that group, whatever LDAP says of her membership. A user whose id cannot be taken out
     * is not created this sync. Then the users are created, and the existing ones updated. A second directory entry
     * with an id already seen is not looked up, created, updated or counted again (the groups naming the id are
     * refused as ambiguous — R148).
     */
    private UserSyncOutcome performUserSync(String repositoryId, List<LdapUser> ldapUsers, List<LdapGroup> ldapGroups,
            List<GroupItem> groupsBefore, DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun) {
        UserSyncOutcome outcome = new UserSyncOutcome();
        Map<String, UserState> states = outcome.states;
        
        log.info("Syncing " + ldapUsers.size() + " users from LDAP");

        String configPrefix = config.getUserPrefix();
        final String userPrefix = (configPrefix == null || configPrefix.isEmpty()) ? "" : configPrefix;

        Set<String> ldapUserIds = ldapUsers.stream()
                .map(u -> userPrefix + u.getUserId())
                .collect(Collectors.toSet());

        // Which directory users NemakiWare has; nothing is written yet.
        Map<String, UserItem> existing = new LinkedHashMap<>();
        Map<String, LdapUser> entryOf = new LinkedHashMap<>();
        List<String> toCreate = new ArrayList<>();
        for (LdapUser ldapUser : ldapUsers) {
            String nemakiUserId = userPrefix + ldapUser.getUserId();
            if (entryOf.containsKey(nemakiUserId)) {
                continue;
            }
            entryOf.put(nemakiUserId, ldapUser);
            states.put(nemakiUserId, UserState.UNKNOWN);
            try {
                UserItem existingUser = contentService.getUserItemById(repositoryId, nemakiUserId);
                if (existingUser != null) {
                    existing.put(nemakiUserId, existingUser);
                    states.put(nemakiUserId, UserState.EXISTS);
                } else if (config.isCreateMissingUsers()) {
                    toCreate.add(nemakiUserId);
                } else {
                    states.put(nemakiUserId, UserState.ABSENT);
                    result.incrementUsersSkipped();
                    log.debug("User skipped (createMissingUsers=false): " + nemakiUserId);
                }
            } catch (Exception e) {
                log.error("Error syncing user " + nemakiUserId + ": " + e.getMessage());
                result.addWarning(nemakiUserId, "User sync failed: " + e.getMessage());
            }
        }

        Set<String> blocked = takeOutIdsOfUsersToCreate(repositoryId, new LinkedHashSet<>(toCreate), ldapGroups,
                groupsBefore, config, result, dryRun, outcome.takenOut);

        for (String nemakiUserId : toCreate) {
            if (blocked.contains(nemakiUserId)) {
                states.put(nemakiUserId, UserState.ABSENT);
                result.addError(nemakiUserId, "user " + nemakiUserId + ": a group of this sync lists the id from "
                        + "before the user existed, and it could not be taken out, so the user is not created this "
                        + "sync (it would gain that group)");
                continue;
            }
            outcome.attempted.add(nemakiUserId);
            if (dryRun) {
                states.put(nemakiUserId, UserState.CREATED);
                result.incrementUsersAdded();
                continue;
            }
            try {
                createUser(repositoryId, entryOf.get(nemakiUserId), userPrefix, config);
                log.info("AUDIT: User created via directory sync: " + nemakiUserId);
                states.put(nemakiUserId, UserState.CREATED);
                result.incrementUsersAdded();
                log.debug("User created: " + nemakiUserId);
            } catch (Exception e) {
                // The user is stored before the change log is written, and a failure there is thrown all the
                // same: what is stored decides (R153 (a)).
                UserItem stored;
                try {
                    stored = contentService.getUserItemById(repositoryId, nemakiUserId);
                } catch (Exception unread) {
                    log.error("Error syncing user " + nemakiUserId + ": " + e.getMessage() + "; reading it back: "
                            + unread.getMessage());
                    result.addWarning(nemakiUserId, "User sync failed: " + e.getMessage() + " (whether the user was "
                            + "stored could not be read: " + unread.getMessage() + ")");
                    continue;
                }
                if (stored != null) {
                    states.put(nemakiUserId, UserState.CREATED);
                    result.incrementUsersAdded();
                    result.addWarning(nemakiUserId, "user " + nemakiUserId + ": the user was stored, but a step "
                            + "after storing it failed (" + e.getMessage() + ")");
                } else {
                    states.put(nemakiUserId, UserState.ABSENT);
                    log.error("Error syncing user " + nemakiUserId + ": " + e.getMessage());
                    result.addWarning(nemakiUserId, "User sync failed: " + e.getMessage());
                }
            }
        }

        for (Map.Entry<String, UserItem> user : existing.entrySet()) {
            String nemakiUserId = user.getKey();
            try {
                if (config.isUpdateExistingUsers() && hasUserChanges(user.getValue(), entryOf.get(nemakiUserId))) {
                    if (!dryRun) {
                        updateUser(repositoryId, user.getValue(), entryOf.get(nemakiUserId));
                    }
                    result.incrementUsersUpdated();
                    log.debug("User updated: " + nemakiUserId);
                } else {
                    result.incrementUsersSkipped();
                    log.debug("User unchanged or update disabled: " + nemakiUserId);
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
        return outcome;
    }

    /**
     * Takes the ids of the users this sync is about to create out of the groups of this sync that list them from
     * before (R153 (a)): the groups the group search returned, and the orphans this sync deletes. One write per
     * group, from the group read past the cache, checked by reading it back; a preview says what it would take out
     * and projects it. Local groups and the orphans kept are not the sync's, and are not touched (an id an
     * administrator listed there is given to the user created with it). Returns the ids still listed in a group
     * after that — those users are not created this sync.
     */
    private Set<String> takeOutIdsOfUsersToCreate(String repositoryId, Set<String> toCreate, List<LdapGroup> ldapGroups,
            List<GroupItem> groupsBefore, DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun,
            Map<String, Set<String>> takenOut) {
        Set<String> blocked = new HashSet<>();
        if (toCreate.isEmpty()) {
            return blocked;
        }
        String configPrefix = config.getGroupPrefix();
        String groupPrefix = (configPrefix == null) ? DirectorySyncConfig.DEFAULT_GROUP_PREFIX : configPrefix;
        Set<String> ldapGroupIds = new HashSet<>();
        for (LdapGroup ldapGroup : ldapGroups) {
            ldapGroupIds.add(groupPrefix + ldapGroup.getGroupId());
        }
        Set<String> seen = new HashSet<>();
        for (GroupItem group : groupsBefore) {
            String groupId = group.getGroupId();
            if (groupId == null || !seen.add(groupId)) {
                continue;
            }
            boolean ours = ldapGroupIds.contains(groupId)
                    || (config.isDeleteOrphanGroups() && groupId.startsWith(groupPrefix));
            if (!ours) {
                continue;
            }
            Set<String> listed = new LinkedHashSet<>();
            for (String id : group.getUsers() == null ? List.<String>of() : group.getUsers()) {
                if (toCreate.contains(id)) {
                    listed.add(id);
                }
            }
            if (listed.isEmpty()) {
                continue;
            }
            if (dryRun) {
                takenOut.put(groupId, listed);
                result.addWarning(groupId, "group " + groupId + ": " + listed.size() + " id(s) of users this sync "
                        + "would create were listed in the group before the users existed, and would be taken out "
                        + "first, for example " + sample(new ArrayList<>(listed)));
                continue;
            }
            String failure = null;
            GroupItem after = null;
            try {
                GroupItem fresh = contentService.getGroupItemByIdFresh(repositoryId, groupId);
                if (fresh == null) {
                    failure = "the group could not be read";
                } else {
                    List<String> keep = new ArrayList<>(fresh.getUsers() == null ? List.of() : fresh.getUsers());
                    keep.removeAll(listed);
                    fresh.setUsers(keep);
                    fresh.setModifier("system");
                    fresh.setModified(new GregorianCalendar());
                    try {
                        contentService.update(new SystemCallContext(repositoryId), repositoryId, fresh);
                    } catch (Exception e) {
                        failure = e.getMessage();
                    }
                }
                after = contentService.getGroupItemByIdFresh(repositoryId, groupId);
            } catch (Exception e) {
                failure = (failure == null ? "" : failure + "; ") + e.getMessage();
                after = null;
            }
            Set<String> left = new LinkedHashSet<>(listed);
            if (after != null) {
                left.retainAll(after.getUsers() == null ? List.of() : after.getUsers());
            }
            Set<String> out = new LinkedHashSet<>(listed);
            out.removeAll(left);
            if (!out.isEmpty()) {
                takenOut.put(groupId, out);
                result.addWarning(groupId, "group " + groupId + ": " + out.size() + " id(s) of users this sync creates "
                        + "were listed in the group before the users existed, and were taken out first, for example "
                        + sample(new ArrayList<>(out)));
            }
            if (!left.isEmpty()) {
                blocked.addAll(left);
                result.addError(groupId, "group " + groupId + ": " + left.size() + " id(s) of users this sync would "
                        + "create are listed in the group from before the users existed and could not be taken out"
                        + (failure == null ? "" : " (" + failure + ")") + ", so those users are not created this "
                        + "sync, for example " + sample(new ArrayList<>(left)));
            }
        }
        return blocked;
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
        performGroupSync(repositoryId, ldapGroups, config, result, dryRun, userStates, Set.of(), Map.of());
    }

    private void performGroupSync(String repositoryId, List<LdapGroup> ldapGroups,
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun,
            Map<String, UserState> userStates, Set<String> attempted, Map<String, Set<String>> takenOut) {
        
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
        if (dryRun && !takenOut.isEmpty()) {
            // The preview did not take the ids of the users it would create out of the groups: it sees the
            // groups as the sync would (R153 (a)).
            List<GroupItem> projected = new ArrayList<>();
            for (GroupItem g : existingGroups) {
                Set<String> out = g.getGroupId() == null ? null : takenOut.get(g.getGroupId());
                if (out == null) {
                    projected.add(g);
                } else {
                    List<String> users = new ArrayList<>(g.getUsers() == null ? List.of() : g.getUsers());
                    users.removeAll(out);
                    GroupItem copy = new GroupItem(g.getId(), g.getObjectType(), g.getGroupId(), g.getName(), users,
                            g.getGroups() == null ? new ArrayList<>() : new ArrayList<>(g.getGroups()));
                    copy.setRevision(g.getRevision());
                    projected.add(copy);
                }
            }
            existingGroups = projected;
        }
        Set<String> present = existingGroups.stream()
                .map(GroupItem::getGroupId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        
        Map<String, GroupItem> syncedGroupMap = existingGroups.stream()
                .filter(g -> g.getGroupId() != null && g.getGroupId().startsWith(groupPrefix))
                .collect(Collectors.toMap(GroupItem::getGroupId, g -> g));

        Map<String, LdapGroup> ldapGroupMap = new HashMap<>();
        Map<String, List<LdapGroup>> entriesOf = new HashMap<>();
        for (LdapGroup lg : ldapGroups) {
            String nemakiGroupId = groupPrefix + lg.getGroupId();
            ldapGroupMap.put(nemakiGroupId, lg);
            entriesOf.computeIfAbsent(nemakiGroupId, k -> new ArrayList<>()).add(lg);
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
        // R151: the nesting as stored (and as this sync writes it), the nestings that could not be checked
        // against LDAP or removed, and the groups below them — held back while those nestings stay.
        Map<String, List<String>> storedNesting = new HashMap<>();
        Map<String, Set<String>> uncheckedEdges = new LinkedHashMap<>();
        Map<String, String> withheldBy = new HashMap<>();
        Set<String> firstStepWrote = new HashSet<>();
        Set<String> orphansKept = new HashSet<>();
        Set<String> orphans = new LinkedHashSet<>();
        Map<String, List<String>> plannedGone = new LinkedHashMap<>();
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

            // R151: a nesting LDAP no longer states, kept by a group this sync does not write — or writes only
            // after the groups below it — lets whoever those groups gain reach that group, though LDAP does not
            // name them under it. Before any group is written, the nesting LDAP is known to have taken out is
            // removed; the groups below a nesting that cannot be checked are given no one new.
            for (GroupItem g : existingGroups) {
                if (g.getGroupId() != null) {
                    storedNesting.put(g.getGroupId(), new ArrayList<>(g.getGroups() == null ? List.of() : g.getGroups()));
                }
            }
            // Planned first, written after: the members a removal would take a group from are put back
            // before it is made (R154).
            Set<String> parents = new HashSet<>();
            for (LdapGroup ldapGroup : ldapGroups) {
                String parentId = groupPrefix + ldapGroup.getGroupId();
                if (!syncedGroupMap.containsKey(parentId) || !parents.add(parentId)) {
                    continue;
                }
                boolean read = membersRead(ldapGroup);
                Set<String> named = prefixed(groupPrefix, memberGroupIdsOf(ldapGroup));
                Set<String> maybe = prefixed(groupPrefix, ldapGroup.getAmbiguousGroupIds());
                boolean unnamed = ldapGroup.getUnresolvedMembers() != null && !ldapGroup.getUnresolvedMembers().isEmpty();
                List<String> gone = new ArrayList<>();
                List<String> cannotCheck = new ArrayList<>();
                for (String nestedId : storedNesting.get(parentId)) {
                    // Of a group whose members could not be read — or whose id two entries share, so which entry
                    // it is is not known — no nesting is known to be LDAP's either (review round 1).
                    if (read && named.contains(nestedId)) {
                        continue;
                    }
                    // A value the sync could not name is a DN no search returned as written: a group the group
                    // search does not reach, or one it does written differently — the resolver lists those
                    // among the groups a value could name (maybe) — never a group NemakiWare holds without
                    // the prefix.
                    if (!read || maybe.contains(nestedId)
                            || (ldapGroup.isAnyGroupCouldBeNamed() && nestedId.startsWith(groupPrefix))
                            || (unnamed && nestedId.startsWith(groupPrefix) && !ldapGroupMap.containsKey(nestedId))) {
                        cannotCheck.add(nestedId);
                        uncheck(uncheckedEdges, parentId, nestedId);
                    } else {
                        gone.add(nestedId);
                    }
                }
                if (!cannotCheck.isEmpty()) {
                    result.addWarning(parentId, "group " + parentId + ": " + cannotCheck.size() + " nested group(s) "
                            + "could not be checked against LDAP (its members could not be read, or a member value "
                            + "could name them), so the groups below them are given no one new, for example "
                            + sample(cannotCheck));
                }
                if (!gone.isEmpty()) {
                    plannedGone.put(parentId, gone);
                }
            }
        }
        if (config.isDeleteOrphanGroups()) {
            // An orphan is deleted after the groups are written, and its deletion strips the nesting of it from the
            // groups above: their members are put back first, where nested groups are not synced as well (R154, R156).
            // Where they are, its own nesting is removed before any group is written — until then it would carry
            // whoever the groups below it gain (R151); where they are not, no nesting was read, and none is removed.
            for (String orphanId : syncedGroupMap.keySet()) {
                if (!ldapGroupMap.containsKey(orphanId)) {
                    orphans.add(orphanId);
                    List<String> all = new ArrayList<>(storedNesting.getOrDefault(orphanId, List.of()));
                    if (!all.isEmpty()) {
                        plannedGone.put(orphanId, all);
                    }
                }
            }
        }

        // R154 / R156 (both settings): the groups as stored, whom LDAP names in each group of this sync — counted once,
        // for this step and the main loop alike — and what the main loop leaves each group holding of those it held:
        // the members LDAP names in it directly and, where nested groups are synced, those it names surely through the
        // groups it nests (the main loop keeps those it would not reach otherwise). A group refused now keeps what it
        // holds; one refused later keeps it too, so this can take more than the loop does, never less (batch 14).
        Map<String, List<String>> nestingBefore = new HashMap<>();
        Map<String, Set<String>> usersBefore = new HashMap<>();
        for (GroupItem g : existingGroups) {
            if (g.getGroupId() != null) {
                nestingBefore.put(g.getGroupId(), new ArrayList<>(g.getGroups() == null ? List.of() : g.getGroups()));
                usersBefore.computeIfAbsent(g.getGroupId(), k -> new LinkedHashSet<>())
                        .addAll(g.getUsers() == null ? List.of() : g.getUsers());
            }
        }
        Map<String, Naming> namings = namings(entriesOf, groupPrefix, userPrefix, syncNestedGroups);
        Map<String, Set<String>> usersAfter = new HashMap<>();
        Set<String> sources = new LinkedHashSet<>(plannedGone.keySet());
        for (Map.Entry<String, Set<String>> e : usersBefore.entrySet()) {
            String groupId = e.getKey();
            Set<String> kept = new LinkedHashSet<>(e.getValue());
            LdapGroup entry = ldapGroupMap.get(groupId);
            if (entry != null && !refusals.containsKey(groupId)) {
                Set<String> stays = prefixed(userPrefix, entry.getMemberUserIds());
                if (syncNestedGroups) {
                    stays.addAll(namings.get(groupId).surely);
                }
                kept.retainAll(stays);
                if (kept.size() < e.getValue().size()) {
                    sources.add(groupId);
                }
            }
            usersAfter.put(groupId, kept);
        }
        for (Map.Entry<String, List<String>> e : nestingBefore.entrySet()) {
            for (String child : e.getValue()) {
                if (orphans.contains(child)) {
                    sources.add(e.getKey());
                }
            }
        }
        // The nesting the removals leave; a removal kept below puts its nesting back before the groups above count.
        Map<String, List<String>> nestingAfter = new HashMap<>();
        for (Map.Entry<String, List<String>> e : nestingBefore.entrySet()) {
            List<String> kept = new ArrayList<>(e.getValue());
            kept.removeAll(plannedGone.getOrDefault(e.getKey(), List.of()));
            kept.removeAll(orphans);
            nestingAfter.put(e.getKey(), kept);
        }
        Map<String, Set<String>> putBackDone = new HashMap<>();
        Map<String, Set<String>> heldEdges = new HashMap<>();
        Map<String, Set<String>> heldMembers = new HashMap<>();
        Map<String, Boolean> couldBeUsers = new HashMap<>();
        for (String groupId : childrenFirstAmong(ancestorsOrSelf(sources, nestingBefore), nestingBefore)) {
            // Every group met here is stored, so one this search returned is a synced group, and an orphan is not
            // one this search returned (batch 13 review round 1: the two other conditions decided nothing).
            if (!entriesOf.containsKey(groupId)) {
                continue;
            }
            Naming named = namings.get(groupId);
            // Whom the group stops reaching through the removals or the main loop's writes below it, but those put back
            // below (R154, R156). They reached it before as the store holds it: a write below that is not made, or that
            // fails, leaves them there (batch 14 design review — so the hold below counts them all).
            Set<String> lost = reach(groupId, nestingBefore, usersBefore);
            lost.removeAll(reach(groupId, nestingAfter, usersAfter, putBackDone));
            List<String> putBack = new ArrayList<>();
            List<String> unsure = new ArrayList<>();
            for (String id : lost) {
                UserState state = userStates.get(id);
                if (attempted.contains(id) || state == UserState.ABSENT) {
                    // No NemakiWare user had the id before this sync, so nothing anyone had is taken (batch 13 review
                    // round 1: Codex P1, answered).
                    continue;
                }
                if (named.surely.contains(id)) {
                    putBack.add(id);
                } else if (couldBeNamed(id, state, named, userPrefix, repositoryId, couldBeUsers)) {
                    unsure.add(id);
                }
            }
            Set<String> keep = new LinkedHashSet<>(unsure);
            if (!putBack.isEmpty()) {
                List<String> missing = putBack(repositoryId, groupId, putBack, dryRun, result, syncedGroupMap);
                Set<String> done = new LinkedHashSet<>(putBack);
                done.removeAll(missing);
                if (!done.isEmpty()) {
                    putBackDone.computeIfAbsent(groupId, k -> new LinkedHashSet<>()).addAll(done);
                    firstStepWrote.add(groupId);
                }
                keep.addAll(missing);
            }
            if (keep.isEmpty()) {
                continue;
            }
            // Whom LDAP may name in the group, or who could not be put back there, keeps it: the changes that would take
            // it from them are not made for them this sync — what is not known is not taken for "not named" (batch 13
            // review round 1; R157). A write below that would take them out keeps them as the store holds them (batch 14
            // review round 1: Codex P1 — the owner chose to hold LDAP's taking-out rather than have them lose the group)…
            for (String below : descendantsOrSelf(groupId, nestingBefore)) {
                Set<String> dropped = new LinkedHashSet<>(usersBefore.getOrDefault(below, Set.of()));
                dropped.removeAll(usersAfter.getOrDefault(below, Set.of()));
                dropped.retainAll(keep);
                if (!dropped.isEmpty()) {
                    heldMembers.computeIfAbsent(below, k -> new LinkedHashSet<>()).addAll(dropped);
                    usersAfter.get(below).addAll(dropped);
                }
            }
            // …and the removals and orphans on the way to a group that holds them and that the group would no longer
            // reach are not made either — only those (batch 14 review round 1: subagent P3). Kept, they count for the
            // groups above.
            Set<String> stillLost = new LinkedHashSet<>(keep);
            stillLost.removeAll(reach(groupId, nestingAfter, usersAfter, putBackDone));
            if (!stillLost.isEmpty()) {
                Set<String> targets = new HashSet<>();
                for (String below : descendantsOrSelf(groupId, nestingBefore)) {
                    // Where they are after this sync's writes: where they were, or kept there. None of these is a group
                    // the group still reaches — it would reach them there (batch 14 review round 1: the test was made twice).
                    Set<String> holders = new HashSet<>(usersAfter.getOrDefault(below, Set.of()));
                    holders.retainAll(stillLost);
                    if (!holders.isEmpty()) {
                        targets.add(below);
                    }
                }
                for (String below : descendantsOrSelf(groupId, nestingBefore)) {
                    for (String child : plannedGone.getOrDefault(below, List.of())) {
                        if (!Collections.disjoint(descendantsOrSelf(child, nestingBefore), targets)
                                && heldEdges.computeIfAbsent(below, k -> new LinkedHashSet<>()).add(child)) {
                            nestingAfter.get(below).add(child);
                        }
                    }
                    if (orphans.contains(below) && !Collections.disjoint(descendantsOrSelf(below, nestingBefore), targets)
                            && orphansKept.add(below)) {
                        // Deleting it strips the nesting of it from the groups above (R154): it is kept this sync.
                        for (Map.Entry<String, List<String>> e : nestingBefore.entrySet()) {
                            if (e.getValue().contains(below)) {
                                nestingAfter.get(e.getKey()).add(below);
                            }
                        }
                    }
                }
            }
            if (!unsure.isEmpty()) {
                result.addError(groupId, "group " + groupId + ": whom LDAP names in the group is not all known (a member "
                        + "value it could not name or read, or an id two entries share), and " + unsure.size()
                        + " member(s) who would lose the group through this sync's changes — a nesting LDAP no longer "
                        + "states below it, an orphan's deletion, or a write that takes them out — could be among them, so "
                        + "those changes are not made for them this sync, for example " + sample(unsure));
            }
        }

        // The removals planned above, but those kept: none where nested groups are not synced, where nothing is planned.
        for (Map.Entry<String, List<String>> planned : plannedGone.entrySet()) {
            String parentId = planned.getKey();
            List<String> held = new ArrayList<>(heldEdges.getOrDefault(parentId, Set.of()));
            List<String> gone = new ArrayList<>(planned.getValue());
            gone.removeAll(held);
            if (!held.isEmpty()) {
                // Removing it would take from a group above members LDAP names — or may name — in it, who were not
                // put back: it stays, and the groups below it are given no one new (R154). A group the loop writes is
                // written with it, and with LDAP's other changes (batch 14 review round 1: subagent P2).
                for (String nestedId : held) {
                    uncheck(uncheckedEdges, parentId, nestedId);
                }
                result.addWarning(parentId, "group " + parentId + ": " + held.size() + " nested group(s) LDAP no longer "
                        + "names under it are kept this sync, because members LDAP names — or may name — in a group above "
                        + "would lose it through them, and the groups below them are given no one new, for example "
                        + sample(held));
            }
            if (removeGoneNesting(repositoryId, parentId, gone, dryRun, result, syncedGroupMap, storedNesting,
                    uncheckedEdges, refusals)) {
                firstStepWrote.add(parentId);
            }
            if (orphans.contains(parentId) && refusals.containsKey(parentId)) {
                // No LDAP group reports it: the main loop does not see orphans (review round 1).
                result.addError(parentId, refusals.get(parentId));
            }
        }
        withheldBy = withheld(uncheckedEdges, storedNesting);

        Set<String> established = new HashSet<>();
        // What the groups hold as the loop writes them — after the first step, then each write — for the members a
        // group reaches through the groups it nests (batch 13 review round 1).
        Map<String, Set<String>> usersNow = new HashMap<>();
        Map<String, List<String>> nestingNow = new HashMap<>();
        for (GroupItem g : existingGroups) {
            if (g.getGroupId() != null) {
                GroupItem now = syncedGroupMap.getOrDefault(g.getGroupId(), g);
                usersNow.put(g.getGroupId(), new HashSet<>(now.getUsers() == null ? List.of() : now.getUsers()));
                nestingNow.put(g.getGroupId(), new ArrayList<>(now.getGroups() == null ? List.of() : now.getGroups()));
            }
        }
        // The groups the loop has met: their state is settled for this sync, as is that of the groups it does not write.
        Set<String> processed = new HashSet<>();
        for (LdapGroup ldapGroup : order) {
            String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
            processed.add(nemakiGroupId);
            boolean writing = false;
            boolean creating = false;
            boolean isEstablished = true;
            Set<String> writtenUsers = null;
            Set<String> writtenGroups = null;
            String writtenName = null;
            
            try {
                GroupItem existingGroup = syncedGroupMap.get(nemakiGroupId);
                String unknown = refusals.get(nemakiGroupId);
                if (unknown != null) {
                    if (firstStepWrote.contains(nemakiGroupId)) {
                        unknown = unknown + " (before any group was written, this sync changed it first — the nesting "
                                + "LDAP no longer names under it removed, or members LDAP names in it put back; see the "
                                + "warning)";
                    }
                    log.warn("AUDIT: Group not written by directory sync: " + unknown);
                    result.addError(nemakiGroupId, unknown);
                    continue;
                }
                String uncheckedParent = withheldBy.get(nemakiGroupId);
                if (existingGroup == null && uncheckedParent != null) {
                    // Creating it would bring the nesting that names its id to life (R151).
                    String held = "group " + nemakiGroupId + ": " + uncheckedParent + " nests this id, directly or "
                            + "through other groups, by a nesting this sync could not check against LDAP, so creating "
                            + "the group would give whoever it names that group's permissions; the group is not created";
                    log.warn("AUDIT: Group not written by directory sync: " + held);
                    result.addError(nemakiGroupId, held);
                    continue;
                }
                if (existingGroup == null && (isUser(userStates.get(nemakiGroupId))
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
                    if (isUser(userStates.get(userPrefix + userId))) {
                        users.add(userId);
                    } else {
                        notUsers.add(userId);
                    }
                }
                // Below a nesting this sync could not check, whoever the group gains would reach the group
                // above it: the members LDAP takes out are taken out, and none are added (R151). The ids of
                // the users this sync created were taken out before they were created (R153 (a)).
                List<String> heldUsers = new ArrayList<>();
                if (uncheckedParent != null) {
                    Set<String> hadUsers = new HashSet<>(existingGroup.getUsers() == null ? List.of() : existingGroup.getUsers());
                    for (Iterator<String> named = users.iterator(); named.hasNext();) {
                        String userId = named.next();
                        if (!hadUsers.contains(userPrefix + userId)) {
                            heldUsers.add(userId);
                            named.remove();
                        }
                    }
                }
                // Members whom a group above may name through this group, or who could not be put back there, stay as the
                // store holds them: LDAP's taking them out is not made this sync (batch 14 review round 1).
                // They are none of LDAP's direct members: the prediction keeps those.
                List<String> keptMembers = new ArrayList<>(heldMembers.getOrDefault(nemakiGroupId, Set.of()));
                for (String id : keptMembers) {
                    users.add(id.substring(userPrefix.length()));
                }
                ldapGroup.setMemberUserIds(users);
                if (!keptMembers.isEmpty()) {
                    result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + keptMembers.size() + " member(s) "
                            + "LDAP no longer names in it are kept this sync, because a group above may name them through it "
                            + "— whom LDAP names there is not all known — or they could not be put back there, for example "
                            + sample(keptMembers));
                }
                if (!notUsers.isEmpty()) {
                    result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + notUsers.size()
                            + " member(s) are directory users that are not NemakiWare users (this sync did not "
                            + "create them) and were not written, for example " + sample(notUsers));
                }
                List<String> heldGroups = new ArrayList<>();
                // Nestings LDAP took out, kept for whom a group above may name: written as they are, with LDAP's (R154).
                Set<String> keptNesting = heldEdges.getOrDefault(nemakiGroupId, Set.of());
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
                        } else if (had.contains(nestedId) && established.contains(nestedId)) {
                            nested.add(groupId);
                        } else if (had.contains(nestedId) && present.contains(nestedId)) {
                            nested.add(groupId);
                            kept.add(nestedId);
                        } else if (had.contains(nestedId)) {
                            namingNoGroup.add(nestedId);
                        } else if (!established.contains(nestedId)) {
                            notAdded.add(nestedId);
                        } else if (uncheckedParent != null) {
                            heldGroups.add(nestedId);
                        } else {
                            nested.add(groupId);
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
                    isEstablished = kept.isEmpty() && keptNesting.isEmpty();
                    // A member the group had, whom LDAP surely names in it through a group it nests and who does not
                    // reach it through the groups this write nests (as they hold them now), stays a member: writing
                    // LDAP's direct members alone would take the group from them — the members a removal below put
                    // back among them too (batch 13 review round 1: subagent P2). They had the group, so no one gains it.
                    if (existingGroup != null) {
                        // Only through groups whose state is settled: one the loop writes later may still drop them,
                        // and an orphan takes its members along when it is deleted — this sync, or the next where a
                        // nesting kept it this once (batch 14 — R158 (a)).
                        Set<String> reached = new HashSet<>();
                        for (String groupId : nested) {
                            reached.addAll(reachSettled(groupPrefix + groupId, nestingNow, usersNow,
                                    id -> orphans.contains(id) || (ldapGroupMap.containsKey(id) && !processed.contains(id))));
                        }
                        Set<String> direct = prefixed(userPrefix, users);
                        List<String> keptUsers = new ArrayList<>(users);
                        Set<String> namedThrough = namings.get(nemakiGroupId).surely;
                        for (String id : existingGroup.getUsers() == null ? List.<String>of() : existingGroup.getUsers()) {
                            UserState state = userStates.get(id);
                            if (namedThrough.contains(id) && !direct.contains(id) && !reached.contains(id)
                                    && (state == UserState.EXISTS || state == UserState.UNKNOWN)) {
                                keptUsers.add(id.substring(userPrefix.length()));
                            }
                        }
                        if (keptUsers.size() > users.size()) {
                            ldapGroup.setMemberUserIds(keptUsers);
                        }
                    }
                }
                if (!heldUsers.isEmpty() || !heldGroups.isEmpty()) {
                    List<String> held = new ArrayList<>(heldUsers);
                    held.addAll(heldGroups);
                    result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": " + heldUsers.size() + " member(s) and "
                            + heldGroups.size() + " nested group(s) LDAP adds were not added, because " + uncheckedParent
                            + " nests this group, directly or through other groups, by a nesting this sync could not "
                            + "check against LDAP, and whoever this group gains would gain that group's permissions; "
                            + "for example " + sample(held));
                }
                writtenUsers = prefixed(userPrefix, ldapGroup.getMemberUserIds());
                writtenName = ldapGroup.getGroupName();
                if (syncNestedGroups) {
                    writtenGroups = prefixed(groupPrefix, memberGroupIdsOf(ldapGroup));
                    writtenGroups.addAll(keptNesting);
                } else {
                    writtenGroups = existingGroup == null ? Set.of() : null;
                }
                if (!keptMembers.isEmpty()) {
                    // Members LDAP took out stay: a group newly nesting this one would give them its permissions (R149).
                    isEstablished = false;
                }
                if (existingGroup != null) {
                    if (hasGroupChanges(existingGroup, ldapGroup, groupPrefix, config, keptNesting)) {
                        if (!dryRun) {
                            writing = true;
                            updateGroup(repositoryId, existingGroup, ldapGroup, groupPrefix, config, keptNesting);
                        }
                        result.incrementGroupsUpdated();
                        withheldBy = nestingWritten(nemakiGroupId, writtenGroups, storedNesting, uncheckedEdges, withheldBy);
                        log.debug("Group updated: " + nemakiGroupId);
                    } else {
                        result.incrementGroupsSkipped();
                        log.debug("Group unchanged: " + nemakiGroupId);
                    }
                } else {
                    creating = true;
                    if (!dryRun) {
                        writing = true;
                        createGroup(repositoryId, ldapGroup, groupPrefix, config);
                        log.info("AUDIT: Group created via directory sync: " + nemakiGroupId);
                    }
                    result.incrementGroupsCreated();
                    withheldBy = nestingWritten(nemakiGroupId, writtenGroups, storedNesting, uncheckedEdges, withheldBy);
                    log.debug("Group created: " + nemakiGroupId);
                }
                if (isEstablished) {
                    established.add(nemakiGroupId);
                }
                if (writtenUsers != null) {
                    usersNow.put(nemakiGroupId, new HashSet<>(writtenUsers));
                    if (writtenGroups != null) {
                        nestingNow.put(nemakiGroupId, new ArrayList<>(writtenGroups));
                    }
                }
            } catch (Exception e) {
                // The group is stored before the change log and the index are written, and a failure there
                // is thrown all the same: what was stored decides, read back past the cache (R152).
                if (writing && storedAsWritten(repositoryId, nemakiGroupId, writtenName, writtenUsers, writtenGroups)) {
                    result.addWarning(nemakiGroupId, "group " + nemakiGroupId + ": the group was stored, but a step "
                            + "after storing it failed (" + e.getMessage() + ")");
                    if (creating) {
                        result.incrementGroupsCreated();
                    } else {
                        result.incrementGroupsUpdated();
                    }
                    withheldBy = nestingWritten(nemakiGroupId, writtenGroups, storedNesting, uncheckedEdges, withheldBy);
                    if (isEstablished) {
                        established.add(nemakiGroupId);
                    }
                    usersNow.put(nemakiGroupId, new HashSet<>(writtenUsers));
                    if (writtenGroups != null) {
                        nestingNow.put(nemakiGroupId, new ArrayList<>(writtenGroups));
                    }
                } else {
                    log.error("Error syncing group " + nemakiGroupId + ": " + e.getMessage());
                    result.addError(nemakiGroupId, e.getMessage());
                }
            }
        }

        if (config.isDeleteOrphanGroups()) {
            for (GroupItem existingGroup : syncedGroupMap.values()) {
                if (!ldapGroupMap.containsKey(existingGroup.getGroupId())) {
                    if (orphansKept.contains(existingGroup.getGroupId())) {
                        result.addError(existingGroup.getGroupId(), "group " + existingGroup.getGroupId() + ": the "
                                + "orphan is not deleted this sync — a group above it was not first given back the "
                                + "members LDAP names (or may name) in it, who reach it through the orphan");
                        continue;
                    }
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

    private static Set<String> prefixed(String prefix, List<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids != null) {
            for (String id : ids) {
                out.add(prefix + id);
            }
        }
        return out;
    }

    /**
     * Whether every member value of the group was read and looked up, so that what LDAP does not name under
     * it is known (R151): its entry is the one the id means, the attribute came back whole, and the values
     * went through the resolver.
     */
    private static boolean membersRead(LdapGroup ldapGroup) {
        if (ldapGroup.isIdShared() || ldapGroup.isMembersRanged() || !ldapGroup.isMemberAttributePresent()) {
            return false;
        }
        if (ldapGroup.getMemberDns() == null || ldapGroup.getMemberDns().isEmpty()) {
            return true;
        }
        return !isEmpty(ldapGroup.getMemberUserIds()) || !isEmpty(ldapGroup.getMemberGroupIds())
                || !isEmpty(ldapGroup.getUnresolvedMembers()) || !isEmpty(ldapGroup.getAmbiguousMembers())
                || !isEmpty(ldapGroup.getAmbiguousGroupMembers());
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    /**
     * Removes from a stored group the nested ids LDAP is known to have taken out — nothing else: its users, name and
     * other nested groups stay as they are (R151; the members a removal takes a group above from are put back
     * before it, by {@link #putBack}). The preview projects the removal without writing. Says whether a nested id
     * was removed (or, in the preview, would be). What the group holds afterwards is read back past the cache; a removal not seen there is tried
     * once more, from the group as read back (a revision the listed group no longer has fails the first write),
     * and a nesting still there after that is held as unchecked. A group that cannot be read back is not written
     * by this sync.
     */
    private boolean removeGoneNesting(String repositoryId, String groupId, List<String> gone,
            boolean dryRun, DirectorySyncResult result, Map<String, GroupItem> syncedGroupMap,
            Map<String, List<String>> storedNesting, Map<String, Set<String>> uncheckedEdges,
            Map<String, String> refusals) {
        if (gone.isEmpty()) {
            return false;
        }
        if (dryRun) {
            GroupItem stored = syncedGroupMap.get(groupId);
            List<String> keep = new ArrayList<>(storedNesting.get(groupId));
            keep.removeAll(gone);
            syncedGroupMap.put(groupId, new GroupItem(stored.getId(), stored.getObjectType(), stored.getGroupId(),
                    stored.getName(), stored.getUsers() == null ? new ArrayList<>() : new ArrayList<>(stored.getUsers()),
                    new ArrayList<>(keep)));
            storedNesting.put(groupId, keep);
            result.addWarning(groupId, "group " + groupId + ": " + gone.size() + " nested group(s) LDAP no longer "
                    + "names under it were removed, for example " + sample(gone));
            return true;
        }
        List<String> left = new ArrayList<>(gone);
        String failure = null;
        String firstFailure = null;
        for (int attempt = 0; attempt < 2 && !left.isEmpty(); attempt++) {
            GroupItem stored = syncedGroupMap.get(groupId);
            List<String> keep = new ArrayList<>(storedNesting.get(groupId));
            keep.removeAll(gone);
            stored.setGroups(new ArrayList<>(keep));
            stored.setModifier("system");
            stored.setModified(new GregorianCalendar());
            failure = null;
            try {
                contentService.update(new SystemCallContext(repositoryId), repositoryId, stored);
            } catch (Exception e) {
                failure = e.getMessage();
            }
            GroupItem after;
            try {
                after = contentService.getGroupItemByIdFresh(repositoryId, groupId);
            } catch (Exception e) {
                after = null;
                failure = (failure == null ? "" : failure + "; ") + e.getMessage();
            }
            if (after == null) {
                refusals.merge(groupId, "group " + groupId + ": removing the nested groups LDAP no longer names under "
                        + "it failed, and what the group holds now could not be read (" + failure + "); the group is "
                        + "not written", (first, then) -> first + "; " + then);
                for (String nestedId : storedNesting.get(groupId)) {
                    uncheck(uncheckedEdges, groupId, nestedId);
                }
                return false;
            }
            syncedGroupMap.put(groupId, after);
            List<String> now = after.getGroups() == null ? new ArrayList<>() : new ArrayList<>(after.getGroups());
            storedNesting.put(groupId, now);
            left.retainAll(now);
            if (!left.isEmpty() && firstFailure == null) {
                firstFailure = failure == null ? "the removal was not stored" : failure;
            }
        }
        List<String> removed = new ArrayList<>(gone);
        removed.removeAll(left);
        if (!removed.isEmpty()) {
            result.addWarning(groupId, "group " + groupId + ": " + removed.size() + " nested group(s) LDAP no longer "
                    + "names under it were removed, for example " + sample(removed)
                    + (failure != null && left.isEmpty() ? " (a step after storing the group failed: " + failure + ")" : "")
                    + (firstFailure != null && left.isEmpty() ? " (on a second try; the first failed: " + firstFailure + ")" : ""));
        }
        if (!left.isEmpty()) {
            for (String nestedId : left) {
                uncheck(uncheckedEdges, groupId, nestedId);
            }
            result.addError(groupId, "group " + groupId + ": " + left.size() + " nested group(s) LDAP no longer names "
                    + "under it could not be removed" + (failure == null ? "" : " (" + failure + ")") + ", so the "
                    + "groups below them are given no one new while the nesting stays, for example " + sample(left));
        }
        return !removed.isEmpty();
    }

    private static void uncheck(Map<String, Set<String>> uncheckedEdges, String parentId, String nestedId) {
        uncheckedEdges.computeIfAbsent(parentId, k -> new LinkedHashSet<>()).add(nestedId);
    }

    /** The groups and every group that nests them, directly or through others, in the stored nesting. */
    private static Set<String> ancestorsOrSelf(Set<String> ids, Map<String, List<String>> nesting) {
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (Map.Entry<String, List<String>> e : nesting.entrySet()) {
            for (String child : e.getValue()) {
                parentsOf.computeIfAbsent(child, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Set<String> found = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(ids);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (found.add(id)) {
                for (String parent : parentsOf.getOrDefault(id, List.of())) {
                    todo.push(parent);
                }
            }
        }
        return found;
    }

    /** The users a group reaches through the nesting given, with the members put back in each group as well. */
    private static Set<String> reach(String groupId, Map<String, List<String>> nesting,
            Map<String, Set<String>> usersOf, Map<String, Set<String>> putBack) {
        Set<String> users = reach(groupId, nesting, usersOf);
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.push(groupId);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!seen.add(id)) {
                continue;
            }
            users.addAll(putBack.getOrDefault(id, Set.of()));
            for (String child : nesting.getOrDefault(id, List.of())) {
                todo.push(child);
            }
        }
        return users;
    }

    /** The users a group reaches through the nesting given, not through the groups the test skips. */
    private static Set<String> reachSettled(String groupId, Map<String, List<String>> nesting,
            Map<String, Set<String>> usersOf, java.util.function.Predicate<String> skip) {
        Set<String> users = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.push(groupId);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!seen.add(id) || skip.test(id)) {
                continue;
            }
            users.addAll(usersOf.getOrDefault(id, Set.of()));
            for (String child : nesting.getOrDefault(id, List.of())) {
                todo.push(child);
            }
        }
        return users;
    }

    /** The group and every group below it in the nesting given. */
    private static Set<String> descendantsOrSelf(String groupId, Map<String, List<String>> nesting) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.push(groupId);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (found.add(id)) {
                for (String child : nesting.getOrDefault(id, List.of())) {
                    todo.push(child);
                }
            }
        }
        return found;
    }

    /** The groups given, each after the groups given below it in the nesting (a cycle in any order). */
    private static List<String> childrenFirstAmong(Set<String> ids, Map<String, List<String>> nesting) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String root : ids) {
            Deque<Object[]> work = new ArrayDeque<>();
            if (!seen.add(root)) {
                continue;
            }
            work.push(new Object[] {root, nesting.getOrDefault(root, List.of()).iterator()});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                @SuppressWarnings("unchecked")
                Iterator<String> it = (Iterator<String>) frame[1];
                if (it.hasNext()) {
                    String child = it.next();
                    if (seen.add(child)) {
                        work.push(new Object[] {child, nesting.getOrDefault(child, List.of()).iterator()});
                    }
                } else {
                    work.pop();
                    String id = (String) frame[0];
                    if (ids.contains(id)) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    /** The users a group reaches through the nesting given — its own and those of every group below it. */
    private static Set<String> reach(String groupId, Map<String, List<String>> nesting,
            Map<String, Set<String>> usersOf) {
        Set<String> users = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.push(groupId);
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!seen.add(id)) {
                continue;
            }
            users.addAll(usersOf.getOrDefault(id, Set.of()));
            for (String child : nesting.getOrDefault(id, List.of())) {
                todo.push(child);
            }
        }
        return users;
    }

    /** Whom LDAP names in a group, by what the sync read (R154): surely, possibly, and whether anyone else could be. */
    static final class Naming {
        /** Named by every entry of the id, directly or through the groups LDAP nests in it, named surely the same way. */
        final Set<String> surely = new HashSet<>();
        /** Named by any entry, or what a member value could name — users, and the members of groups — down through. */
        final Set<String> possibly = new HashSet<>();
        /** A member value down through is a DN no search returned: it may be anyone's, or a group that names anyone. */
        boolean outside;
        /** The members of a group down through were not read, or a value could not be parsed: anyone could be named. */
        boolean anyone;
    }

    /**
     * Whom LDAP names in each group of this sync, counted once for the first step and the main loop alike (batch 14 —
     * one table, where each used its own and the main loop's memo kept what a cut cycle left half done). Surely: whom
     * every entry of the id names, its members the sync named and those the groups it nests name surely — the least
     * fixpoint, so a nesting cycle names no one it would not name otherwise; an entry whose members were not read names
     * surely none but those it returned (none, as the connector reads them). Possibly: any entry's members, the users a
     * value could name ({@code possibleUserIds}), and the same of every group a value names or could name
     * ({@code ambiguousGroupIds}), down through; outside and anyone likewise. What the sync could not name or read is not
     * taken for "no one" (batch 13 review round 1). Where nested groups are not synced, a group's membership is its
     * direct members alone, and nothing is followed down.
     */
    private static Map<String, Naming> namings(Map<String, List<LdapGroup>> entriesOf, String groupPrefix,
            String userPrefix, boolean followNesting) {
        Map<String, Naming> own = new HashMap<>();
        Map<String, Set<String>> down = new HashMap<>();
        for (Map.Entry<String, List<LdapGroup>> e : entriesOf.entrySet()) {
            Naming naming = new Naming();
            Set<String> next = new LinkedHashSet<>();
            for (LdapGroup entry : e.getValue()) {
                if (entry.isMembersRanged() || !entry.isMemberAttributePresent()) {
                    naming.anyone = true;
                }
                for (String userId : entry.getMemberUserIds() == null ? List.<String>of() : entry.getMemberUserIds()) {
                    naming.possibly.add(userPrefix + userId);
                }
                for (String userId : entry.getPossibleUserIds() == null ? List.<String>of() : entry.getPossibleUserIds()) {
                    naming.possibly.add(userPrefix + userId);
                }
                naming.outside |= entry.isOutsideCouldBeNamed();
                naming.anyone |= entry.isAnyGroupCouldBeNamed();
                if (followNesting) {
                    for (String nestedId : memberGroupIdsOf(entry)) {
                        next.add(groupPrefix + nestedId);
                    }
                    for (String nestedId : entry.getAmbiguousGroupIds() == null ? List.<String>of() : entry.getAmbiguousGroupIds()) {
                        next.add(groupPrefix + nestedId);
                    }
                }
            }
            own.put(e.getKey(), naming);
            down.put(e.getKey(), next);
        }
        // The groups as strongly connected components, the ones below first: a component is counted once, after every
        // component it reaches.
        List<List<String>> components = components(down);
        Map<String, Naming> namings = new HashMap<>();
        for (List<String> component : components) {
            Naming merged = new Naming();
            for (String groupId : component) {
                Naming mine = own.get(groupId);
                merged.possibly.addAll(mine.possibly);
                merged.outside |= mine.outside;
                merged.anyone |= mine.anyone;
                for (String d : down.get(groupId)) {
                    Naming below = namings.get(d);
                    if (below != null) {
                        merged.possibly.addAll(below.possibly);
                        merged.outside |= below.outside;
                        merged.anyone |= below.anyone;
                    }
                }
            }
            for (String groupId : component) {
                Naming naming = new Naming();
                naming.possibly.addAll(merged.possibly);
                naming.outside = merged.outside;
                naming.anyone = merged.anyone;
                namings.put(groupId, naming);
            }
            // Surely: from nothing, within the component, until nothing changes — each step only adds.
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String groupId : component) {
                    Set<String> common = null;
                    for (LdapGroup entry : entriesOf.get(groupId)) {
                        Set<String> named = new HashSet<>();
                        for (String userId : entry.getMemberUserIds() == null ? List.<String>of() : entry.getMemberUserIds()) {
                            named.add(userPrefix + userId);
                        }
                        if (followNesting) {
                            for (String nestedId : memberGroupIdsOf(entry)) {
                                named.addAll(namings.get(groupPrefix + nestedId).surely);
                            }
                        }
                        if (common == null) {
                            common = named;
                        } else {
                            common.retainAll(named);
                        }
                    }
                    if (common != null && namings.get(groupId).surely.addAll(common)) {
                        changed = true;
                    }
                }
            }
        }
        return namings;
    }

    /** Tarjan's strongly connected components of the graph, each after every component it reaches. */
    private static List<List<String>> components(Map<String, Set<String>> edges) {
        List<List<String>> out = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        for (String root : edges.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            // Iterative, so a deep nesting does not overflow the call stack.
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[] {root, edges.getOrDefault(root, Set.of()).iterator()});
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.push(root);
            onStack.add(root);
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                @SuppressWarnings("unchecked")
                Iterator<String> it = (Iterator<String>) frame[1];
                if (it.hasNext()) {
                    String w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Object[] {w, edges.getOrDefault(w, Set.of()).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        String parent = (String) work.peek()[0];
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                    if (low.get(v).equals(index.get(v))) {
                        List<String> component = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            component.add(w);
                        } while (!w.equals(v));
                        out.add(component);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether LDAP may name the user in the group, though not surely (R157, batch 14): a member value the sync could not
     * name or read could be anyone — a DN outside the searches may be a group that names a user this sync looked up — or
     * could be her, written differently. Where there is a user prefix, an id without it is no directory user's; an id
     * no NemakiWare user has loses nothing.
     */
    private boolean couldBeNamed(String id, UserState state, Naming named, String userPrefix, String repositoryId,
            Map<String, Boolean> couldBeUsers) {
        if (!named.anyone && !named.outside && !named.possibly.contains(id)) {
            return false;
        }
        if (state != null) {
            return true;
        }
        if (!userPrefix.isEmpty() && !id.startsWith(userPrefix)) {
            return false;
        }
        return couldBeUsers.computeIfAbsent(id, k -> couldBeAUser(repositoryId, k));
    }

    /** Whether a NemakiWare user may have the id: one does, or whether one does could not be read. */
    private boolean couldBeAUser(String repositoryId, String id) {
        try {
            return contentService.getUserItemById(repositoryId, id) != null;
        } catch (Exception unread) {
            return true;
        }
    }

    /**
     * Writes into a group, as direct members, the users a removal or a write below it would take it from and that LDAP
     * surely names in it — before either, from the group read past the cache, checked by reading it back (R154, R156).
     * They reach it already, so no one gains anything; a group the sync does not write keeps them as it keeps its other
     * members, until it can be written. The preview projects it. Answers whom it could not put back (none, when done).
     */
    private List<String> putBack(String repositoryId, String groupId, List<String> users, boolean dryRun,
            DirectorySyncResult result, Map<String, GroupItem> syncedGroupMap) {
        if (dryRun) {
            GroupItem stored = syncedGroupMap.get(groupId);
            List<String> with = new ArrayList<>(stored.getUsers() == null ? List.of() : stored.getUsers());
            for (String id : users) {
                if (!with.contains(id)) {
                    with.add(id);
                }
            }
            syncedGroupMap.put(groupId, new GroupItem(stored.getId(), stored.getObjectType(), stored.getGroupId(),
                    stored.getName(), with, stored.getGroups() == null ? new ArrayList<>() : new ArrayList<>(stored.getGroups())));
            result.addWarning(groupId, "group " + groupId + ": " + users.size() + " member(s) LDAP names in the group, "
                    + "who reach it through a group below it that this sync changes, were put back as members first, "
                    + "for example " + sample(users));
            return List.of();
        }
        String failure = null;
        GroupItem after = null;
        try {
            GroupItem fresh = contentService.getGroupItemByIdFresh(repositoryId, groupId);
            if (fresh == null) {
                failure = "the group could not be read";
            } else {
                List<String> with = new ArrayList<>(fresh.getUsers() == null ? List.of() : fresh.getUsers());
                for (String id : users) {
                    if (!with.contains(id)) {
                        with.add(id);
                    }
                }
                fresh.setUsers(with);
                fresh.setModifier("system");
                fresh.setModified(new GregorianCalendar());
                try {
                    contentService.update(new SystemCallContext(repositoryId), repositoryId, fresh);
                } catch (Exception e) {
                    failure = e.getMessage();
                }
            }
            after = contentService.getGroupItemByIdFresh(repositoryId, groupId);
        } catch (Exception e) {
            failure = (failure == null ? "" : failure + "; ") + e.getMessage();
            after = null;
        }
        List<String> missing = new ArrayList<>(users);
        if (after != null) {
            missing.removeAll(after.getUsers() == null ? List.of() : after.getUsers());
            syncedGroupMap.put(groupId, after);
        }
        if (after == null || !missing.isEmpty()) {
            List<String> notPutBack = after == null ? new ArrayList<>(users) : missing;
            result.addError(groupId, "group " + groupId + ": " + notPutBack.size() + " member(s) LDAP names in the group, "
                    + "who reach it through a group below it that this sync changes, could not be put back as members"
                    + (failure == null ? "" : " (" + failure + ")") + ", so the changes below that would take the group "
                    + "from them are not made, for example " + sample(notPutBack));
            return notPutBack;
        }
        result.addWarning(groupId, "group " + groupId + ": " + users.size() + " member(s) LDAP names in the group, "
                + "who reach it through a group below it that this sync changes, were put back as members first, "
                + "for example " + sample(users) + (failure == null ? "" : " (a step after storing the group failed: "
                + failure + ")"));
        return List.of();
    }

    /**
     * Everything below a nesting that could not be checked or removed and is still stored — groups NemakiWare
     * holds, local ones between, and ids no group has yet — each with the group whose nesting holds it back.
     */
    private static Map<String, String> withheld(Map<String, Set<String>> uncheckedEdges,
            Map<String, List<String>> storedNesting) {
        Map<String, String> withheldBy = new HashMap<>();
        for (Map.Entry<String, Set<String>> root : uncheckedEdges.entrySet()) {
            List<String> stillNested = storedNesting.getOrDefault(root.getKey(), List.of());
            for (String nestedId : root.getValue()) {
                if (!stillNested.contains(nestedId)) {
                    continue;
                }
                Deque<String> todo = new ArrayDeque<>();
                todo.push(nestedId);
                while (!todo.isEmpty()) {
                    String id = todo.pop();
                    if (withheldBy.putIfAbsent(id, root.getKey()) == null) {
                        for (String child : storedNesting.getOrDefault(id, List.of())) {
                            todo.push(child);
                        }
                    }
                }
            }
        }
        return withheldBy;
    }

    /**
     * The stored nesting follows a group this sync wrote (or, in the preview, would write); where that lets go
     * of a nesting that held groups back, the groups written after it are not held back by it (R151, review
     * round 1). Without nested sync the nesting is not written, and nothing is held back.
     */
    private static Map<String, String> nestingWritten(String groupId, Set<String> writtenGroups,
            Map<String, List<String>> storedNesting, Map<String, Set<String>> uncheckedEdges,
            Map<String, String> withheldBy) {
        if (writtenGroups == null || uncheckedEdges.isEmpty()) {
            return withheldBy;
        }
        List<String> before = storedNesting.getOrDefault(groupId, List.of());
        if (new HashSet<>(before).equals(writtenGroups)) {
            return withheldBy;
        }
        storedNesting.put(groupId, new ArrayList<>(writtenGroups));
        if (!uncheckedEdges.containsKey(groupId) && !withheldBy.containsKey(groupId)) {
            return withheldBy;
        }
        return withheld(uncheckedEdges, storedNesting);
    }

    /** Whether the group, read back past the cache, holds what was written (R152) — its name too (review round 1). */
    private boolean storedAsWritten(String repositoryId, String groupId, String name, Set<String> users,
            Set<String> groups) {
        try {
            GroupItem stored = contentService.getGroupItemByIdFresh(repositoryId, groupId);
            if (stored == null || users == null || !Objects.equals(stored.getName(), name)) {
                return false;
            }
            if (!new HashSet<>(stored.getUsers() == null ? List.of() : stored.getUsers()).equals(users)) {
                return false;
            }
            return groups == null || new HashSet<>(stored.getGroups() == null ? List.of() : stored.getGroups()).equals(groups);
        } catch (Exception e) {
            log.warn("Directory sync: group " + groupId + " could not be read back after a failed write: " + e.getMessage());
            return false;
        }
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

    private boolean hasGroupChanges(GroupItem existingGroup, LdapGroup ldapGroup, String groupPrefix, DirectorySyncConfig config,
            Set<String> keptNesting) {
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
            ldapSubGroupsWithPrefix.addAll(keptNesting);

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

    private void updateGroup(String repositoryId, GroupItem existingGroup, LdapGroup ldapGroup, String groupPrefix,
            DirectorySyncConfig config, Set<String> keptNesting) {
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

        // Nestings LDAP took out that a group above needs kept are written as they are stored (batch 14 review round 1).
        for (String kept : keptNesting) {
            if (!subGroups.contains(kept)) {
                subGroups.add(kept);
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
