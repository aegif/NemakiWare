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
var media_rendition_menu_button_exports = {};
__export(media_rendition_menu_button_exports, {
  MediaRenditionMenuButton: () => MediaRenditionMenuButton,
  default: () => media_rendition_menu_button_default
});
module.exports = __toCommonJS(media_rendition_menu_button_exports);
var import_constants = require("../constants.js");
var import_media_chrome_menu_button = require("./media-chrome-menu-button.js");
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_element_utils = require("../utils/element-utils.js");
var import_i18n = require("../utils/i18n.js");
const renditionIcon = (
  /*html*/
  `<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M13.5 2.5h2v6h-2v-2h-11v-2h11v-2Zm4 2h4v2h-4v-2Zm-12 4h2v6h-2v-2h-3v-2h3v-2Zm4 2h12v2h-12v-2Zm1 4h2v6h-2v-2h-8v-2h8v-2Zm4 2h7v2h-7v-2Z" />
</svg>`
);
function getSlotTemplateHTML() {
  return (
    /*html*/
    `
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${renditionIcon}</slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Quality");
}
class MediaRenditionMenuButton extends import_media_chrome_menu_button.MediaChromeMenuButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED,
      import_constants.MediaUIAttributes.MEDIA_RENDITION_UNAVAILABLE,
      import_constants.MediaUIAttributes.MEDIA_HEIGHT
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    this.setAttribute("aria-label", (0, import_i18n.t)("quality"));
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   */
  get invokeTargetElement() {
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return (0, import_element_utils.getMediaController)(this).querySelector("media-rendition-menu");
  }
  /**
   * Get selected rendition id.
   */
  get mediaRenditionSelected() {
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED);
  }
  set mediaRenditionSelected(id) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED, id);
  }
  get mediaHeight() {
    return (0, import_element_utils.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_HEIGHT);
  }
  set mediaHeight(height) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_HEIGHT, height);
  }
}
MediaRenditionMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaRenditionMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-rendition-menu-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-rendition-menu-button",
    MediaRenditionMenuButton
  );
}
var media_rendition_menu_button_default = MediaRenditionMenuButton;
