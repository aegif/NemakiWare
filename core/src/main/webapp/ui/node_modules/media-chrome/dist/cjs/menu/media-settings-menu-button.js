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
var media_settings_menu_button_exports = {};
__export(media_settings_menu_button_exports, {
  MediaSettingsMenuButton: () => MediaSettingsMenuButton,
  default: () => media_settings_menu_button_default
});
module.exports = __toCommonJS(media_settings_menu_button_exports);
var import_media_chrome_menu_button = require("./media-chrome-menu-button.js");
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_element_utils = require("../utils/element-utils.js");
var import_i18n = require("../utils/i18n.js");
function getSlotTemplateHTML() {
  return (
    /*html*/
    `
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">
      <svg aria-hidden="true" viewBox="0 0 24 24">
        <path d="M4.5 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/>
      </svg>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Settings");
}
class MediaSettingsMenuButton extends import_media_chrome_menu_button.MediaChromeMenuButton {
  static get observedAttributes() {
    return [...super.observedAttributes, "target"];
  }
  connectedCallback() {
    super.connectedCallback();
    this.setAttribute("aria-label", (0, import_i18n.t)("settings"));
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   * @return {HTMLElement | null}
   */
  get invokeTargetElement() {
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return (0, import_element_utils.getMediaController)(this).querySelector("media-settings-menu");
  }
}
MediaSettingsMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaSettingsMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-settings-menu-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-settings-menu-button",
    MediaSettingsMenuButton
  );
}
var media_settings_menu_button_default = MediaSettingsMenuButton;
