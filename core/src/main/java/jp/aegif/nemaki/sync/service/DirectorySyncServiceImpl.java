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

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.naming.NamingException;

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
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class DirectorySyncServiceImpl implements DirectorySyncService {

    private static final Log log = LogFactory.getLog(DirectorySyncServiceImpl.class);

    private ContentService contentService;
    private PropertyManager propertyManager;
    
    private final Map<String, DirectorySyncResult> lastSyncResults = new ConcurrentHashMap<>();
    private final Object syncLock = new Object();

    @Override
    public DirectorySyncResult syncGroups(String repositoryId, boolean dryRun) {
        synchronized (syncLock) {
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

                log.info("Starting directory sync for repository: " + repositoryId + " (dryRun=" + dryRun + ")");

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

                if (!dryRun) {
                    config.setLastSyncTime(new GregorianCalendar());
                    config.setLastSyncStatus(result.getStatus().name());
                }

                log.info("Directory sync completed: " +
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
                            createUser(repositoryId, ldapUser, userPrefix);
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
            for (UserItem existingUser : existingUsers) {
                String userId = existingUser.getUserId();
                if (userId != null && userId.startsWith(userPrefix) && !ldapUserIds.contains(userId)) {
                    try {
                        if (!dryRun) {
                            contentService.delete(new SystemCallContext(repositoryId), repositoryId, existingUser.getId(), false);
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

    private void createUser(String repositoryId, LdapUser ldapUser, String userPrefix) {
        Folder usersFolder = getOrCreateUsersFolder(repositoryId);
        if (usersFolder == null) {
            throw new RuntimeException("Failed to get or create users folder");
        }

        String randomPassword = UUID.randomUUID().toString();
        String passwordHash = BCrypt.hashpw(randomPassword, BCrypt.gensalt());

        String displayName = ldapUser.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = ldapUser.getUserId();
        }

        String nemakiUserId = userPrefix + ldapUser.getUserId();

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

    private Folder getOrCreateUsersFolder(String repositoryId) {
        Folder systemFolder = contentService.getSystemFolder(repositoryId);
        if (systemFolder == null) {
            log.error("System folder not found");
            return null;
        }

        List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        if (children != null) {
            for (Content child : children) {
                if ("users".equals(child.getName()) && child instanceof Folder) {
                    return (Folder) child;
                }
            }
        }

        return null;
    }

    private void performGroupSync(String repositoryId, List<LdapGroup> ldapGroups, 
            DirectorySyncConfig config, DirectorySyncResult result, boolean dryRun) {
        
        String configPrefix = config.getGroupPrefix();
        final String groupPrefix = (configPrefix == null) ? "ldap_" : configPrefix;

        List<GroupItem> existingGroups = contentService.getGroupItems(repositoryId);
        
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
        Set<String> existingUsers = new HashSet<>(existingGroup.getUsers() != null ? existingGroup.getUsers() : new ArrayList<>());
        Set<String> ldapUsers = new HashSet<>(ldapGroup.getMemberUserIds() != null ? ldapGroup.getMemberUserIds() : new ArrayList<>());
        
        if (!existingUsers.equals(ldapUsers)) {
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
        
        List<String> users = ldapGroup.getMemberUserIds() != null ? 
                new ArrayList<>(ldapGroup.getMemberUserIds()) : new ArrayList<>();
        
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
        List<String> users = ldapGroup.getMemberUserIds() != null ? 
                new ArrayList<>(ldapGroup.getMemberUserIds()) : new ArrayList<>();
        
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
        Folder systemFolder = contentService.getSystemFolder(repositoryId);
        if (systemFolder == null) {
            log.error("System folder not found");
            return null;
        }

        List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
        if (children != null) {
            for (Content child : children) {
                if ("groups".equals(child.getName()) && child instanceof Folder) {
                    return (Folder) child;
                }
            }
        }

        return null;
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
        config.setLdapBindPassword(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BIND_PASSWORD));
        config.setUseTls(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_LDAP_USE_TLS));
        config.setUseStartTls(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_LDAP_USE_STARTTLS));
        
        String connTimeout = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_CONNECTION_TIMEOUT);
        if (connTimeout != null && !connTimeout.isEmpty()) {
            config.setConnectionTimeout(Integer.parseInt(connTimeout));
        }
        String readTimeout = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_READ_TIMEOUT);
        if (readTimeout != null && !readTimeout.isEmpty()) {
            config.setReadTimeout(Integer.parseInt(readTimeout));
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
        config.setGroupPrefix(groupPrefix != null && !groupPrefix.isEmpty() ? groupPrefix : "ldap_");
        
        String userPrefix = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX);
        config.setUserPrefix(userPrefix != null ? userPrefix : "");
        
        config.setScheduleEnabled(propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_SCHEDULE_ENABLED));
        config.setCronExpression(propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_CRON));
        
        return config;
    }

    @Override
    public void saveConfig(String repositoryId, DirectorySyncConfig config) {
        log.info("Config save requested - configuration is managed via nemakiware.properties");
    }

    @Override
    public boolean testConnection(String repositoryId) {
        DirectorySyncConfig config = getConfig(repositoryId);
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
}
