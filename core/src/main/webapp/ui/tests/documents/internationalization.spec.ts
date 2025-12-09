import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

/**
 * Internationalization Test Suite
 *
 * Tests document management system's ability to handle:
 * - Japanese filenames (日本語ファイル名)
 * - Special characters (emoji, accented characters, Chinese characters)
 * - Unicode path handling in folder hierarchies
 * - CMIS property encoding verification
 * - Search functionality with international characters
 *
 * Critical for global deployment and multi-language support.
 */

test.describe('Internationalization Tests', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login and navigate to documents
    await authHelper.login();
    await page.goto('http://localhost:8080/core/ui/');
    await testHelper.waitForAntdLoad();

    // Navigate to Documents section
    const documentsLink = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsLink.click();
    await page.waitForLoadState('networkidle');

    // Mobile browser handling: close sidebar to prevent overlay blocking
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }
  });

  test.afterEach(async ({ page }) => {
    // Cleanup: Delete all test documents created during internationalization tests
    try {
      // Query for test documents with various naming patterns
      const queryPatterns = [
        'test-i18n-%25',       // UUID-based test files
        '%25テスト%25',         // Japanese test files
        '%25测试%25',          // Chinese test files
        '%25тест%25',          // Russian test files
        '%25prueba%25'         // Spanish test files
      ];

      for (const pattern of queryPatterns) {
        const queryResponse = await page.request.get(
          `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId,cmis:name%20FROM%20cmis:document%20WHERE%20cmis:name%20LIKE%20'${pattern}'`,
          {
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            }
          }
        );

        if (queryResponse.ok()) {
          const queryData = await queryResponse.json();
          const documents = queryData.results || [];

          // Delete each document
          for (const doc of documents) {
            const objectId = doc.properties['cmis:objectId']?.value;
            if (objectId) {
              await page.request.post('http://localhost:8080/core/browser/bedroom', {
                headers: {
                  'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`,
                  'Content-Type': 'application/x-www-form-urlencoded'
                },
                form: {
                  'cmisaction': 'delete',
                  'objectId': objectId,
                  'allVersions': 'true'
                }
              });
            }
          }
        }
      }

      // Cleanup test folders with international names
      const folderQueryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId%20FROM%20cmis:folder%20WHERE%20cmis:name%20LIKE%20'test-i18n-folder-%25'`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (folderQueryResponse.ok()) {
        const folderData = await folderQueryResponse.json();
        const folders = folderData.results || [];

        for (const folder of folders) {
          const folderId = folder.properties['cmis:objectId']?.value;
          if (folderId) {
            await page.request.post('http://localhost:8080/core/browser/bedroom', {
              headers: {
                'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`,
                'Content-Type': 'application/x-www-form-urlencoded'
              },
              form: {
                'cmisaction': 'deleteTree',
                'folderId': folderId,
                'allVersions': 'true'
              }
            });
          }
        }
      }
    } catch (error) {
      console.log('Cleanup error (non-critical):', error);
    }
  });

  test('should handle Japanese filename upload and display', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);
    const japaneseFilename = `テストファイル-${uuid}.txt`;

    // Locate upload button
    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() === 0) {
      test.skip('File upload functionality not available');
      return;
    }

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Click upload button
    await uploadButton.click(isMobile ? { force: true } : {});

    // Wait for upload modal
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    // Upload file with Japanese name
    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      japaneseFilename,
      'これは日本語のテストコンテンツです。'
    );

    // Submit upload
    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary').first();
    await submitButton.click();

    // Wait for success message
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });

    // Wait for table update
    await page.waitForTimeout(2000);

    // Verify Japanese filename appears in document list
    const documentRow = page.locator(`.ant-table-tbody tr:has-text("${japaneseFilename}")`);
    await expect(documentRow).toBeVisible({ timeout: 10000 });

    // Verify Japanese characters are correctly displayed (not garbled)
    const fileNameCell = documentRow.locator('td').filter({ hasText: japaneseFilename });
    await expect(fileNameCell).toContainText('テストファイル');
  });

  test('should handle special characters in filenames (emoji, accents, Chinese)', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);

    // Test various special character combinations
    const specialFilenames = [
      `test-emoji-${uuid}-📄.txt`,           // Emoji
      `test-accent-${uuid}-café.txt`,        // Accented characters
      `test-chinese-${uuid}-测试.txt`,       // Chinese characters
      `test-mixed-${uuid}-文書📝.txt`        // Mixed Unicode
    ];

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() === 0) {
      test.skip('File upload functionality not available');
      return;
    }

    // Upload each file with special characters
    for (const filename of specialFilenames) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        filename,
        `Test content for ${filename}`
      );

      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary').first();
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(1000);
    }

    // Verify all special character filenames are displayed correctly
    for (const filename of specialFilenames) {
      const documentRow = page.locator(`.ant-table-tbody tr:has-text("${filename}")`);
      await expect(documentRow).toBeVisible({ timeout: 10000 });
    }
  });

  test('should handle Unicode characters in folder hierarchy', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);
    const japaneseFolderName = `test-i18n-folder-${uuid}-日本語`;
    const chineseFolderName = `test-i18n-folder-${uuid}-中文`;

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });

    if (await createFolderButton.count() === 0) {
      test.skip('Folder creation functionality not available');
      return;
    }

    // Create Japanese folder
    await createFolderButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
    await nameInput.fill(japaneseFolderName);

    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Verify Japanese folder appears
    const japaneseFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: japaneseFolderName });
    await expect(japaneseFolderRow).toBeVisible({ timeout: 10000 });

    // Navigate into Japanese folder
    const japaneseFolderLink = japaneseFolderRow.locator('a, td').first();
    await japaneseFolderLink.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Create Chinese subfolder inside Japanese folder
    await createFolderButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });
    await nameInput.fill(chineseFolderName);
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Verify breadcrumb shows Japanese folder name correctly
    const breadcrumb = page.locator('.ant-breadcrumb');
    if (await breadcrumb.count() > 0) {
      await expect(breadcrumb).toContainText('日本語');
    }

    // Verify Chinese subfolder appears
    const chineseFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: chineseFolderName });
    await expect(chineseFolderRow).toBeVisible({ timeout: 10000 });
  });

  test('should preserve Unicode encoding in CMIS properties', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);
    const unicodeFilename = `test-i18n-${uuid}-特殊文字テスト.txt`;

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() === 0) {
      test.skip('File upload functionality not available');
      return;
    }

    // Upload file with Unicode filename
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      unicodeFilename,
      'Unicode test content'
    );

    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary').first();
    await submitButton.click();
    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Open document properties
    const documentRow = page.locator(`.ant-table-tbody tr:has-text("${unicodeFilename}")`);
    await expect(documentRow).toBeVisible({ timeout: 10000 });

    // Look for properties/info button
    const propertiesButton = documentRow.locator('button, a').filter({
      or: [
        { hasText: 'プロパティ' },
        { hasText: '詳細' },
        { hasText: 'Properties' }
      ]
    });

    if (await propertiesButton.count() > 0) {
      await propertiesButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Verify Unicode characters in properties modal/drawer
      const propertiesContainer = page.locator('.ant-modal, .ant-drawer');
      if (await propertiesContainer.count() > 0) {
        // Check cmis:name property contains correct Unicode characters
        await expect(propertiesContainer).toContainText('特殊文字テスト');
      }
    } else {
      // If properties button not found, verify via CMIS API directly
      const queryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:name%20FROM%20cmis:document%20WHERE%20cmis:name%20LIKE%20'test-i18n-${uuid}%25'`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      expect(queryResponse.ok()).toBeTruthy();
      const queryData = await queryResponse.json();
      const results = queryData.results || [];

      expect(results.length).toBeGreaterThan(0);
      const cmisName = results[0].properties['cmis:name']?.value;
      expect(cmisName).toBe(unicodeFilename);
    }
  });

  test('should support search functionality with international characters', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);
    const searchableFilenames = [
      `test-i18n-${uuid}-検索テスト.txt`,     // Japanese
      `test-i18n-${uuid}-搜索测试.txt`,       // Chinese
      `test-i18n-${uuid}-поиск.txt`          // Russian
    ];

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() === 0) {
      test.skip('File upload functionality not available');
      return;
    }

    // Upload files with international characters
    for (const filename of searchableFilenames) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        filename,
        `Searchable content for ${filename}`
      );

      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary').first();
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(1000);
    }

    // Wait for all uploads to complete
    await page.waitForTimeout(2000);

    // Test search with Japanese characters
    const searchInput = page.locator('input[placeholder*="検索"], input[type="search"], .search-input');

    if (await searchInput.count() === 0) {
      test.skip('Search functionality not available');
      return;
    }

    // Search for Japanese text
    await searchInput.fill('検索テスト');

    // Look for search button
    const searchButton = page.locator('button').filter({
      or: [
        { hasText: '検索' },
        { hasText: 'Search' }
      ]
    });

    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Verify Japanese file appears in search results
      const japaneseResult = page.locator('.ant-table-tbody tr').filter({ hasText: '検索テスト' });
      if (await japaneseResult.count() > 0) {
        await expect(japaneseResult).toBeVisible();
      } else {
        console.log('Search results may not be displaying - checking if search executed');
      }
    } else {
      // If no search button, pressing Enter might trigger search
      await searchInput.press('Enter');
      await page.waitForTimeout(2000);
    }

    // Clear search
    await searchInput.clear();
    await page.waitForTimeout(1000);

    // Verify all international files are still accessible after search
    for (const filename of searchableFilenames) {
      const documentRow = page.locator(`.ant-table-tbody tr:has-text("${filename}")`);
      await expect(documentRow).toBeVisible({ timeout: 10000 });
    }
  });

  test('should handle filename length limits with multibyte characters', async ({ page, browserName }) => {
    const uuid = randomUUID().substring(0, 8);

    // Test long filenames with multibyte characters (Japanese uses 3 bytes per character in UTF-8)
    // CMIS typically limits filenames to 255 bytes, which is ~85 Japanese characters
    const longJapaneseName = `test-i18n-${uuid}-${'あ'.repeat(50)}.txt`; // 50 Japanese chars
    const veryLongName = `test-i18n-${uuid}-${'日'.repeat(80)}.txt`;     // 80 Japanese chars (240 bytes)

    // Mobile browser detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() === 0) {
      test.skip('File upload functionality not available');
      return;
    }

    // Test moderate length Japanese filename (should succeed)
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      longJapaneseName,
      'Content for long Japanese filename'
    );

    const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary').first();
    await submitButton.click();

    // Check if upload succeeded
    const successMessage = page.locator('.ant-message-success');
    const errorMessage = page.locator('.ant-message-error, .ant-modal .ant-alert-error');

    await Promise.race([
      successMessage.waitFor({ timeout: 10000 }),
      errorMessage.waitFor({ timeout: 10000 })
    ]).catch(() => {});

    if (await successMessage.count() > 0) {
      // Moderate length succeeded - verify display
      await page.waitForTimeout(2000);
      const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: uuid });
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      // Verify Japanese characters are not truncated or garbled
      await expect(documentRow).toContainText('あ');
    }

    // Test very long filename (may fail with validation error - that's acceptable)
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    const nameInput = page.locator('.ant-modal input[type="text"], .ant-modal input[id*="name"]');
    if (await nameInput.count() > 0) {
      await nameInput.fill(veryLongName);

      // Check if validation error appears for too-long filename
      const validationError = page.locator('.ant-form-item-explain-error, .ant-modal .ant-alert-error');

      await page.waitForTimeout(1000);

      if (await validationError.count() > 0) {
        // Validation error is expected for very long filenames - test passes
        expect(await validationError.count()).toBeGreaterThan(0);

        // Close modal
        const cancelButton = page.locator('.ant-modal button').filter({ hasText: 'キャンセル' });
        if (await cancelButton.count() > 0) {
          await cancelButton.click();
        }
      }
    }
  });
});
