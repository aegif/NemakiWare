import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import dayjs from 'dayjs';
import 'dayjs/locale/ja';
import 'dayjs/locale/en';

import ja from './locales/ja.json';
import en from './locales/en.json';

// Available languages
export const languages = {
  ja: { name: '日本語', nativeName: '日本語' },
  en: { name: 'English', nativeName: 'English' }
} as const;

export type LanguageCode = keyof typeof languages;

/**
 * The language the UI shows: 'en' when i18next resolved to English, 'ja' otherwise — the same
 * answer the translations use, since 'ja' is the fallback. i18next takes the first exact match
 * among a stored choice, the browser's languages and the page's <html lang="ja">, so English
 * comes from a stored choice or a browser whose list has a plain "en" before any plain "ja"; one
 * listing only en-US or en-GB gets Japanese. Ant Design's built-in texts and dayjs follow this, so
 * a component's own labels and the library's visible texts (empty tables, date pickers, default
 * confirm buttons) agree. Accessible names follow only in part (antd 6.5.1): Modal and
 * notification close buttons are named "Close" in every language (for a Modal the locale's word
 * lands on a wrapper inside the button), Tag and Drawer close buttons take the locale's word, and
 * an icon with no name of its own is named by its icon ("search").
 */
export const uiLanguage = (lng?: string): LanguageCode =>
  (lng ?? i18n.resolvedLanguage ?? i18n.language ?? 'ja').split('-')[0] === 'en' ? 'en' : 'ja';

i18n
  // Detect user language from browser
  .use(LanguageDetector)
  // Pass the i18n instance to react-i18next
  .use(initReactI18next)
  // Initialize i18next
  .init({
    resources: {
      ja: { translation: ja },
      en: { translation: en }
    },
    fallbackLng: 'ja', // Default to Japanese if detection fails
    supportedLngs: ['ja', 'en'],
    
    // Language detection options
    detection: {
      // Order of language detection methods
      order: ['localStorage', 'navigator', 'htmlTag'],
      // Cache user language preference in localStorage
      caches: ['localStorage'],
      // Key to store language preference
      lookupLocalStorage: 'nemakiware-language'
    },
    
    interpolation: {
      escapeValue: false // React already escapes values
    },
    
    // Debug mode (disable in production)
    debug: false
  });

// dayjs's global locale follows the UI language too: set once here at start-up (i18next has
// already resolved the language and emitted languageChanged before this listener exists, since
// the resources are bundled) and again on every change. Today this changes nothing on screen:
// antd's date picker does not read the global locale — Japanese names come from antd's ja_JP
// locale, English names from dayjs 'en' which the picker asks for explicitly — and the app's own
// dayjs formats are numeric. It keeps any locale-dependent dayjs output (month names, relative
// times) in the UI language. dayjsStartupLocale*.test.ts measure the start-up path (a file per
// language — i18next and dayjs are singletons), dayjsLocale.test.ts the change path.
const syncDayjsLocale = (lng?: string) => {
  dayjs.locale(uiLanguage(lng));
};
i18n.on('languageChanged', syncDayjsLocale);
syncDayjsLocale();

export default i18n;
