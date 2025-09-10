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
 *     Claude Code - Spring @RestController implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.DateUtil;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring @RestController for Group Management API
 * Replaces Jersey-based GroupItemResource with full Spring DI support
 */
@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/groups")
@CrossOrigin(origins = "*", maxAge = 3600)
public class GroupController {

    private ContentService contentService;

    private ObjectMapper objectMapper;
    
    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        // Fallback to manual Spring context lookup
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }
    
    private ObjectMapper getObjectMapper() {
        if (objectMapper != null) {
            return objectMapper;
        }
        // Fallback to manual Spring context lookup
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("nemakiObjectMapper", ObjectMapper.class);
    }

    /**
     * Get all groups in repository
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listGroups(@PathVariable String repositoryId) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> groupList = new ArrayList<>();
        
        try {
            List<GroupItem> groups = getContentService().getGroupItems(repositoryId);
            
            for (GroupItem group : groups) {
                Map<String, Object> groupMap = convertGroupToMap(group);
                groupList.add(groupMap);
            }
            
            response.put("status", "success");
            response.put("groups", groupList);
            response.put("count", groupList.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to retrieve groups");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get specific group by ID
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> getGroup(
            @PathVariable String repositoryId, 
            @PathVariable String groupId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
            
            if (group == null) {
                response.put("status", "error");
                response.put("message", "Group not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            response.put("status", "success");
            response.put("group", convertGroupToMap(group));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to retrieve group");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Create new group
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createGroup(
            @PathVariable String repositoryId,
            @RequestParam String groupId,
            @RequestParam String name,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String groups) {
        
        Map<String, Object> response = new HashMap<>();
        List<String> errors = new ArrayList<>();
        
        // Validation
        if (StringUtils.isBlank(groupId)) {
            errors.add("Group ID is required");
        }
        if (StringUtils.isBlank(name)) {
            errors.add("Group name is required");
        }
        
        // Check if group already exists
        if (StringUtils.isNotBlank(groupId)) {
            try {
                GroupItem existingGroup = getContentService().getGroupItemById(repositoryId, groupId);
                if (existingGroup != null) {
                    errors.add("Group ID already exists");
                }
            } catch (Exception e) {
                // Group doesn't exist, which is good for creation
            }
        }
        
        if (!errors.isEmpty()) {
            response.put("status", "error");
            response.put("message", "Validation failed");
            response.put("errors", errors);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        
        try {
            // Parse member lists
            List<String> userList = parseJsonArray(users);
            List<String> groupList = parseJsonArray(groups);
            
            // Get or create groups folder
            Folder groupsFolder = getOrCreateSystemSubFolder(repositoryId, "groups");
            
            // Create group object
            GroupItem group = new GroupItem(null, NemakiObjectType.nemakiGroup, groupId, name, userList, groupList);
            group.setParentId(groupsFolder.getId());
            
            // Set creation metadata
            group.setCreator("system");
            group.setModifier("system");
            GregorianCalendar now = new GregorianCalendar();
            group.setCreated(now);
            group.setModified(now);
            
            // Create group in repository
            getContentService().createGroupItem(new SystemCallContext(repositoryId), repositoryId, group);
            
            response.put("status", "success");
            response.put("message", "Group created successfully");
            response.put("group", convertGroupToMap(group));
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to create group");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Update existing group
     */
    @PutMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable String repositoryId,
            @PathVariable String groupId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String groups) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
            
            if (group == null) {
                response.put("status", "error");
                response.put("message", "Group not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Update properties
            if (StringUtils.isNotBlank(name)) {
                group.setName(name);
            }
            
            if (users != null) {
                List<String> userList = parseJsonArray(users);
                group.setUsers(userList);
            }
            
            if (groups != null) {
                List<String> groupList = parseJsonArray(groups);
                group.setGroups(groupList);
            }
            
            // Set modification metadata
            group.setModifier("system");
            group.setModified(new GregorianCalendar());
            
            // Update group in repository
            getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
            
            response.put("status", "success");
            response.put("message", "Group updated successfully");
            response.put("group", convertGroupToMap(group));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to update group");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Delete group
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> deleteGroup(
            @PathVariable String repositoryId,
            @PathVariable String groupId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
            
            if (group == null) {
                response.put("status", "error");
                response.put("message", "Group not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Delete group from repository
            getContentService().delete(new SystemCallContext(repositoryId), repositoryId, group.getId(), false);
            
            response.put("status", "success");
            response.put("message", "Group deleted successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to delete group");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Add members to group
     */
    @PostMapping("/{groupId}/members")
    public ResponseEntity<Map<String, Object>> addMembers(
            @PathVariable String repositoryId,
            @PathVariable String groupId,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String groups) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
            
            if (group == null) {
                response.put("status", "error");
                response.put("message", "Group not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Add users
            if (users != null) {
                List<String> newUsers = parseJsonArray(users);
                List<String> currentUsers = new ArrayList<>(group.getUsers());
                for (String userId : newUsers) {
                    if (!currentUsers.contains(userId)) {
                        // Validate user exists
                        UserItem user = getContentService().getUserItemById(repositoryId, userId);
                        if (user != null) {
                            currentUsers.add(userId);
                        }
                    }
                }
                group.setUsers(currentUsers);
            }
            
            // Add groups
            if (groups != null) {
                List<String> newGroups = parseJsonArray(groups);
                List<String> currentGroups = new ArrayList<>(group.getGroups());
                for (String newGroupId : newGroups) {
                    if (!currentGroups.contains(newGroupId) && !newGroupId.equals(groupId)) {
                        // Validate group exists
                        GroupItem targetGroup = getContentService().getGroupItemById(repositoryId, newGroupId);
                        if (targetGroup != null) {
                            currentGroups.add(newGroupId);
                        }
                    }
                }
                group.setGroups(currentGroups);
            }
            
            // Set modification metadata
            group.setModifier("system");
            group.setModified(new GregorianCalendar());
            
            // Update group in repository
            getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
            
            response.put("status", "success");
            response.put("message", "Members added successfully");
            response.put("group", convertGroupToMap(group));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to add members");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Remove members from group
     */
    @DeleteMapping("/{groupId}/members")
    public ResponseEntity<Map<String, Object>> removeMembers(
            @PathVariable String repositoryId,
            @PathVariable String groupId,
            @RequestParam(required = false) String users,
            @RequestParam(required = false) String groups) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            GroupItem group = getContentService().getGroupItemById(repositoryId, groupId);
            
            if (group == null) {
                response.put("status", "error");
                response.put("message", "Group not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Remove users
            if (users != null) {
                List<String> usersToRemove = parseJsonArray(users);
                List<String> currentUsers = new ArrayList<>(group.getUsers());
                currentUsers.removeAll(usersToRemove);
                group.setUsers(currentUsers);
            }
            
            // Remove groups
            if (groups != null) {
                List<String> groupsToRemove = parseJsonArray(groups);
                List<String> currentGroups = new ArrayList<>(group.getGroups());
                currentGroups.removeAll(groupsToRemove);
                group.setGroups(currentGroups);
            }
            
            // Set modification metadata
            group.setModifier("system");
            group.setModified(new GregorianCalendar());
            
            // Update group in repository
            getContentService().update(new SystemCallContext(repositoryId), repositoryId, group);
            
            response.put("status", "success");
            response.put("message", "Members removed successfully");
            response.put("group", convertGroupToMap(group));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to remove members");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Convert GroupItem to Map for JSON response
     */
    private Map<String, Object> convertGroupToMap(GroupItem group) {
        Map<String, Object> groupMap = new HashMap<>();
        
        groupMap.put("groupId", group.getGroupId());
        groupMap.put("name", group.getName());
        groupMap.put("type", group.getType());
        groupMap.put("creator", group.getCreator());
        groupMap.put("modifier", group.getModifier());
        
        // Format dates
        if (group.getCreated() != null) {
            groupMap.put("created", DateUtil.formatSystemDateTime(group.getCreated()));
        }
        if (group.getModified() != null) {
            groupMap.put("modified", DateUtil.formatSystemDateTime(group.getModified()));
        }
        
        // Member information
        List<String> users = group.getUsers() != null ? group.getUsers() : new ArrayList<>();
        List<String> groups = group.getGroups() != null ? group.getGroups() : new ArrayList<>();
        
        groupMap.put("users", users);
        groupMap.put("groups", groups);
        groupMap.put("userCount", users.size());
        groupMap.put("groupCount", groups.size());
        
        return groupMap;
    }

    /**
     * Parse JSON array string to List<String>
     */
    private List<String> parseJsonArray(String jsonString) {
        if (StringUtils.isBlank(jsonString)) {
            return new ArrayList<>();
        }
        
        try {
            return getObjectMapper().readValue(jsonString, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // If JSON parsing fails, try to split by comma as fallback
            if (jsonString.contains(",")) {
                List<String> result = new ArrayList<>();
                for (String item : jsonString.split(",")) {
                    String trimmed = item.trim();
                    if (StringUtils.isNotBlank(trimmed)) {
                        result.add(trimmed);
                    }
                }
                return result;
            }
            return new ArrayList<>();
        }
    }

    /**
     * Get or create system subfolder
     */
    private Folder getOrCreateSystemSubFolder(String repositoryId, String name) {
        Folder systemFolder = getContentService().getSystemFolder(repositoryId);
        
        // Check if folder already exists
        List<Content> children = getContentService().getChildren(repositoryId, systemFolder.getId());
        if (CollectionUtils.isNotEmpty(children)) {
            for (Content child : children) {
                if (ObjectUtils.equals(name, child.getName())) {
                    return (Folder) child;
                }
            }
        }
        
        // Create new folder
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:name", name));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
        
        return getContentService().createFolder(new SystemCallContext(repositoryId), repositoryId, properties, systemFolder, null, null, null, null);
    }
}