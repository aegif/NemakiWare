import { Page, expect } from '@playwright/test';

/**
 * General test helper utilities for NemakiWare UI tests
 */
export class TestHelper {
  constructor(private page: Page) {}

  /**
   * Wait for page to fully load including all AJAX requests
   */
  async waitForPageLoad(timeout: number = 10000): Promise<void> {
    await this.page.waitForLoadState('networkidle', { timeout });
  }

  /**
   * Wait for Ant Design components to fully render
   * Increased timeout for mobile browser compatibility
   */
  async waitForAntdLoad(): Promise<void> {
    // Wait for Ant Design CSS to load
    await this.page.waitForFunction(
      () => {
        const antdElements = document.querySelectorAll('.ant-layout, .ant-menu, .ant-table, .ant-form, .ant-btn');
        return antdElements.length > 0;
      },
      { timeout: 30000 }
    );
  }

  /**
   * Take a screenshot with timestamp
   */
  async takeTimestampedScreenshot(name: string): Promise<void> {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    await this.page.screenshot({
      path: `test-results/screenshots/${name}-${timestamp}.png`,
      fullPage: true,
    });
  }

  /**
   * Check for JavaScript errors on the page
   * Sets up listeners and waits for a period to collect errors
   */
  async checkForJSErrors(waitTimeMs: number = 1000): Promise<string[]> {
    const errors: string[] = [];

    const consoleHandler = (msg: any) => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    };

    const pageErrorHandler = (error: any) => {
      errors.push(error.message);
    };

    // Set up listeners
    this.page.on('console', consoleHandler);
    this.page.on('pageerror', pageErrorHandler);

    // Wait for specified time to collect errors
    await this.page.waitForTimeout(waitTimeMs);

    // Clean up listeners
    this.page.off('console', consoleHandler);
    this.page.off('pageerror', pageErrorHandler);

