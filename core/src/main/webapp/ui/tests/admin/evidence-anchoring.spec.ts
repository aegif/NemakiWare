import { test, expect, APIRequestContext } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { waitForUiStable } from '../utils/wait-helpers';

/**
 * Admin → Evidence and time-stamping (design docs/design/anchor-scheduler.md §6, plan §20 C-4).
 *
 * From the UI: an empty interval cannot be saved while enabled, a saved schedule reads back as
 * saved, and with no rung configured the screen says the schedule does nothing. Where anchors are
 * sent is not editable (the API refuses the keys by name).
 *
 * The schedule is restored to "off, nothing set" afterwards, so a run of this spec never leaves
 * the scheduler enabled on the environment.
 */

const REPO = 'bedroom';
const SCHEDULE = `/core/api/v1/admin/anchor/schedule?repositoryId=${REPO}`;
const HEADERS = {
  Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'),
  'X-Requested-With': 'XMLHttpRequest',
};
const KEYS = [
  'anchor.schedule.enabled',
  'anchor.schedule.interval-minutes',
  'anchor.schedule.max-unanchored-entries',
  'anchor.schedule.min-interval-minutes',
  'anchor.schedule.upgrade-interval-minutes',
  'anchor.schedule.retry-unsettled-interval-minutes',
];

async function clearSchedule(request: APIRequestContext) {
  const body: Record<string, string> = {};
  for (const key of KEYS) {
    body[key] = '';
  }
  body['anchor.schedule.enabled'] = 'false';
  const res = await request.put(SCHEDULE, { headers: { ...HEADERS, 'Content-Type': 'application/json' }, data: body });
  expect(res.status(), await res.text()).toBe(200);
}

test.describe('Evidence and time-stamping (C-4)', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ request }) => {
    await clearSchedule(request);
  });

  test.afterAll(async ({ request }) => {
    await clearSchedule(request);
  });

  test.beforeEach(async ({ page }) => {
    await new AuthHelper(page).login();
    await waitForUiStable(page);
    await page.goto('/core/ui/#/evidence-anchoring');
    await waitForUiStable(page);
    await expect(page.getByTestId('evidence-anchoring-page')).toBeVisible();
  });

  test('enabled with an empty interval cannot be saved — the button is disabled and says why', async ({ page }) => {
    const toggle = page.getByTestId('anchor-schedule-enabled');
    await expect(toggle).toBeVisible();
    if ((await toggle.getAttribute('aria-checked')) !== 'true') {
      await toggle.click();
    }
    await page.getByTestId('anchor-interval').fill('');

    await expect(page.getByTestId('anchor-save')).toBeDisabled();
    await expect(page.getByTestId('anchor-save-disabled-reason')).toBeVisible();
  });

  test('a saved schedule reads back as saved', async ({ page, request }) => {
    await page.getByTestId('anchor-interval').fill('1440');
    await page.getByTestId('anchor-max').fill('500');
    await page.getByTestId('anchor-min-interval').fill('10');
    await page.getByTestId('anchor-retry-interval').fill('120');
    await page.getByTestId('anchor-save').click();
    await expect(page.getByText(/保存しました|Saved/).first()).toBeVisible({ timeout: 15000 });

    const res = await request.get(SCHEDULE, { headers: HEADERS });
    expect(res.ok()).toBe(true);
    const body = await res.json();
    expect(body.settings['anchor.schedule.interval-minutes']).toBe('1440');
    expect(body.settings['anchor.schedule.max-unanchored-entries']).toBe('500');
    expect(body.settings['anchor.schedule.min-interval-minutes']).toBe('10');
    expect(body.settings['anchor.schedule.retry-unsettled-interval-minutes']).toBe('120');

    // And the screen shows the saved value after a reload, not the form's own memory.
    await page.reload();
    await waitForUiStable(page);
    await expect(page.getByTestId('anchor-interval')).toHaveValue('1440');
  });

  test('with no rung configured the screen says the schedule does nothing', async ({ page, request }) => {
    const res = await request.get(SCHEDULE, { headers: HEADERS });
    const body = await res.json();
    const configured = (body.rungs as Array<{ configured: boolean }>).filter((r) => r.configured).length;
    test.skip(configured > 0, 'ENV: this deployment has a rung configured, so the no-rung notice is not shown');

    await expect(page.getByTestId('anchor-no-rung')).toBeVisible();
  });

  test('where anchors are sent cannot be set — the API refuses the keys by name', async ({ request }) => {
    const res = await request.put(SCHEDULE, {
      headers: { ...HEADERS, 'Content-Type': 'application/json' },
      data: { 'anchor.rfc3161.tsa.url': 'http://tsa.example.invalid/' },
    });
    expect(res.status()).toBe(400);
    expect(JSON.stringify(await res.json())).toContain('anchor.rfc3161.tsa.url');
  });
});
