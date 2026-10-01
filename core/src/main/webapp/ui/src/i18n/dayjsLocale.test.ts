import { describe, it, expect, afterAll } from 'vitest';
import dayjs from 'dayjs';
import i18n from './index';

// dayjs's global locale follows the UI language at the moment it changes (src/i18n/index.ts).
// Registering the 'ja' locale is part of it: without the import, dayjs.locale('ja') silently
// stays English, so each language is asserted, not only that something changed.
describe('dayjs follows the UI language', () => {
  afterAll(async () => {
    await i18n.changeLanguage('ja');
  });

  it('switches with the language, including a regional English and an unsupported language', async () => {
    await i18n.changeLanguage('en');
    expect(dayjs.locale()).toBe('en');
    await i18n.changeLanguage('ja');
    expect(dayjs.locale()).toBe('ja');
    expect(dayjs('2026-10-04').format('dd')).toBe('日');
    await i18n.changeLanguage('en-GB');
    expect(dayjs.locale()).toBe('en');
    expect(dayjs('2026-10-04').format('dd')).toBe('Su');
    // An unsupported language resolves to the fallback, Japanese, like the translations.
    await i18n.changeLanguage('fr');
    expect(dayjs.locale()).toBe('ja');
  });
});
