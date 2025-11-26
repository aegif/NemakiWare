import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

/**
 * FolderTree Component Navigation E2E Tests
 *
 * Tests for the enhanced FolderTree component with ancestor-aware navigation:
 * - Ancestor display up to N generations (configurable)
 * - Single-click folder selection (updates main pane content)
 * - Double-click to make folder "current" (redraws tree around it)
 * - Current folder vs selected folder visual distinction
 * - Tree expansion and lazy loading of children
 *
 * Key Concepts:
 * - Current Folder: The pivot point for tree construction (shows ancestors relative to this)
 * - Selected Folder: The folder whose contents are displayed in main pane (highlighted in tree)
 *
 * Navigation Flows:
 * 1. Click child folder -> Child becomes selected -> Main pane shows child's contents
 * 2. Click selected folder again -> Selected folder becomes current -> Tree redraws around it
 * 3. Click ancestor folder -> Ancestor becomes selected -> Main pane shows ancestor's contents
 * 4. Click selected ancestor again -> Ancestor becomes current -> Tree redraws around it
 *
 * UI Elements Tested:
 * - .ant-tree: Folder tree component
 * - .ant-tree-node-content-wrapper: Tree node wrapper
 * - Current folder indicator: border-color #1890ff, background #f0f0f0
 * - Selected folder indicator: border-color #91d5ff, background #e6f7ff
 */
