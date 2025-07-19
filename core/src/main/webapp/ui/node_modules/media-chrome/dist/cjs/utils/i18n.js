var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var i18n_exports = {};
__export(i18n_exports, {
  addTranslation: () => addTranslation,
  setLanguage: () => setLanguage,
  t: () => t
});
module.exports = __toCommonJS(i18n_exports);
var import_en = require("../lang/en.js");
var _a;
const translations = {
  en: import_en.En
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
