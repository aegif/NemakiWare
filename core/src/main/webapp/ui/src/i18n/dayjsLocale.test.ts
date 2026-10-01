import { describe, it, expect, afterAll } from 'vitest';
import dayjs from 'dayjs';
import i18n from './index';

// dayjs's global locale follows the UI language (src/i18n/index.ts) on two paths: once at
// start-up (dayjsStartupLocale*.test.ts, each needing a module context of its own) and on every
// change (here). Registering the 'ja' locale is part of both: without the import,
// dayjs.locale('ja') silently stays English, so each language is asserted, not only that
// something changed.
describe('dayjs follows the UI language', () => {
  afterAll(async () => {
    await i18n.changeLanguage('ja');
  });

  it('switches with the language (the listener path)', async () => {
    await i18n.changeLanguage('en');
    expect(dayjs.locale()).toBe('en');
    await i18n.changeLanguage('ja');
    expect(dayjs.locale()).toBe('ja');
    expect(dayjs('2026-10-04').format('dd')).toBe('日');
    // i18next resolves these codes before the listener runs, so they check the outcome (dayjs
    // follows the resolved language), not uiLanguage's own parsing of a region or an unknown code.
    await i18n.changeLanguage('en-GB');
    expect(dayjs.locale()).toBe('en');
    expect(dayjs('2026-10-04').format('dd')).toBe('Su');
    await i18n.changeLanguage('fr');
    expect(dayjs.locale()).toBe('ja');
  });
});
