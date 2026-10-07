import { test, expect, Page } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { waitForAppReady, waitForUiStable } from '../utils/wait-helpers';

/**
 * Ant Design's own texts follow the UI language.
 *
 * Without a ConfigProvider locale antd shows English, so the Japanese UI — the default — showed
 * "No data", "Select date" and a "Cancel" button between Japanese labels. These tests read the
 * library's texts in BOTH languages on the screens that use them with no override of their own:
 * the search form's date pickers and its results table, and the cloud-directory-sync LDAP
 * confirmation. Each asserts the right language AND the absence of the other one, so a locale
 * that is not applied (antd's English everywhere) fails the Japanese half, and a locale that
 * does not follow the switch fails the last test.
 *
 * The confirmation is only ever dismissed with its cancel button; nothing is synchronised.
 *
 * Close buttons the locale does not name are named by App.tsx's ConfigProvider (common.close): a
 * Modal's (rc-dialog writes "Close" on it in every language), an Alert's and the image preview's
 * (named by their icon, "close", otherwise). Without that, all three fail in Japanese; in English
 * the Alert's and the preview's fail, and the Modal's cannot — "Close" is the same word. The Alert
 * is the Purview tab's connection test result, which saves nothing.
 *
 * The language is stored BEFORE the app loads (an init script), because the app reads it once at
 * start-up: a hash navigation after login does not reload the page, and with nothing stored the
 * app starts in Japanese even in this English browser (it lists only en-US, and the page's
 * lang="ja" is the first exact match; that first load then stores 'ja').
 */

type Lang = 'ja' | 'en';

const EXPECTED: Record<Lang, {
  datePlaceholder: string;
  today: string;
  firstWeekday: string;
  empty: RegExp;
  cancel: string;
  close: string;
  connectionResult: RegExp;
  otherLanguage: RegExp;
}> = {
  ja: {
    datePlaceholder: '日付を選択',
    today: '今日',
    firstWeekday: '日',
    empty: /データがありません|データなし/,
    cancel: 'キャンセル',
    close: '閉じる',
    connectionResult: /接続に成功しました|連携が無効です|接続に失敗しました/,
    otherLanguage: /Select date|Today|No data|Cancel/,
  },
  en: {
    datePlaceholder: 'Select date',
    today: 'Today',
    firstWeekday: 'Su',
    empty: /No data/,
    cancel: 'Cancel',
    close: 'Close',
    connectionResult: /Connection successful|Integration is disabled|Connection failed/,
    otherLanguage: /日付を選択|今日|データ|キャンセル/,
  },
};

async function openInLanguage(page: Page, lang: Lang, hashPath: string): Promise<void> {
  await page.addInitScript((l) => localStorage.setItem('nemakiware-language', l), lang);
  await new AuthHelper(page).login();
  await waitForAppReady(page, { timeout: 30000 });
  await page.goto(`/core/ui/#${hashPath}`);
  await waitForUiStable(page);
}

