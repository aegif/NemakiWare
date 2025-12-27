import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

import ja from './locales/ja.json';
import en from './locales/en.json';

// Available languages
export const languages = {
  ja: { name: '日本語', nativeName: '日本語' },
  en: { name: 'English', nativeName: 'English' }
} as const;

export type LanguageCode = keyof typeof languages;

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

export default i18n;
