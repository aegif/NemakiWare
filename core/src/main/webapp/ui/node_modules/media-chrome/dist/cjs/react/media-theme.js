var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
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
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var media_theme_exports = {};
__export(media_theme_exports, {
  MediaTheme: () => MediaTheme
});
module.exports = __toCommonJS(media_theme_exports);
var import_react = __toESM(require("react"), 1);
var import_ce_la_react = require("ce-la-react");
var Modules = __toESM(require("../media-theme.js"), 1);
function toAttributeValue(propValue) {
  if (typeof propValue === "boolean")
    return propValue ? "" : void 0;
  if (typeof propValue === "function")
    return void 0;
  const isPrimitive = (v) => typeof v === "string" || typeof v === "number" || typeof v === "boolean";
  if (Array.isArray(propValue) && propValue.every(isPrimitive))
    return propValue.join(" ");
  if (typeof propValue === "object" && propValue !== null)
    return void 0;
  return propValue;
}
const MediaTheme = (0, import_ce_la_react.createComponent)({
  tagName: "media-theme",
  elementClass: Modules.MediaTheme,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
