import { describe, it, expect } from 'vitest';

// The start-up path of src/i18n/index.ts, starting in English (here through the browser's
// language: the page has no lang and this environment no usable localStorage). With
// dayjsStartupLocale.test.ts (a Japanese start) it measures that the start-up call does not set
// Japanese — the fallback — unconditionally. A file of its own, with nothing imported up front:
// i18next and dayjs are singletons a module reset does not renew.
describe('dayjs starts in the UI language — an English start', () => {
  it('is English when the app starts in English, before any switch', async () => {
    Object.defineProperty(window.navigator, 'languages', { value: ['en-US'], configurable: true });
    Object.defineProperty(window.navigator, 'language', { value: 'en-US', configurable: true });
    const { default: dayjs } = await import('dayjs');
    const { default: i18n } = await import('./index');
    expect(i18n.resolvedLanguage).toBe('en');
    expect(dayjs.locale()).toBe('en');
  });
});
