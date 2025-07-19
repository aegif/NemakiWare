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
var menu_exports = {};
__export(menu_exports, {
  MediaAudioTrackMenu: () => MediaAudioTrackMenu,
  MediaAudioTrackMenuButton: () => MediaAudioTrackMenuButton,
  MediaCaptionsMenu: () => MediaCaptionsMenu,
  MediaCaptionsMenuButton: () => MediaCaptionsMenuButton,
  MediaChromeMenu: () => MediaChromeMenu,
  MediaChromeMenuButton: () => MediaChromeMenuButton,
  MediaChromeMenuItem: () => MediaChromeMenuItem,
  MediaPlaybackRateMenu: () => MediaPlaybackRateMenu,
  MediaPlaybackRateMenuButton: () => MediaPlaybackRateMenuButton,
  MediaRenditionMenu: () => MediaRenditionMenu,
  MediaRenditionMenuButton: () => MediaRenditionMenuButton,
  MediaSettingsMenu: () => MediaSettingsMenu,
  MediaSettingsMenuButton: () => MediaSettingsMenuButton,
  MediaSettingsMenuItem: () => MediaSettingsMenuItem
});
module.exports = __toCommonJS(menu_exports);
var import_react = __toESM(require("react"), 1);
var import_ce_la_react = require("ce-la-react");
var Modules = __toESM(require("../../menu/index.js"), 1);
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
const MediaChromeMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-menu",
  elementClass: Modules.MediaChromeMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaChromeMenuItem = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-menu-item",
  elementClass: Modules.MediaChromeMenuItem,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaSettingsMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-settings-menu",
  elementClass: Modules.MediaSettingsMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaSettingsMenuItem = (0, import_ce_la_react.createComponent)({
  tagName: "media-settings-menu-item",
  elementClass: Modules.MediaSettingsMenuItem,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaChromeMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-menu-button",
  elementClass: Modules.MediaChromeMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaSettingsMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-settings-menu-button",
  elementClass: Modules.MediaSettingsMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaAudioTrackMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-audio-track-menu",
  elementClass: Modules.MediaAudioTrackMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaAudioTrackMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-audio-track-menu-button",
  elementClass: Modules.MediaAudioTrackMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaCaptionsMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-captions-menu",
  elementClass: Modules.MediaCaptionsMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaCaptionsMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-captions-menu-button",
  elementClass: Modules.MediaCaptionsMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPlaybackRateMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-playback-rate-menu",
  elementClass: Modules.MediaPlaybackRateMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPlaybackRateMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-playback-rate-menu-button",
  elementClass: Modules.MediaPlaybackRateMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaRenditionMenu = (0, import_ce_la_react.createComponent)({
  tagName: "media-rendition-menu",
  elementClass: Modules.MediaRenditionMenu,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaRenditionMenuButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-rendition-menu-button",
  elementClass: Modules.MediaRenditionMenuButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
