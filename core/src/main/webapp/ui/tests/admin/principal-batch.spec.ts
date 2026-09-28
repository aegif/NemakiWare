import { test, expect, APIRequestContext, Page } from '@playwright/test';
import { randomUUID } from 'crypto';
import { AuthHelper } from '../utils/auth-helper';
import { waitForUiStable } from '../utils/wait-helpers';

/**
 * Bulk correction of users, groups and memberships from the management screens
 * (design docs/design/principal-batch.md §8, plan §20 C-2).
 *
 * Measured from the UI: a preview writes nothing (the list is unchanged afterwards), "confirm and
 * run" aborts on an unexpected row, skipping is a separate button behind a confirmation, a
 * forbidden row is not written even with skip, and a blank groups column keeps the memberships.
 * Setup and verification go through the API (the same batch endpoint, and api/v1 users/groups).
 */

const REPO = 'bedroom';
const API = `/core/api/v1/cmis/repositories/${REPO}`;
const HEADERS = {
  Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'),
  'X-Requested-With': 'XMLHttpRequest',
};
const PASSWORD = 'Batch-e2e-Passw0rd!';

async function batch(request: APIRequestContext, kind: string, operation: string, rows: unknown[], onUnexpected = 'abort') {
  const res = await request.post(`${API}/principals/batch/execute`, {
    headers: { ...HEADERS, 'Content-Type': 'application/json' },
    data: { kind, operation, onUnexpected, rows },
  });
  return { status: res.status(), body: await res.json().catch(() => ({})) };
}

async function userExists(request: APIRequestContext, userId: string): Promise<boolean> {
  const res = await request.get(`${API}/users/${encodeURIComponent(userId)}`, { headers: HEADERS });
  if (res.status() === 404) {
    return false;
  }
  expect(res.ok(), `GET user ${userId} answered ${res.status()}`).toBe(true);
  return true;
}

async function deleteQuietly(request: APIRequestContext, kind: 'users' | 'groups', id: string) {
  await request.delete(`${API}/${kind}/${encodeURIComponent(id)}`, { headers: HEADERS }).catch(() => undefined);
}

async function openBatch(page: Page, screen: '/users' | '/groups') {
  await page.goto(`/core/ui/#${screen}`);
  await waitForUiStable(page);
  await page.getByTestId('principal-batch-open').click();
  await expect(page.getByRole('dialog')).toBeVisible();
}

async function chooseAndUpload(page: Page, operation: RegExp, csv: string, kind?: RegExp) {
  const dialog = page.getByRole('dialog');
  if (kind) {
    await dialog.getByText(kind).first().click();
  }
  await dialog.getByText(operation).first().click();
  await page.getByTestId('principal-batch-next').click();
  await dialog.locator('input[type="file"]').setInputFiles({
    name: 'batch.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(csv, 'utf-8'),
  });
  await page.getByTestId('principal-batch-preview').click();
  await expect(page.getByTestId('principal-batch-count-expected')).toBeVisible({ timeout: 15000 });
}

