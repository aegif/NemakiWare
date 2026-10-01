import { describe, it, expect } from 'vitest';

// The start-up path of src/i18n/index.ts: dayjs takes the UI language before anything switches it.
// This file starts in Japanese (fails if the start-up call is missing: dayjs's default is
// English); dayjsStartupLocaleEn.test.ts starts in English (fails if the start-up sets Japanese
// without reading the resolved language).
// A file of its own, with nothing imported up front, because i18next and dayjs are singletons that
// a module reset does not renew — in a file that had already loaded index.ts, the earlier
// listener would answer for the start-up call and the test could not tell the two apart.
describe('dayjs starts in the UI language — a Japanese start', () => {
  it('is Japanese when the app starts in Japanese, before any switch', async () => {
    // A Japanese browser opening the app (this environment has no usable localStorage, so the
    // detector falls through to the browser's language). dayjs's own default is English, so 'ja'
    // can only come from index.ts.
    Object.defineProperty(window.navigator, 'languages', { value: ['ja-JP'], configurable: true });
    Object.defineProperty(window.navigator, 'language', { value: 'ja-JP', configurable: true });
    const { default: dayjs } = await import('dayjs');
    const { default: i18n } = await import('./index');
    expect(i18n.resolvedLanguage).toBe('ja');
    expect(dayjs.locale()).toBe('ja');
  });
});
