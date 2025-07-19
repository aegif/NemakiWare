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
var media_settings_menu_exports = {};
__export(media_settings_menu_exports, {
  MediaSettingsMenu: () => MediaSettingsMenu,
  default: () => media_settings_menu_default
});
module.exports = __toCommonJS(media_settings_menu_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_media_chrome_menu = require("./media-chrome-menu.js");
var import_element_utils = require("../utils/element-utils.js");
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    ${import_media_chrome_menu.MediaChromeMenu.getTemplateHTML(_attrs)}
    <style>
      :host {
        --_menu-bg: rgb(20 20 30 / .8);
        background: var(--media-settings-menu-background,
            var(--media-menu-background,
              var(--media-control-background,
                var(--media-secondary-color, var(--_menu-bg)))));
        min-width: var(--media-settings-menu-min-width, 170px);
        border-radius: 2px 2px 0 0;
        overflow: hidden;
      }

      @-moz-document url-prefix() {
        :host{
          --_menu-bg: rgb(20 20 30);
        }
      }

      :host([role="menu"]) {
        ${/* Bottom fix setting menu items for animation when the height expands. */
    ""}
        justify-content: end;
      }

      slot:not([name]) {
        justify-content: var(--media-settings-menu-justify-content);
        flex-direction: var(--media-settings-menu-flex-direction, column);
        overflow: visible;
      }

      #container.has-expanded {
        --media-settings-menu-item-opacity: 0;
      }
    </style>
  `
  );
}
class MediaSettingsMenu extends import_media_chrome_menu.MediaChromeMenu {
  /**
   * Returns the anchor element when it is a floating menu.
   */
  get anchorElement() {
    if (this.anchor !== "auto")
      return super.anchorElement;
    return (0, import_element_utils.getMediaController)(this).querySelector(
      "media-settings-menu-button"
    );
  }
}
MediaSettingsMenu.getTemplateHTML = getTemplateHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-settings-menu")) {
  import_server_safe_globals.globalThis.customElements.define("media-settings-menu", MediaSettingsMenu);
}
var media_settings_menu_default = MediaSettingsMenu;
