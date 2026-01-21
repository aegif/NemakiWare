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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Patch for initializing test users and groups for QA and development
 * Creates:
 * - TestUser group
 * - test user (password: test) and adds to TestUser group
 */
public class Patch_TestUserInitialization extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_TestUserInitialization.class);

    @Override
    public String getName() {
        return "Test User and Group Initialization";
    }

    @Override
    protected void applySystemPatch() {
        // No system-wide patches needed for user initialization
        log.info("=== PATCH: TestUserInitialization - No system patches required ===");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== PATCH: Applying Test User Initialization for repository: " + repositoryId + " ===");
        
        try {
            // Get services via proper DI - these should be injected as fields
            PrincipalService principalService = SpringContext.getApplicationContext()
                    .getBean("PrincipalService", PrincipalService.class);
            ContentService contentService = SpringContext.getApplicationContext()
                    .getBean("ContentService", ContentService.class);
            
            if (principalService == null || contentService == null) {
                log.error("Required services not available - skipping test user initialization");
                return;
            }
            
            // Create TestUser group if it doesn't exist
            createTestUserGroup(repositoryId, principalService, contentService);
            
            // Create test user if it doesn't exist
            createTestUser(repositoryId, principalService, contentService);
            
            log.info("=== PATCH: Test User Initialization completed successfully ===");
            
        } catch (Exception e) {
            log.warn("Error during test user initialization: " + e.getMessage());
            // Don't fail the entire patch process due to test user initialization issues
        }
    }
    
    private void createTestUserGroup(String repositoryId, PrincipalService principalService, ContentService contentService) {
        try {
            // Check if TestUser group already exists
            List<GroupItem> existingGroups = contentService.getGroupItems(repositoryId);
            boolean testGroupExists = false;
            
            for (GroupItem group : existingGroups) {
                if ("testgroup".equals(group.getGroupId()) || "Test Group".equals(group.getName())) {
                    testGroupExists = true;
                    log.info("Test Group already exists - skipping creation");
                    break;
                }
            }
            
            if (!testGroupExists) {
                // Create TestUser group with proper initialization
                List<String> testUsers = new ArrayList<String>();
                testUsers.add("testuser"); // Add testuser to the test group
                List<String> emptyGroups = new ArrayList<String>();
                
                GroupItem testGroup = new GroupItem(null, NemakiObjectType.nemakiGroup, "testgroup", "Test Group", testUsers, emptyGroups);
                testGroup.setDescription("Test group for QA users and development");
                
                // Use ContentService to create the group
                GroupItem createdGroup = contentService.createGroupItem(new SystemCallContext(repositoryId), repositoryId, testGroup);
                String groupId = createdGroup.getId();
                log.info("Created QA Group with ID: " + groupId);
            } else {
                // Group exists, update it to include testuser if not already included  
                List<GroupItem> allGroups = contentService.getGroupItems(repositoryId);
                for (GroupItem group : allGroups) {
                    if ("testgroup".equals(group.getGroupId())) {
                        List<String> currentUsers = group.getUsers();
                        if (currentUsers == null) {
                            currentUsers = new ArrayList<String>();
                        }
                        if (!currentUsers.contains("testuser")) {
                            currentUsers.add("testuser");
                            group.setUsers(currentUsers);
                            
                            // Update the group
                            contentService.update(new SystemCallContext(repositoryId), repositoryId, group);
                            log.info("Updated testgroup to include testuser");
                        } else {
                            log.info("testuser is already in testgroup");
                        }
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to create QA group: " + e.getMessage(), e);
        }
    }
    
    private void createTestUser(String repositoryId, PrincipalService principalService, ContentService contentService) {
        try {
            // Check if test user already exists
            List<UserItem> existingUsers = contentService.getUserItems(repositoryId);
            boolean testUserExists = false;
            
            for (UserItem user : existingUsers) {
                if ("testuser".equals(user.getUserId())) {
                    testUserExists = true;
                    log.info("Test user already exists - skipping creation");
                    break;
                }
            }
            
            if (!testUserExists) {
                // Get users folder (required for UserItem creation)
                // Note: This requires ContentService to get system folder structure
                try {
                    // Create test user with proper constructor (password: test)
                    UserItem testUser = new UserItem(null, NemakiObjectType.nemakiUser, "testuser", "Test User", "test", false, null);
                    testUser.setDescription("Test user for QA and development");

                    // Set additional properties (firstName, lastName, email)
                    Map<String, Object> propsMap = new HashMap<>();
                    propsMap.put("nemaki:firstName", "Test");
                    propsMap.put("nemaki:lastName", "User");
                    propsMap.put("nemaki:email", "testuser@example.com");

                    List<Property> properties = new ArrayList<>();
                    for (String key : propsMap.keySet()) {
                        properties.add(new Property(key, propsMap.get(key)));
                    }

                    if (!properties.isEmpty()) {
                        testUser.setSubTypeProperties(properties);
                    }

                    // Note: Group membership will be handled by the group creation process
                    // UserItem doesn't have a setGroups method - groups are managed via GroupItem

                    // Use ContentService to create the user with properties
                    UserItem createdUser = contentService.createUserItem(new SystemCallContext(repositoryId), repositoryId, testUser);
                    String userId = createdUser.getId();
                    log.info("Created QA user with ID: " + userId + " with firstName: Test, lastName: User");

                } catch (Exception userCreationException) {
                    log.warn("Failed to create QA user (may already exist): " + userCreationException.getMessage());
                    // Continue with patch execution even if user creation fails
                }
            } else {
                log.info("Test user already exists - skipping creation but continuing with group membership verification");
            }
            
        } catch (Exception e) {
            log.warn("Error during test user initialization: " + e.getMessage());
            // Continue with patch execution - don't let user creation errors block group updates
        }
    }
}