    return errors;
  }

  /**
   * Wait for CMIS API call to complete
   */
  async waitForCMISResponse(urlPattern: string | RegExp = /\/core\/(atom|browser|rest)/): Promise<void> {
    await this.page.waitForResponse(
      (response) => {
        const url = response.url();
        const matches = typeof urlPattern === 'string' ? url.includes(urlPattern) : urlPattern.test(url);
        return matches && response.status() === 200;
      },
      { timeout: 15000 }
    );
  }

  /**
   * Verify no network errors occurred
   * Sets up listeners and waits for network activity to complete
   */
  async verifyNoNetworkErrors(waitTimeMs: number = 2000): Promise<void> {
    const responses: Array<{ url: string; status: number }> = [];

    const responseHandler = (response: any) => {
      if (response.status() >= 400) {
        responses.push({
          url: response.url(),
          status: response.status(),
        });
      }
    };

    // Set up listener
    this.page.on('response', responseHandler);

    // Wait for network activity to complete
    await this.page.waitForLoadState('networkidle');
    await this.page.waitForTimeout(waitTimeMs);

    // Clean up listener
    this.page.off('response', responseHandler);

    if (responses.length > 0) {
      const errorDetails = responses.map(r => `${r.status}: ${r.url}`).join('\n');
      throw new Error(`Network errors detected:\n${errorDetails}`);
    }
  }

  /**
   * Upload a test file
   */
  async uploadTestFile(fileInputSelector: string | any, fileName: string, content: string): Promise<void> {
    // Create a temporary file
    const buffer = Buffer.from(content, 'utf8');

    // Handle both string selectors and Locator objects
    if (typeof fileInputSelector === 'string') {
      // Set the file input using string selector
      await this.page.setInputFiles(fileInputSelector, {
        name: fileName,
        mimeType: 'text/plain',
        buffer: buffer,
      });
    } else {
      // Assume it's a Locator object
      await fileInputSelector.setInputFiles({
        name: fileName,
        mimeType: 'text/plain',
        buffer: buffer,
      });
    }
  }

  /**
   * Wait for element to be stable (not moving/changing)
   */
  async waitForElementStable(selector: string, timeout: number = 3000): Promise<void> {
    const element = this.page.locator(selector);
    await expect(element).toBeVisible();

    // Wait for element to stop moving/changing
    await this.page.waitForFunction(
      async (sel) => {
        const el = document.querySelector(sel);
        if (!el) return false;

        const rect1 = el.getBoundingClientRect();

        // Wait a bit and check again
        await new Promise(resolve => setTimeout(resolve, 100));

        const rect2 = el.getBoundingClientRect();
        return rect1.top === rect2.top &&
               rect1.left === rect2.left &&
               rect1.width === rect2.width &&
               rect1.height === rect2.height;
      },
      selector,
      { timeout }
    );
  }

  /**
   * Upload a document and wait for success
   * @param fileName Name of the file to upload
   * @param content Content of the file
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if upload succeeded, false otherwise
   */
  async uploadDocument(fileName: string, content: string, isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Uploading ${fileName}`);
    
    // Click upload button to open modal
    const uploadButton = this.page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    
    // Wait for upload modal to appear
    console.log('TestHelper: Waiting for upload modal...');
    await this.page.waitForSelector('.ant-modal:has-text("ファイルアップロード")', { timeout: 5000 });
    await this.page.waitForTimeout(500);

    console.log('TestHelper: Selecting file...');
    const fileInput = this.page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from(content, 'utf-8'),
    });
    await this.page.waitForTimeout(1000);

    console.log('TestHelper: Filling file name field...');
    const nameInput = this.page.locator('.ant-modal input[placeholder*="ファイル名"]');
    await nameInput.fill(fileName);
    await this.page.waitForTimeout(500);

    console.log('TestHelper: Clicking upload button in modal...');
    const modalUploadButton = this.page.locator('.ant-modal button[type="submit"]').filter({ hasText: 'アップロード' });
    await modalUploadButton.click(isMobile ? { force: true } : {});

    console.log('TestHelper: Waiting for upload response...');
    
    // Wait for upload to complete - check for success or error message (shorter timeout)
    try {
      await this.page.waitForSelector('.ant-message-success, .ant-message-error', { timeout: 5000 });

      const successMsg = await this.page.locator('.ant-message-success').count();
      const errorMsg = await this.page.locator('.ant-message-error').count();

      console.log(`TestHelper: Upload status - Success: ${successMsg > 0}, Error: ${errorMsg > 0}`);

      if (errorMsg > 0) {
        const errorText = await this.page.locator('.ant-message-error').textContent();
        console.log(`TestHelper: Upload ERROR - ${errorText}`);
      }
    } catch (e) {
      console.log('TestHelper: No upload success/error indicator appeared within 5s - checking if document appears in table');
    }

    // Wait for document to appear in table (even if error message was shown or no message appeared)
    await this.page.waitForTimeout(3000);
    
    let docExists = false;
    for (let i = 0; i < 3; i++) {
      const documentRow = this.page.locator('.ant-table-tbody tr').filter({ hasText: fileName }).first();
      docExists = await documentRow.count() > 0;
      
      if (docExists) {
        console.log(`TestHelper: Document found in table after ${i + 1} attempts`);
        return true;
      }
      
      console.log(`TestHelper: Document not found in table (attempt ${i + 1}/3), waiting...`);
      
      if (i === 1) {
        console.log('TestHelper: Refreshing page to check for uploaded document');
        await this.page.reload({ waitUntil: 'networkidle' });
        await this.page.waitForTimeout(2000);
      } else {
        await this.page.waitForTimeout(2000);
      }
    }
    
    console.log(`TestHelper: Document not found in table after 3 attempts`);
    
    const allRows = await this.page.locator('.ant-table-tbody tr').count();
    console.log(`TestHelper: Total rows in table: ${allRows}`);
    
    if (allRows > 0) {
      const firstRowText = await this.page.locator('.ant-table-tbody tr').first().textContent();
      console.log(`TestHelper: First row text: ${firstRowText?.substring(0, 100)}`);
    }
    
    return false;
  }
}
