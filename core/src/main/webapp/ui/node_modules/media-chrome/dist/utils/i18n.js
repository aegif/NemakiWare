var _a;
import { En } from "../lang/en.js";
const translations = {
  en: En
};
let currentLang = ((_a = globalThis.navigator) == null ? void 0 : _a.language) || "en";
const setLanguage = (langCode) => {
  currentLang = langCode;
};
const addTranslation = (lang, languageDictionary) => {
  translations[lang] = languageDictionary;
};
const resolveTranslation = (key) => {
  var _a2, _b, _c;
  const [base] = currentLang.split("-");
  return ((_a2 = translations[currentLang]) == null ? void 0 : _a2[key]) || ((_b = translations[base]) == null ? void 0 : _b[key]) || ((_c = translations.en) == null ? void 0 : _c[key]) || key;
};
const t = (key, vars = {}) => resolveTranslation(key).replace(
  /\{(\w+)\}/g,
  (_, v) => v in vars ? String(vars[v]) : `{${v}}`
);
export {
  addTranslation,
  setLanguage,
  t
};