test.describe('Ant Design built-in texts follow the UI language', () => {

  for (const lang of ['ja', 'en'] as const) {
    test(`date picker: placeholder, today button and weekday names in ${lang}`, async ({ page }) => {
      await openInLanguage(page, lang, '/search');
      const picker = page.locator('.ant-picker').first();
      await expect(picker).toBeVisible({ timeout: 15000 });
      await expect(picker.locator('input')).toHaveAttribute('placeholder', EXPECTED[lang].datePlaceholder);

      await picker.click();
      const dropdown = page.locator('.ant-picker-dropdown:visible');
      await expect(dropdown).toBeVisible();
      await expect(dropdown.getByText(EXPECTED[lang].today, { exact: true })).toBeVisible();
      // The weekday header: Japanese from antd's ja_JP locale (its shortWeekDays), English from dayjs
      // 'en', which antd's picker asks for explicitly. Either way it is the picker's language.
      await expect(dropdown.locator('.ant-picker-content thead th').first()).toHaveText(EXPECTED[lang].firstWeekday);
      await expect(dropdown).not.toContainText(EXPECTED[lang].otherLanguage);
      await page.keyboard.press('Escape');
    });

    test(`empty table: the results table's empty text in ${lang}`, async ({ page }) => {
      await openInLanguage(page, lang, '/search');
      const token = `i18nempty${Date.now()}zz`;
      // The search form has no name, so antd gives the Form.Item "query" the input id "query".
      await page.locator('input#query').fill(token);
      await page.locator('.search-submit-button').click();
      const placeholder = page.locator('.ant-table-placeholder').first();
      await expect(placeholder).toBeVisible({ timeout: 30000 });
      await expect(placeholder).toHaveText(EXPECTED[lang].empty);
      await expect(placeholder).not.toContainText(EXPECTED[lang].otherLanguage);
    });

    test(`confirmation dialog: default buttons in ${lang}`, async ({ page }) => {
      await openInLanguage(page, lang, '/cloud-directory-sync');
      await page.locator('.ant-tabs-tab').filter({ hasText: 'LDAP / Active Directory' }).click();
      await page.getByRole('button', { name: /同期実行|Run Sync/ }).click();

      const popconfirm = page.locator('.ant-popconfirm:visible, .ant-popover:visible').filter({ has: page.locator('button') }).last();
      await expect(popconfirm).toBeVisible();
      const buttons = popconfirm.locator('button');
      await expect(buttons.filter({ hasText: /確定|OK|確認/ })).toHaveCount(1);
      const cancel = buttons.filter({ hasText: EXPECTED[lang].cancel });
      await expect(cancel).toHaveCount(1);
      await expect(popconfirm).not.toContainText(EXPECTED[lang].otherLanguage);
      await cancel.click();
      await expect(popconfirm).toBeHidden();
    });

    test(`close button: a Modal's in ${lang}`, async ({ page }) => {
      await openInLanguage(page, lang, '/users');
      await page.getByTestId('principal-batch-open').click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toBeVisible();
      const close = dialog.locator('.ant-modal-close');
      await expect(close).toHaveAccessibleName(EXPECTED[lang].close);
      await close.click();
      await expect(dialog).toBeHidden();
    });

    test(`close button: an Alert's in ${lang}`, async ({ page }) => {
      await openInLanguage(page, lang, '/integration-settings');
      await page.locator('.ant-tabs-tab').filter({ hasText: /Purview/i }).click();
      await waitForUiStable(page);
      await page.getByRole('button', { name: /接続テスト|Test\s*Connection/i }).click();
      // The result's own Alert — its title is one of the three outcomes — not another closable
      // Alert the tab may show.
      const result = page.locator('.ant-alert').filter({ hasText: EXPECTED[lang].connectionResult });
      await expect(result.locator('.ant-alert-close-icon')).toHaveAccessibleName(EXPECTED[lang].close, { timeout: 30000 });
    });

    test(`close button: the image preview's in ${lang}`, async ({ page }) => {
      // The help page opens with its login section expanded, so its first image is on the screen.
      await openInLanguage(page, lang, '/help');
      await page.locator('.ant-image').first().click();
      await expect(page.locator('.ant-image-preview-close')).toHaveAccessibleName(EXPECTED[lang].close);
    });
  }

  test('switching the language in the header re-labels antd without a reload', async ({ page }) => {
    // Japanese at the start (this browser and page, with nothing stored — see the note at the top
    // of this file), then English from the header only.
    await new AuthHelper(page).login();
    await waitForAppReady(page, { timeout: 30000 });
    await page.goto('/core/ui/#/search');
    await waitForUiStable(page);
    const pickerInput = page.locator('.ant-picker input').first();
    await expect(pickerInput).toHaveAttribute('placeholder', EXPECTED.ja.datePlaceholder, { timeout: 15000 });

    // The header's language switcher (an antd Select showing the current language's own name).
    await page.locator('.ant-select').filter({ hasText: '日本語' }).first().click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'English' }).click();

    await expect(pickerInput).toHaveAttribute('placeholder', EXPECTED.en.datePlaceholder);
    await pickerInput.click();
    const dropdown = page.locator('.ant-picker-dropdown:visible');
    await expect(dropdown.getByText(EXPECTED.en.today, { exact: true })).toBeVisible();
    await expect(dropdown.locator('.ant-picker-content thead th').first()).toHaveText(EXPECTED.en.firstWeekday);
    await page.keyboard.press('Escape');
  });
});
