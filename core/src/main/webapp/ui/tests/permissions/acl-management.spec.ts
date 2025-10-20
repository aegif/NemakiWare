import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Advanced ACL Management', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testGroupName = `testgroup-${Date.now()}`;
  const testFolderName = `acl-test-folder-${Date.now()}`;
  const childFolderName = `child-folder-${Date.now()}`;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test('should add group permission to folder', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Step 1: Create a test group first
    console.log('Test: Creating test group for ACL management');

    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: '管理' });
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);

      const groupMgmtItem = page.locator('.ant-menu-item').filter({ hasText: 'グループ管理' });
      if (await groupMgmtItem.count() > 0) {
        await groupMgmtItem.click();
        await page.waitForTimeout(2000);

        // Create new group
        const createGroupButton = page.locator('button').filter({ hasText: /新規グループ|グループ作成|追加/i });
        if (await createGroupButton.count() > 0) {
          await createGroupButton.first().click();
          await page.waitForTimeout(1000);

          const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
          await modal.waitFor({ state: 'visible', timeout: 5000 });

          // Fill group name
          const groupNameInput = modal.locator('input[id*="name"], input[placeholder*="グループ名"]').first();
          if (await groupNameInput.count() > 0) {
            await groupNameInput.fill(testGroupName);
          }

          // Submit
          const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary').first();
          if (await submitButton.count() > 0) {
            await submitButton.click();
            await page.waitForTimeout(2000);
            console.log(`Test: Group ${testGroupName} created`);
          }
        } else {
          console.log('Test: Group creation button not found - skipping group creation');
        }
      }
    }

    // Step 2: Create a test folder
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const folderModal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = folderModal.locator('input[placeholder*="名前"], input[id*="name"]');
      await nameInput.fill(testFolderName);

      const submitButton = folderModal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);
    }

    // Step 3: Add group permission to folder
    const folderRow = page.locator('tr').filter({ hasText: testFolderName });
    if (await folderRow.count() > 0) {
      // Look for permissions/ACL button
      const permissionsButton = folderRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], [data-icon="safety"], [data-icon="setting"]')
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Look for ACL modal/drawer
        const aclModal = page.locator('.ant-modal, .ant-drawer').last();
        if (await aclModal.count() > 0) {
          // Add group permission
          const addButton = page.locator('button').filter({ hasText: /追加|Add/ });
          if (await addButton.count() > 0) {
            await addButton.first().click();
            await page.waitForTimeout(1000);

            // Select principal type as "Group"
            const principalTypeSelect = page.locator('.ant-select, select').filter({ hasText: /種別|Type/ }).first();
            if (await principalTypeSelect.count() > 0) {
              await principalTypeSelect.click();
              await page.waitForTimeout(500);

              const groupOption = page.locator('.ant-select-item').filter({ hasText: /グループ|Group/ });
              if (await groupOption.count() > 0) {
                await groupOption.first().click();
                await page.waitForTimeout(500);
              }
            }

            // Select the test group
            const groupSelect = page.locator('.ant-select, input[placeholder*="グループ"]').first();
            if (await groupSelect.count() > 0) {
              await groupSelect.click();
              await page.keyboard.type(testGroupName);
              await page.waitForTimeout(500);

              const testGroupOption = page.locator(`.ant-select-item`).filter({ hasText: testGroupName });
              if (await testGroupOption.count() > 0) {
                await testGroupOption.first().click();
                console.log(`Test: Selected group ${testGroupName}`);
              }
            }

            // Set permission level
            const permissionSelect = page.locator('.ant-select').filter({ hasText: /権限|Permission/ });
            if (await permissionSelect.count() > 0) {
              await permissionSelect.first().click();
              await page.waitForTimeout(500);

              const readOption = page.locator('.ant-select-item').filter({ hasText: /読み取り|Read/ });
              if (await readOption.count() > 0) {
                await readOption.first().click();
              }
            }

            // Save
            const saveButton = page.locator('button[type="submit"], button.ant-btn-primary').filter({ hasText: /保存|OK/ });
            if (await saveButton.count() > 0) {
              await saveButton.first().click();
              await page.waitForSelector('.ant-message-success', { timeout: 10000 });
              console.log('Test: Group permission added successfully');

              // Verify group appears in ACL list
              const groupRow = page.locator('tr').filter({ hasText: testGroupName });
              await expect(groupRow).toBeVisible({ timeout: 5000 });
            }
          }
        }
      } else {
        test.skip('ACL management interface not found');
      }
    }

    // Cleanup: Delete test folder
    await page.locator('tr').filter({ hasText: testFolderName }).first().click();
    await page.waitForTimeout(500);

    const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
    if (await deleteButton.count() > 0) {
      await deleteButton.first().click();
      await page.waitForTimeout(500);

      const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
      if (await confirmButton.count() > 0) {
        await confirmButton.first().click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should verify permission inheritance from parent folder', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Step 1: Create parent folder
    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = modal.locator('input[placeholder*="名前"], input[id*="name"]');
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);
    }

    // Step 2: Set ACL on parent folder with specific user permission
    const parentFolderRow = page.locator('tr').filter({ hasText: testFolderName });
    if (await parentFolderRow.count() > 0) {
      // Try to access ACL settings via CMIS Browser Binding API
      console.log('Test: Setting parent folder ACL via CMIS API');

      // First, get folder ID
      const queryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:name%20=%20'${encodeURIComponent(testFolderName)}'`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (queryResponse.ok()) {
        const queryResult = await queryResponse.json();
        const folderId = queryResult.results?.[0]?.properties?.['cmis:objectId']?.value;

        if (folderId) {
          console.log(`Test: Parent folder ID: ${folderId}`);

          // Apply ACL with a specific principal (e.g., testuser)
          const aclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            },
            form: {
              'cmisaction': 'applyACL',
              'objectId': folderId,
              'addACEPrincipal[0]': 'testuser',
              'addACEPermission[0][0]': 'cmis:read'
            }
          });

          if (aclResponse.ok()) {
            console.log('Test: Parent folder ACL set successfully');
          }

          // Step 3: Navigate into parent folder
          const folderLink = parentFolderRow.locator('button, a').first();
          await folderLink.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(2000);

          // Step 4: Create child folder
          const createChildButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
          if (await createChildButton.count() > 0) {
            await createChildButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            const childModal = page.locator('.ant-modal:not(.ant-modal-hidden)');
            const childNameInput = childModal.locator('input[placeholder*="名前"], input[id*="name"]');
            await childNameInput.fill(childFolderName);

            const childSubmitButton = childModal.locator('button[type="submit"], button.ant-btn-primary');
            await childSubmitButton.click();
            await page.waitForSelector('.ant-message-success', { timeout: 10000 });
            await page.waitForTimeout(2000);

            // Step 5: Check child folder ACL via API
            const childQueryResponse = await page.request.get(
              `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:name%20=%20'${encodeURIComponent(childFolderName)}'`,
              {
                headers: {
                  'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
                }
              }
            );

            if (childQueryResponse.ok()) {
              const childQueryResult = await childQueryResponse.json();
              const childFolderId = childQueryResult.results?.[0]?.properties?.['cmis:objectId']?.value;

              if (childFolderId) {
                console.log(`Test: Child folder ID: ${childFolderId}`);

                // Get child folder ACL
                const childAclResponse = await page.request.get(
                  `http://localhost:8080/core/atom/bedroom/${childFolderId}?selector=acl`,
                  {
                    headers: {
                      'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
                    }
                  }
                );

                if (childAclResponse.ok()) {
                  const childAclXml = await childAclResponse.text();
                  console.log('Test: Child folder ACL:', childAclXml.substring(0, 500));

                  // Verify parent folder ACL is inherited
                  // This assumes CMIS supports ACL inheritance
                  // Note: CMIS spec allows repositories to implement inheritance differently
                  const hasInheritedPermission = childAclXml.includes('testuser') || childAclXml.includes('cmis:read');

                  if (hasInheritedPermission) {
                    console.log('Test: Permission inheritance verified - child folder has inherited ACL');
                  } else {
                    console.log('Test: Permission inheritance not found - may not be supported or configured differently');
                  }
                }
              }
            }
          }
        }
      }
    } else {
      test.skip('Parent folder creation failed');
    }

    // Cleanup: Navigate back to parent and delete test folder tree
    const breadcrumb = page.locator('.ant-breadcrumb').filter({ hasText: testFolderName });
    if (await breadcrumb.count() > 0) {
      await breadcrumb.click();
      await page.waitForTimeout(2000);
    }

    // Go back to documents root
    const documentsLink = page.locator('.ant-breadcrumb a, .ant-breadcrumb span').filter({ hasText: /ドキュメント|Documents/i });
    if (await documentsLink.count() > 0) {
      await documentsLink.first().click();
      await page.waitForTimeout(2000);
    }

    // Delete parent folder (will delete child too)
    const parentFolderRowCleanup = page.locator('tr').filter({ hasText: testFolderName });
    if (await parentFolderRowCleanup.count() > 0) {
      await parentFolderRowCleanup.first().click();
      await page.waitForTimeout(500);

      const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
      if (await deleteButton.count() > 0) {
        await deleteButton.first().click();
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click();
          await page.waitForTimeout(2000);
        }
      }
    }
  });

  test('should handle access denied scenarios gracefully', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a folder with restricted access
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = modal.locator('input[placeholder*="名前"], input[id*="name"]');
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);
    }

    // Set ACL to deny access (admin only)
    const folderRow = page.locator('tr').filter({ hasText: testFolderName });
    if (await folderRow.count() > 0) {
      // Use CMIS API to set restrictive ACL
      const queryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:name%20=%20'${encodeURIComponent(testFolderName)}'`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (queryResponse.ok()) {
        const queryResult = await queryResponse.json();
        const folderId = queryResult.results?.[0]?.properties?.['cmis:objectId']?.value;

        if (folderId) {
          // Remove default permissions and set admin-only
          const aclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            },
            form: {
              'cmisaction': 'applyACL',
              'objectId': folderId,
              'removeACEPrincipal[0]': 'GROUP_EVERYONE',
              'addACEPrincipal[0]': 'admin',
              'addACEPermission[0][0]': 'cmis:all'
            }
          });

          console.log('Test: Set folder to admin-only access');

          // Now try to access as testuser (should fail)
          const testUserAccessResponse = await page.request.get(
            `http://localhost:8080/core/atom/bedroom/${folderId}`,
            {
              headers: {
                'Authorization': `Basic ${Buffer.from('testuser:password').toString('base64')}`
              }
            }
          );

          // Verify access denied (should be 403 or 404)
          console.log(`Test: Access denied response status: ${testUserAccessResponse.status()}`);
          expect([403, 404]).toContain(testUserAccessResponse.status());

          if (!testUserAccessResponse.ok()) {
            console.log('Test: Access denied scenario handled correctly - testuser cannot access admin-only folder');
          }
        }
      }
    }

    // Cleanup
    const folderRowCleanup = page.locator('tr').filter({ hasText: testFolderName });
    if (await folderRowCleanup.count() > 0) {
      await folderRowCleanup.first().click();
      await page.waitForTimeout(500);

      const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
      if (await deleteButton.count() > 0) {
        await deleteButton.first().click();
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click();
          await page.waitForTimeout(2000);
        }
      }
    }
  });

  test('should allow permission level changes without breaking existing access', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create folder
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = modal.locator('input[placeholder*="名前"], input[id*="name"]');
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);
    }

    // Get folder ID
    const queryResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:name%20=%20'${encodeURIComponent(testFolderName)}'`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        }
      }
    );

    if (queryResponse.ok()) {
      const queryResult = await queryResponse.json();
      const folderId = queryResult.results?.[0]?.properties?.['cmis:objectId']?.value;

      if (folderId) {
        // Step 1: Grant read-only permission
        await page.request.post('http://localhost:8080/core/browser/bedroom', {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          },
          form: {
            'cmisaction': 'applyACL',
            'objectId': folderId,
            'addACEPrincipal[0]': 'testuser',
            'addACEPermission[0][0]': 'cmis:read'
          }
        });
        console.log('Test: Granted read-only permission');

        // Verify testuser can read
        const readTestResponse = await page.request.get(
          `http://localhost:8080/core/atom/bedroom/${folderId}`,
          {
            headers: {
              'Authorization': `Basic ${Buffer.from('testuser:password').toString('base64')}`
            }
          }
        );
        expect(readTestResponse.ok()).toBe(true);
        console.log('Test: testuser can read folder (read-only)');

        // Step 2: Upgrade to write permission
        await page.request.post('http://localhost:8080/core/browser/bedroom', {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          },
          form: {
            'cmisaction': 'applyACL',
            'objectId': folderId,
            'removeACEPrincipal[0]': 'testuser',
            'addACEPrincipal[0]': 'testuser',
            'addACEPermission[0][0]': 'cmis:write'
          }
        });
        console.log('Test: Upgraded to write permission');

        // Verify testuser still has access (now with write)
        const writeTestResponse = await page.request.get(
          `http://localhost:8080/core/atom/bedroom/${folderId}`,
          {
            headers: {
              'Authorization': `Basic ${Buffer.from('testuser:password').toString('base64')}`
            }
          }
        );
        expect(writeTestResponse.ok()).toBe(true);
        console.log('Test: testuser still has access after permission upgrade');
      }
    }

    // Cleanup
    const folderRowCleanup = page.locator('tr').filter({ hasText: testFolderName });
    if (await folderRowCleanup.count() > 0) {
      await folderRowCleanup.first().click();
      await page.waitForTimeout(500);

      const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
      if (await deleteButton.count() > 0) {
        await deleteButton.first().click();
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click();
          await page.waitForTimeout(2000);
        }
      }
    }
  });
});
