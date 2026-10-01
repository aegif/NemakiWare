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
 * The language the UI shows: 'en' when i18next resolved to English (a stored choice or an
 * English browser), 'ja' otherwise — the same answer the translations use, since 'ja' is the
 * fallback. Ant Design's built-in texts and dayjs follow this, so a component's own labels and
 * the library's (empty tables, date pickers, default confirm buttons) never disagree.
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

// dayjs's global locale follows the UI language too, at the moment the language changes (a
// component effect would run only after the next render). antd's ja / en locales carry their own
// weekday and month names and both start the week on Sunday, and the app's own dayjs formats are
// numeric, so today this changes nothing on screen; it keeps any locale-dependent dayjs output
// (month names, relative times) in the UI language. src/i18n/dayjsLocale.test.ts measures it.
const syncDayjsLocale = (lng?: string) => {
  dayjs.locale(uiLanguage(lng));
};
i18n.on('languageChanged', syncDayjsLocale);
syncDayjsLocale();

export default i18n;
