import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Swagger UI "Try it out" must actually execute against the running server.
 *
 * 3.3.1 #8 found that this had NEVER worked: the served openapi.json carried no `servers`
 * (the application class's @OpenAPIDefinition is not scanned by OpenApiResource), so Swagger
 * UI resolved every request against the page origin — /api/v1/... without the /core context —
 * and all 129 operations answered 404. The fix configures the OpenAPI context programmatically
 * (ApiV1Application, JaxrsOpenApiContextBuilder).
 *
 * This test pins the CONSEQUENCE end to end: expand GET .../objects/{objectId}/acl, execute it
 * from the UI, and require an HTTP 200 with a real ACL body. Reverting the servers fix makes
 * the UI-built request hit the wrong base again and this test fail on the 200 assertion.
 */
test.describe('API docs Try it out', () => {
  test('executes GET ACL from the UI and gets a 200 with a real body', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    await authHelper.login();

    // The spec must advertise the servlet context — this is the fixed artifact itself.
    const spec = await page.evaluate(async () => {
      const r = await fetch('/core/api/v1/cmis/openapi.json', { credentials: 'same-origin' });
      return r.json();
    });
    // "/core" only: the spec's paths already include the @ApplicationPath (/api/v1/cmis),
    // so the server URL must carry just the servlet context or the segment doubles.
    expect(spec.servers?.[0]?.url, 'openapi.json must carry the /core base').toBe('/core');

    // Resolve the root folder id from the server rather than hardcoding the dump constant.
    const rootFolderId = await page.evaluate(async () => {
      const r = await fetch('/core/api/v1/cmis/repositories/bedroom', { credentials: 'same-origin' });
      const json = await r.json();
      return json.rootFolderId ?? json.repositoryInfo?.rootFolderId ?? null;
    });
    expect(rootFolderId, 'repository info must expose the root folder id').toBeTruthy();

    await page.goto('/core/ui/#/api-docs');
    const opRows = page.locator('.opblock-summary');
    await expect(opRows.first()).toBeVisible({ timeout: 30000 });

    // Expand the GET ACL operation.
    const aclRow = page
      .locator('.opblock.opblock-get', { hasText: '/objects/{objectId}/acl' })
      .first();
    await aclRow.locator('.opblock-summary').click();
    await expect(aclRow).toHaveClass(/is-open/);

    await aclRow.getByRole('button', { name: /try it out/i }).click();

    const inputs = aclRow.locator('.parameters input');
    await inputs.nth(0).fill('bedroom');
    await inputs.nth(1).fill(String(rootFolderId));

    await aclRow.locator('button.execute').click();

    // The live response (not the schema example) must show a 200 and a real ACL payload.
    const liveResponse = aclRow.locator('.live-responses-table');
    await expect(liveResponse).toBeVisible({ timeout: 20000 });
    // tr.response, NOT .response-col_status alone — the latter also matches the table
    // HEADER cell ("Code"), which is what the first version of this test asserted against.
    await expect(liveResponse.locator('tr.response td.response-col_status').first()).toHaveText(
      /200/,
      { timeout: 20000 },
    );
    await expect(liveResponse.locator('pre').first()).toContainText('aces');
  });
});
