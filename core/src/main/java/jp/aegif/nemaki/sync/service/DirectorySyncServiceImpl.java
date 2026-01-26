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
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
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
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.model.DirectorySyncResult.SyncStatus;
import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;
import jp.aegif.nemaki.sync.util.PasswordEncryptionUtil;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

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

                LdapDirectoryConnector connector = new LdapDirectoryConnector(config);
                
                try {
                    connector.connect();
                    
                    List<LdapUser> ldapUsers = connector.searchUsers();
                    performUserSync(repositoryId, ldapUsers, config, result, dryRun);
                    
                    List<LdapGroup> ldapGroups = connector.searchGroups();
                    performGroupSync(repositoryId, ldapGroups, config, result, dryRun);
                    
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

    private void performUserSync(String repositoryId, List<LdapUser> ldapUsers,
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun) {
        
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
            
            try {
                UserItem existingUser = contentService.getUserItemById(repositoryId, nemakiUserId);
                
                if (existingUser == null) {
                    if (config.isCreateMissingUsers()) {
                        if (!dryRun) {
                            createUser(repositoryId, ldapUser, userPrefix, config);
                            log.info("AUDIT: User created via directory sync: " + nemakiUserId);
                        }
                        result.incrementUsersAdded();
                        log.debug("User created: " + nemakiUserId);
                    } else {
                        result.incrementUsersSkipped();
                        log.debug("User skipped (createMissingUsers=false): " + nemakiUserId);
                    }
                } else {
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
                            removeUserFromAllGroups(repositoryId, userId);
                            contentService.delete(new SystemCallContext(repositoryId), repositoryId, existingUser.getId(), false);
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
    }

    private void removeUserFromAllGroups(String repositoryId, String userId) {
        List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
        if (allGroups == null) {
            return;
        }
        for (GroupItem group : allGroups) {
            List<String> users = group.getUsers();
            if (users != null && users.contains(userId)) {
                List<String> updatedUsers = new ArrayList<>(users);
                updatedUsers.remove(userId);
                group.setUsers(updatedUsers);
                group.setModifier("system");
                group.setModified(new GregorianCalendar());
                contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
                log.debug("Removed user " + userId + " from group " + group.getGroupId());
            }
        }
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
        
        contentService.update(new SystemCallContext(repositoryId), repositoryId, existingUser);
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

    private void performGroupSync(String repositoryId, List<LdapGroup> ldapGroups, 
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun) {
        
        String configPrefix = config.getGroupPrefix();
        final String groupPrefix = (configPrefix == null) ? DirectorySyncConfig.DEFAULT_GROUP_PREFIX : configPrefix;

        List<GroupItem> existingGroups = contentService.getGroupItems(repositoryId);
        if (existingGroups == null) {
            existingGroups = new ArrayList<>();
        }
        
        Map<String, GroupItem> syncedGroupMap = existingGroups.stream()
                .filter(g -> g.getGroupId() != null && g.getGroupId().startsWith(groupPrefix))
                .collect(Collectors.toMap(GroupItem::getGroupId, g -> g));

        Map<String, LdapGroup> ldapGroupMap = new HashMap<>();
        for (LdapGroup lg : ldapGroups) {
            String nemakiGroupId = groupPrefix + lg.getGroupId();
            ldapGroupMap.put(nemakiGroupId, lg);
        }

        for (LdapGroup ldapGroup : ldapGroups) {
            String nemakiGroupId = groupPrefix + ldapGroup.getGroupId();
            
            try {
                if (syncedGroupMap.containsKey(nemakiGroupId)) {
                    GroupItem existingGroup = syncedGroupMap.get(nemakiGroupId);
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
                            contentService.delete(new SystemCallContext(repositoryId), repositoryId, existingGroup.getId(), false);
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
            Set<String> existingSubGroups = new HashSet<>();
            if (existingGroup.getGroups() != null) {
                for (String g : existingGroup.getGroups()) {
                    if (g.startsWith(groupPrefix)) {
                        existingSubGroups.add(g.substring(groupPrefix.length()));
                    } else {
                        existingSubGroups.add(g);
                    }
                }
            }
            Set<String> ldapSubGroups = new HashSet<>(ldapGroup.getMemberGroupIds() != null ? ldapGroup.getMemberGroupIds() : new ArrayList<>());
            
            if (!existingSubGroups.equals(ldapSubGroups)) {
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
        existingGroup.setGroups(subGroups);
        existingGroup.setModifier("system");
        existingGroup.setModified(new GregorianCalendar());
        
        contentService.update(new SystemCallContext(repositoryId), repositoryId, existingGroup);
    }

    private Folder getOrCreateGroupsFolder(String repositoryId) {
        Folder systemFolder = getSystemFolderWithFallback(repositoryId);

        // Check for existing groups folder
        List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
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
        
        LdapDirectoryConnector connector = new LdapDirectoryConnector(config);
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
