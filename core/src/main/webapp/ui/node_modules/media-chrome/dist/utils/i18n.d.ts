import { TranslateDictionary, TranslateKeys } from '../lang/en.js';
export declare const setLanguage: (langCode: string) => void;
export declare const addTranslation: (lang: string, languageDictionary: TranslateDictionary) => void;
export declare const t: (key: TranslateKeys, vars?: Record<string, string | number>) => string;