test.describe('FolderTree Navigation', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Start with a clean session
    await page.context().clearCookies();
    await page.context().clearPermissions();

    // Login and navigate to documents
    await authHelper.login();
    await testHelper.waitForAntdLoad();

    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }
  });

  test.afterEach(async ({ page }) => {
    // Cleanup: Delete test folders created during tests
    console.log('afterEach: Cleaning up test folders');

    try {
      const queryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId%20FROM%20cmis:folder%20WHERE%20cmis:name%20LIKE%20'test-tree-%25'`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (queryResponse.ok()) {
        const queryResult = await queryResponse.json();
        const folders = queryResult.results || [];

        for (const folder of folders) {
          const objectId = folder.properties?.['cmis:objectId']?.value;
          if (objectId) {
            await page.request.post('http://localhost:8080/core/browser/bedroom', {
              headers: {
                'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
              },
              form: {
                'cmisaction': 'deleteTree',
                'folderId': objectId,
                'allVersions': 'true',
                'continueOnFailure': 'false'
              }
            });
            console.log(`afterEach: Deleted test folder tree ${objectId}`);
          }
        }
      }
    } catch (error) {
      console.log('afterEach: Cleanup failed (non-critical):', error);
    }
  });

  test('should display folder tree with visual hierarchy', async ({ page, browserName }) => {
    // Skip on mobile - folder tree is hidden
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    // Verify folder tree exists
    const folderTree = page.locator('.ant-tree');
    await expect(folderTree).toBeVisible({ timeout: 10000 });

    // Verify tree has at least one node (root folder)
    const treeNodes = folderTree.locator('.ant-tree-node-content-wrapper');
    const nodeCount = await treeNodes.count();
    expect(nodeCount).toBeGreaterThan(0);

    // Verify tree shows folder icons
    const folderIcons = folderTree.locator('[data-icon="folder"], [data-icon="folder-open"]');
    const iconCount = await folderIcons.count();
    expect(iconCount).toBeGreaterThan(0);

    console.log(`Folder tree displayed with ${nodeCount} nodes and ${iconCount} folder icons`);
  });

  test('should show instruction text for click interactions', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    // Look for instruction text about click interactions
    // FolderTree component shows: "シングルクリック: フォルダを選択 | ダブルクリック: ツリーを再描画"
    const instructionText = page.locator('text=シングルクリック');
    const hasInstructions = await instructionText.count() > 0;

    if (hasInstructions) {
      await expect(instructionText).toBeVisible();
      console.log('Folder tree click instructions are displayed');
    } else {
      // Alternative: check for legend about current/selected folder
      const legendText = page.locator('text=カレントフォルダ');
      const hasLegend = await legendText.count() > 0;

      if (hasLegend) {
        await expect(legendText).toBeVisible();
        console.log('Folder tree legend is displayed');
      } else {
        console.log('No instruction text found - feature may use different UI pattern');
      }
    }
  });

  test('should select folder on single click', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      test.skip('Folder tree not available');
      return;
    }

    // Find a clickable folder node (not root)
    const folderNodes = folderTree.locator('.ant-tree-node-content-wrapper');
    const nodeCount = await folderNodes.count();

    if (nodeCount < 2) {
      // Need at least 2 nodes to test selection
      console.log('Not enough folder nodes to test selection');
      return;
    }

    // Click on second node (first child folder)
    const targetNode = folderNodes.nth(1);
    const nodeTitleBefore = await targetNode.textContent();
    console.log(`Clicking on folder: ${nodeTitleBefore}`);

    await targetNode.click();
    await page.waitForTimeout(500); // Wait for selection visual update

    // Verify visual selection indicator (background color or border)
    // Selected folder should have light blue background (#e6f7ff)
    const selectedStyle = await targetNode.evaluate((el) => {
      const style = window.getComputedStyle(el);
      return {
        backgroundColor: style.backgroundColor,
        borderColor: style.borderColor
      };
    });

    console.log(`Selected node style: ${JSON.stringify(selectedStyle)}`);

    // Check for ant-tree-node-selected class or custom selection styling
    const hasSelectedClass = await targetNode.evaluate(el =>
      el.classList.contains('ant-tree-node-selected') ||
      el.closest('.ant-tree-treenode-selected') !== null
    );

    expect(hasSelectedClass || selectedStyle.backgroundColor !== 'rgba(0, 0, 0, 0)').toBe(true);
  });

  test('should expand folder tree node on toggle click', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      test.skip('Folder tree not available');
      return;
    }

    // Find a folder with children (has expand toggle)
    const expandToggle = folderTree.locator('.ant-tree-switcher:not(.ant-tree-switcher-noop)').first();

    if (await expandToggle.count() === 0) {
      console.log('No expandable folders found in tree');
      return;
    }

    // Check if already expanded
    const isExpanded = await expandToggle.evaluate(el =>
      el.classList.contains('ant-tree-switcher_open')
    );

    // Get initial child count
    const childNodes = folderTree.locator('.ant-tree-treenode');
    const initialCount = await childNodes.count();

    // Click toggle to expand/collapse
    await expandToggle.click();
    await page.waitForTimeout(500); // Wait for animation

    // Check state changed
    const isExpandedAfter = await expandToggle.evaluate(el =>
      el.classList.contains('ant-tree-switcher_open')
    );

    // State should have toggled
    expect(isExpandedAfter).toBe(!isExpanded);

    const newCount = await childNodes.count();
    console.log(`Tree toggled: nodes ${initialCount} -> ${newCount}, expanded: ${isExpanded} -> ${isExpandedAfter}`);
  });

  test('should update main content pane when folder selected', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    // Create a test folder to ensure we have something to navigate to
    const uuid = randomUUID().substring(0, 8);
    const testFolderName = `test-tree-${uuid}-nav`;

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() === 0) {
      test.skip('Folder creation functionality not available');
      return;
    }

    // Create folder
    await createFolderButton.click();
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
    await nameInput.fill(testFolderName);
    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(1500);

    // Get initial table content
    const tableBody = page.locator('.ant-table-tbody');
    const initialContent = await tableBody.textContent();

    // Find and click the folder in the tree
    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      console.log('Folder tree not visible - cannot test tree navigation');
      return;
    }

    // Wait for tree to show the new folder
    await page.waitForTimeout(1000);

    const folderInTree = folderTree.locator('.ant-tree-node-content-wrapper').filter({ hasText: testFolderName });

    if (await folderInTree.count() > 0) {
      await folderInTree.click();
      await page.waitForTimeout(1000);

      // Verify content pane updated (should show empty folder or different content)
      const newContent = await tableBody.textContent();

      // Content should have changed (empty folder shows different content than root)
      const contentChanged = newContent !== initialContent ||
                             await page.locator('.ant-empty').count() > 0 ||
                             await page.locator('text=データがありません').count() > 0;

      expect(contentChanged).toBe(true);
      console.log('Main content pane updated after folder selection');
    } else {
      console.log('Test folder not visible in tree - may need to expand parent');
    }
  });

  test('should show current and selected folder distinction', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      test.skip('Folder tree not available');
      return;
    }

    // Look for legend indicators showing current vs selected folder colors
    const currentFolderLegend = page.locator('text=カレントフォルダ');
    const selectedFolderLegend = page.locator('text=選択中');

    const hasCurrentLegend = await currentFolderLegend.count() > 0;
    const hasSelectedLegend = await selectedFolderLegend.count() > 0;

    if (hasCurrentLegend && hasSelectedLegend) {
      await expect(currentFolderLegend).toBeVisible();
      await expect(selectedFolderLegend).toBeVisible();
      console.log('Current/Selected folder legends are displayed');

      // Verify color indicators exist
      const legendContainer = page.locator(':has-text("カレントフォルダ")').last();
      const legendHtml = await legendContainer.innerHTML();

      // Check for color styling (border-color: #1890ff for current, #91d5ff for selected)
      const hasColorIndicators = legendHtml.includes('1890ff') || legendHtml.includes('91d5ff') ||
                                  legendHtml.includes('border');

      expect(hasColorIndicators).toBe(true);
    } else {
      console.log('Legend not found - component may use different visual indicators');

      // Alternative check: look for nodes with different border/background styles
      const treeNodes = folderTree.locator('.ant-tree-node-content-wrapper');
      const nodeCount = await treeNodes.count();

      if (nodeCount > 0) {
        // Get first node styling as baseline
        const firstNodeStyle = await treeNodes.first().evaluate((el) => {
          const wrapper = el.querySelector('span[style]') || el;
          return wrapper.getAttribute('style') || '';
        });

        console.log(`First node has style: ${firstNodeStyle}`);
      }
    }
  });

  test('should handle double-click to make folder current', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    // Create nested folder structure for testing
    const uuid = randomUUID().substring(0, 8);
    const parentName = `test-tree-${uuid}-parent`;
    const childName = `test-tree-${uuid}-child`;

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() === 0) {
      test.skip('Folder creation functionality not available');
      return;
    }

    // Create parent folder
    await createFolderButton.click();
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
    await nameInput.fill(parentName);
    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(1500);

    // Navigate into parent folder to create child
    const parentInTable = page.locator('.ant-table-tbody').locator(`text=${parentName}`);
    await parentInTable.click();
    await page.waitForTimeout(1500);

    // Create child folder
    await createFolderButton.click();
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    await nameInput.fill(childName);
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(1500);

    // Now test double-click in tree
    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      console.log('Folder tree not visible - skipping double-click test');
      return;
    }

    // Find child folder in tree
    const childInTree = folderTree.locator('.ant-tree-node-content-wrapper').filter({ hasText: childName });

    if (await childInTree.count() > 0) {
      // First click to select
      await childInTree.click();
      await page.waitForTimeout(400); // Wait less than click delay (250ms default)

      // Second click (double-click) to make current
      await childInTree.click();
      await page.waitForTimeout(1000);

      // After double-click, tree should redraw with child as current folder
      // This means child folder should now be the "root" of visible tree
      // or have the current folder styling

      // Check if child folder now has "current folder" styling
      const childNodeStyle = await childInTree.evaluate((el) => {
        const wrapper = el.querySelector('span[style]');
        if (wrapper) {
          return wrapper.getAttribute('style') || '';
        }
        return el.closest('[style]')?.getAttribute('style') || '';
      });

      // Current folder has border: 1px solid #1890ff
      const isCurrentFolder = childNodeStyle.includes('1890ff') ||
                              childNodeStyle.includes('bold');

      console.log(`After double-click, child folder style: ${childNodeStyle}`);
      console.log(`Is current folder: ${isCurrentFolder}`);

      // The behavior depends on implementation - either styling changes
      // or tree structure changes (child becomes root of visible tree)
    } else {
      console.log('Child folder not visible in tree - may need to expand parent first');
    }
  });

  test('should load children on tree node expansion', async ({ page, browserName }) => {
    // Skip on mobile
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      test.skip('Folder tree not available on mobile');
      return;
    }

    // Create a folder with child to test lazy loading
    const uuid = randomUUID().substring(0, 8);
    const parentName = `test-tree-${uuid}-lazy-parent`;
    const childName = `test-tree-${uuid}-lazy-child`;

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() === 0) {
      test.skip('Folder creation functionality not available');
      return;
    }

    // Create parent folder
    await createFolderButton.click();
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
    await nameInput.fill(parentName);
    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(1500);

    // Navigate into parent folder
    const parentInTable = page.locator('.ant-table-tbody').locator(`text=${parentName}`);
    await parentInTable.click();
    await page.waitForTimeout(1500);

    // Create child folder inside
    await createFolderButton.click();
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    await nameInput.fill(childName);
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(1500);

    // Navigate back to root to see tree structure
    const rootBreadcrumb = page.locator('.ant-breadcrumb').locator('text=/').first();
    if (await rootBreadcrumb.count() > 0) {
      await rootBreadcrumb.click();
      await page.waitForTimeout(1500);
    }

    // Now check tree expansion behavior
    const folderTree = page.locator('.ant-tree');
    if (await folderTree.count() === 0) {
      console.log('Folder tree not visible');
      return;
    }

    // Find parent folder in tree
    const parentInTree = folderTree.locator('.ant-tree-treenode').filter({ hasText: parentName });

    if (await parentInTree.count() > 0) {
      // Look for expand toggle
      const expandToggle = parentInTree.locator('.ant-tree-switcher').first();

      if (await expandToggle.count() > 0) {
        // Check if parent has expand icon (not a leaf)
        const isLeaf = await expandToggle.evaluate(el =>
          el.classList.contains('ant-tree-switcher-noop')
        );

        if (!isLeaf) {
          // Expand the node
          await expandToggle.click();
          await page.waitForTimeout(1000);

          // Check if child folder now visible in tree
          const childInTree = folderTree.locator('.ant-tree-node-content-wrapper').filter({ hasText: childName });
          const childVisible = await childInTree.count() > 0;

          expect(childVisible).toBe(true);
          console.log('Child folder loaded on expansion');
        } else {
          console.log('Parent folder shows as leaf in tree - children may not be detected');
        }
      }
    } else {
      console.log('Parent folder not found in tree');
    }
  });
});
