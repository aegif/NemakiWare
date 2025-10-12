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
        const antdElements = document.querySelectorAll('.ant-layout, .ant-menu, .ant-table');
        return antdElements.length > 0;
      },
      { timeout: 15000 }
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
}