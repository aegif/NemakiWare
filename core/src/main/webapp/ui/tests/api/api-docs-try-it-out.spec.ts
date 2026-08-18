import { test, expect, Locator, Page } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Swagger UI "Try it out" must actually execute against the running server.
 *
 * 3.3.1 #8 found this had NEVER worked, for two stacked reasons:
 *  (a) the served openapi.json carried no `servers` (the application class's
 *      @OpenAPIDefinition is not scanned by OpenApiResource), so Swagger UI resolved every
 *      request against the page origin — /api/v1/... without the /core context — and all
 *      129 operations answered 404. Fixed by configuring the OpenAPI context
 *      programmatically (ApiV1Application, JaxrsOpenApiContextBuilder).
 *  (b) the first fix value (/core/api/v1/cmis) doubled the path — the spec's paths already
 *      include the @ApplicationPath — and the auth filter misparsed the doubled URL into a
 *      401. Only reading the URL of a real UI-built request caught this; the correct server
 *      URL is the servlet context alone (/core).
 *
 * Auth needs no special handling: it rides the HttpOnly cookie, which fetch's default
 * credentials mode already sends. For unsafe methods the CSRF filter accepts the browser's
 * same-origin Origin header, so the POST below exercises cookie transport + CSRF acceptance
 * end to end (the X-Requested-With merge in ApiDocs.tsx is defense in depth on top of that,
 * not load-bearing — measured against the live filter, 2026-08-18).
 *
 * This test pins the CONSEQUENCE: expand a GET and a POST operation, execute both from the
 * UI, and require live 200s with real bodies. Reverting the servers fix re-breaks both.
 */
test.describe('API docs Try it out', () => {
  async function expandAndTryOut(row: Locator): Promise<void> {
    await row.locator('.opblock-summary').click();
    await expect(row).toHaveClass(/is-open/);
    await row.getByRole('button', { name: /try it out/i }).click();
  }

  async function expectLive200(row: Locator, bodyMarker: string): Promise<void> {
    const liveResponse = row.locator('.live-responses-table');
    await expect(liveResponse).toBeVisible({ timeout: 20000 });
    // tr.response, NOT .response-col_status alone — the latter also matches the table
    // HEADER cell ("Code"), which is what the first version of this test asserted against.
    await expect(liveResponse.locator('tr.response td.response-col_status').first()).toHaveText(
      /200/,
      { timeout: 20000 },
    );
    await expect(liveResponse.locator('pre').first()).toContainText(bodyMarker);
  }

  test('executes GET and POST from the UI and gets live 200s', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    await authHelper.login();

    // The spec must advertise the servlet context and nothing else — this is the fixed
    // artifact itself. Assert the WHOLE list: a stray second entry (e.g. from annotation
    // scanning ever being turned on) would silently offer users a broken base URL.
    const spec = await page.evaluate(async () => {
      const r = await fetch('/core/api/v1/cmis/openapi.json', { credentials: 'same-origin' });
      return r.json();
    });
    const serverUrls = (spec.servers ?? []).map((s: { url: string }) => s.url);
    expect(serverUrls, 'openapi.json must carry exactly the /core base').toEqual(['/core']);

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

    // --- GET .../objects/{objectId}/acl ---
    const aclRow = page
      .locator('.opblock.opblock-get', { hasText: '/objects/{objectId}/acl' })
      .first();
    await expandAndTryOut(aclRow);
    const aclInputs = aclRow.locator('.parameters input');
    await aclInputs.nth(0).fill('bedroom');
    await aclInputs.nth(1).fill(String(rootFolderId));
    await aclRow.locator('button.execute').click();
    await expectLive200(aclRow, 'aces');

    // --- POST .../repositories/{repositoryId}/query (unsafe method: cookie + CSRF path) ---
    // hasText is substring-based: '/repositories/{repositoryId}/query' does not occur in
    // the search-engine query path, and the GET /query/changes row is excluded by the
    // opblock-post class, so this resolves to exactly one operation.
    const queryRow = page
      .locator('.opblock.opblock-post', { hasText: '/repositories/{repositoryId}/query' })
      .first();
    await expandAndTryOut(queryRow);
    await queryRow.locator('.parameters input').first().fill('bedroom');
    const bodyBox = queryRow.locator('textarea.body-param__text');
    await expect(bodyBox).toBeVisible();
    await bodyBox.fill(JSON.stringify({
      statement: "SELECT cmis:objectId FROM cmis:folder WHERE cmis:name = 'nonexistent-e2e-probe'",
    }));
    await queryRow.locator('button.execute').click();
    // 200 with the ObjectListResponse envelope — an empty hit list still carries numItems.
    await expectLive200(queryRow, 'numItems');
  });
});