test.describe('Principal batch (C-2)', () => {
  // The tests share the users they create (the abort test needs `fresh` absent, the skip test
  // creates it), so they run in order.
  test.describe.configure({ mode: 'serial' });
  const run = randomUUID().slice(0, 8);
  const existing = `batch-e2e-existing-${run}`;
  const fresh = `batch-e2e-new-${run}`;
  const member = `batch-e2e-member-${run}`;
  const group = `batch-e2e-group-${run}`;

  test.beforeAll(async ({ request }) => {
    const users = await batch(request, 'users', 'create', [
      { userId: existing, name: existing, password: PASSWORD },
      { userId: member, name: member, password: PASSWORD },
    ]);
    expect(users.status, JSON.stringify(users.body)).toBe(200);
    const groups = await batch(request, 'groups', 'create', [{ groupId: group, name: group, users: [member] }]);
    expect(groups.status, JSON.stringify(groups.body)).toBe(200);
  });

  test.afterAll(async ({ request }) => {
    await deleteQuietly(request, 'groups', group);
    for (const id of [existing, fresh, member]) {
      await deleteQuietly(request, 'users', id);
    }
  });

  test.beforeEach(async ({ page }) => {
    await new AuthHelper(page).login();
    await waitForUiStable(page);
  });

  test('a preview writes nothing — the user does not exist afterwards and the list does not show it', async ({ page, request }) => {
    await openBatch(page, '/users');
    await chooseAndUpload(page, /^(作成|Create)$/, `userId,name,password\n${fresh},${fresh},${PASSWORD}\n`);

    await expect(page.getByTestId('principal-batch-count-expected')).toHaveText('1');
    expect(await userExists(request, fresh)).toBe(false);

    await page.keyboard.press('Escape');
    await page.goto('/core/ui/#/users');
    await waitForUiStable(page);
    await page.locator('.ant-input-search input').first().fill(fresh);
    await page.keyboard.press('Enter');
    await waitForUiStable(page);
    await expect(page.getByRole('cell', { name: fresh, exact: true })).toHaveCount(0);
  });

  test('confirm and run aborts when a row is unexpected — nothing is written', async ({ page, request }) => {
    await openBatch(page, '/users');
    await chooseAndUpload(page, /^(作成|Create)$/,
      `userId,name,password\n${existing},${existing},${PASSWORD}\n${fresh},${fresh},${PASSWORD}\n`);
    await expect(page.getByTestId('principal-batch-count-unexpected')).toHaveText('1');

    await page.getByTestId('principal-batch-confirm').click();

    await expect(page.getByText(/UNEXPECTED_ROWS/)).toBeVisible({ timeout: 15000 });
    expect(await userExists(request, fresh)).toBe(false);
  });

  test('skipping is a separate button behind a confirmation, and applies the expected rows only', async ({ page, request }) => {
    await openBatch(page, '/users');
    await chooseAndUpload(page, /^(作成|Create)$/,
      `userId,name,password\n${existing},${existing},${PASSWORD}\n${fresh},${fresh},${PASSWORD}\n`);

    await page.getByTestId('principal-batch-skip').click();
    await page.getByRole('button', { name: /飛ばして実行|Skip and run/ }).click();

    await expect(page.getByText(/適用しました|Applied/).first()).toBeVisible({ timeout: 15000 });
    expect(await userExists(request, fresh)).toBe(true);
  });

  test('a forbidden row is not written even with skip — the built-in admin survives a delete file', async ({ page, request }) => {
    const doomed = `batch-e2e-doomed-${run}`;
    expect((await batch(request, 'users', 'create', [{ userId: doomed, name: doomed, password: PASSWORD }])).status).toBe(200);

    await openBatch(page, '/users');
    await chooseAndUpload(page, /^(削除|Delete)$/, `userId\nadmin\n${doomed}\n`);
    await expect(page.getByTestId('principal-batch-count-forbidden')).toHaveText('1');
    await page.getByTestId('principal-batch-skip').click();
    await page.getByRole('button', { name: /飛ばして実行|Skip and run/ }).click();

    await expect(page.getByText(/適用しました|Applied/).first()).toBeVisible({ timeout: 15000 });
    expect(await userExists(request, 'admin')).toBe(true);
    expect(await userExists(request, doomed)).toBe(false);
  });

  test('a blank groups column in an update leaves the memberships alone', async ({ page, request }) => {
    await openBatch(page, '/users');
    await chooseAndUpload(page, /^(更新|Update)$/, `userId,name,groups\n${member},Renamed ${run},\n`);
    await expect(page.getByTestId('principal-batch-count-expected')).toHaveText('1');

    await page.getByTestId('principal-batch-confirm').click();
    await expect(page.getByText(/適用しました|Applied/).first()).toBeVisible({ timeout: 15000 });

    const res = await request.get(`${API}/groups/${encodeURIComponent(group)}`, { headers: HEADERS });
    expect(res.ok()).toBe(true);
    const body = await res.json();
    expect(body.users, JSON.stringify(body)).toContain(member);
  });
});
