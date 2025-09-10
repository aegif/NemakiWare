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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.ErrorCode;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.DateUtil;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Spring @RestController for User Management API
 * Replaces Jersey-based UserItemResource with full Spring DI support
 */
@RestController
@RequestMapping("/api/v1/repo/{repositoryId}/users")
@CrossOrigin(origins = "*", maxAge = 3600)
public class UserController {

    private ContentService contentService;
    
    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        // Fallback to manual Spring context lookup
        return jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }

    /**
     * Get all users in repository
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listUsers(@PathVariable String repositoryId) {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> userList = new ArrayList<>();
        
        try {
            List<UserItem> users = getContentService().getUserItems(repositoryId);
            
            for (UserItem user : users) {
                Map<String, Object> userMap = convertUserToMap(user);
                userList.add(userMap);
            }
            
            response.put("status", "success");
            response.put("users", userList);
            response.put("count", userList.size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to retrieve users");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get specific user by ID
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(
            @PathVariable String repositoryId, 
            @PathVariable String userId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            UserItem user = getContentService().getUserItemById(repositoryId, userId);
            
            if (user == null) {
                response.put("status", "error");
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            response.put("status", "success");
            response.put("user", convertUserToMap(user));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to retrieve user");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Create new user
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(
            @PathVariable String repositoryId,
            @RequestParam String userId,
            @RequestParam String name,
            @RequestParam String password,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String email) {
        
        Map<String, Object> response = new HashMap<>();
        List<String> errors = new ArrayList<>();
        
        // Validation
        if (StringUtils.isBlank(userId)) {
            errors.add("User ID is required");
        }
        if (StringUtils.isBlank(name)) {
            errors.add("User name is required");
        }
        if (StringUtils.isBlank(password)) {
            errors.add("Password is required");
        }
        
        // Check if user already exists
        if (StringUtils.isNotBlank(userId)) {
            try {
                UserItem existingUser = getContentService().getUserItemById(repositoryId, userId);
                if (existingUser != null) {
                    errors.add("User ID already exists");
                }
            } catch (Exception e) {
                // User doesn't exist, which is good for creation
            }
        }
        
        if (!errors.isEmpty()) {
            response.put("status", "error");
            response.put("message", "Validation failed");
            response.put("errors", errors);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        
        try {
            // Generate password hash
            String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
            
            // Get or create users folder
            Folder usersFolder = getOrCreateSystemSubFolder(repositoryId, "users");
            
            // Create user object
            UserItem user = new UserItem(null, NemakiObjectType.nemakiUser, userId, name, passwordHash, false, usersFolder.getId());
            
            // Set additional properties
            Map<String, Object> props = new HashMap<>();
            if (firstName != null) props.put("nemaki:firstName", firstName);
            if (lastName != null) props.put("nemaki:lastName", lastName);
            if (email != null) props.put("nemaki:email", email);
            
            List<Property> properties = new ArrayList<>();
            for (String key : props.keySet()) {
                properties.add(new Property(key, props.get(key)));
            }
            user.setSubTypeProperties(properties);
            
            // Set creation metadata
            user.setCreator("system");
            user.setModifier("system");
            GregorianCalendar now = new GregorianCalendar();
            user.setCreated(now);
            user.setModified(now);
            
            // Create user in repository
            getContentService().createUserItem(new SystemCallContext(repositoryId), repositoryId, user);
            
            response.put("status", "success");
            response.put("message", "User created successfully");
            response.put("user", convertUserToMap(user));
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to create user");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Update existing user
     */
    @PutMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable String repositoryId,
            @PathVariable String userId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String password) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            UserItem user = getContentService().getUserItemById(repositoryId, userId);
            
            if (user == null) {
                response.put("status", "error");
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Update basic properties
            if (StringUtils.isNotBlank(name)) {
                user.setName(name);
            }
            
            // Update extended properties
            Map<String, Object> propMap = new HashMap<>();
            for (Property prop : user.getSubTypeProperties()) {
                propMap.put(prop.getKey(), prop.getValue());
            }
            
            if (firstName != null) propMap.put("nemaki:firstName", firstName);
            if (lastName != null) propMap.put("nemaki:lastName", lastName);
            if (email != null) propMap.put("nemaki:email", email);
            
            List<Property> properties = new ArrayList<>();
            for (String key : propMap.keySet()) {
                properties.add(new Property(key, propMap.get(key)));
            }
            user.setSubTypeProperties(properties);
            
            // Update password if provided
            if (StringUtils.isNotBlank(password)) {
                String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
                user.setPassowrd(passwordHash);
            }
            
            // Set modification metadata
            user.setModifier("system");
            user.setModified(new GregorianCalendar());
            
            // Update user in repository
            getContentService().update(new SystemCallContext(repositoryId), repositoryId, user);
            
            response.put("status", "success");
            response.put("message", "User updated successfully");
            response.put("user", convertUserToMap(user));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to update user");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Delete user
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable String repositoryId,
            @PathVariable String userId) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            UserItem user = getContentService().getUserItemById(repositoryId, userId);
            
            if (user == null) {
                response.put("status", "error");
                response.put("message", "User not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            
            // Delete user from repository
            getContentService().delete(new SystemCallContext(repositoryId), repositoryId, user.getId(), false);
            
            response.put("status", "success");
            response.put("message", "User deleted successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to delete user");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Convert UserItem to Map for JSON response
     */
    private Map<String, Object> convertUserToMap(UserItem user) {
        Map<String, Object> userMap = new HashMap<>();
        
        userMap.put("userId", user.getUserId());
        userMap.put("name", user.getName());
        userMap.put("type", user.getType());
        userMap.put("creator", user.getCreator());
        userMap.put("modifier", user.getModifier());
        userMap.put("isAdmin", user.isAdmin() != null ? user.isAdmin() : false);
        
        // Format dates
        if (user.getCreated() != null) {
            userMap.put("created", DateUtil.formatSystemDateTime(user.getCreated()));
        }
        if (user.getModified() != null) {
            userMap.put("modified", DateUtil.formatSystemDateTime(user.getModified()));
        }
        
        // Extract extended properties
        Map<String, Object> extProps = new HashMap<>();
        for (Property prop : user.getSubTypeProperties()) {
            extProps.put(prop.getKey(), prop.getValue());
        }
        
        userMap.put("firstName", MapUtils.getObject(extProps, "nemaki:firstName", ""));
        userMap.put("lastName", MapUtils.getObject(extProps, "nemaki:lastName", ""));
        userMap.put("email", MapUtils.getObject(extProps, "nemaki:email", ""));
        userMap.put("favorites", MapUtils.getObject(extProps, "nemaki:favorites", new ArrayList<>()));
        
        return userMap;
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
