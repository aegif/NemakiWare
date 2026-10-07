import { describe, it, expect } from 'vitest';

// The start-up path of src/i18n/index.ts: dayjs takes the UI language before anything switches it.
// This file starts in Japanese on a browser whose language differs from the resolved one, so it
// fails if the start-up call is missing (dayjs's default is English) or reads the browser's
// language instead of i18next's; dayjsStartupLocaleEn.test.ts starts in English and fails if the
// start-up sets Japanese unconditionally.
// A file of its own, with nothing imported up front, because i18next and dayjs are singletons that
// a module reset does not renew — in a file that had already loaded index.ts, the earlier
// listener would answer for the start-up call and the test could not tell the two apart.
describe('dayjs starts in the UI language — a Japanese start', () => {
  it('is Japanese when the app starts in Japanese, before any switch', async () => {
    // A browser that lists only en-US (as Playwright's Chromium does) on the app's page, whose
    // <html lang="ja"> (index.html) is the first exact match, so i18next resolves Japanese while
    // the browser says English (this environment has no usable localStorage, so nothing stored
    // takes part). dayjs's own default is English, so 'ja' can only come from index.ts.
    document.documentElement.lang = 'ja';
    Object.defineProperty(window.navigator, 'languages', { value: ['en-US'], configurable: true });
    Object.defineProperty(window.navigator, 'language', { value: 'en-US', configurable: true });
    const { default: dayjs } = await import('dayjs');
    const { default: i18n } = await import('./index');
    expect(i18n.resolvedLanguage).toBe('ja');
    expect(dayjs.locale()).toBe('ja');
  });
});
