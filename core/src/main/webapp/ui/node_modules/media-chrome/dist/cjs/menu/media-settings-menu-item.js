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
var media_settings_menu_item_exports = {};
__export(media_settings_menu_item_exports, {
  MediaSettingsMenuItem: () => MediaSettingsMenuItem,
  default: () => media_settings_menu_item_default
});
module.exports = __toCommonJS(media_settings_menu_item_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_media_chrome_menu_item = require("./media-chrome-menu-item.js");
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    ${import_media_chrome_menu_item.MediaChromeMenuItem.getTemplateHTML.call(this, _attrs)}
    <style>
      slot:not([name="submenu"]) {
        opacity: var(--media-settings-menu-item-opacity, var(--media-menu-item-opacity));
      }

      :host([aria-expanded="true"]:hover) {
        background: transparent;
      }
    </style>
  `
  );
}
function getSuffixSlotInnerHTML(_attrs) {
  return (
    /*html*/
    `
    <svg aria-hidden="true" viewBox="0 0 20 24">
      <path d="m8.12 17.585-.742-.669 4.2-4.665-4.2-4.666.743-.669 4.803 5.335-4.803 5.334Z"/>
    </svg>
  `
  );
}
class MediaSettingsMenuItem extends import_media_chrome_menu_item.MediaChromeMenuItem {
}
MediaSettingsMenuItem.shadowRootOptions = { mode: "open" };
MediaSettingsMenuItem.getTemplateHTML = getTemplateHTML;
MediaSettingsMenuItem.getSuffixSlotInnerHTML = getSuffixSlotInnerHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-settings-menu-item")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-settings-menu-item",
    MediaSettingsMenuItem
  );
}
var media_settings_menu_item_default = MediaSettingsMenuItem;
