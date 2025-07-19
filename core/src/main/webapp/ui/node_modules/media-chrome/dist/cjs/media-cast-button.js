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
var media_cast_button_exports = {};
__export(media_cast_button_exports, {
  default: () => media_cast_button_default
});
module.exports = __toCommonJS(media_cast_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_i18n = require("./utils/i18n.js");
var import_element_utils = require("./utils/element-utils.js");
const enterIcon = `<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/></g></svg>`;
const exitIcon = `<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/><path class="cast_caf_icon_boxfill" d="M5,7 L5,8.63 C8,8.6 13.37,14 13.37,17 L19,17 L19,7 Z"/></g></svg>`;
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      ${/* Double negative, but safer if display doesn't equal 'block' */
    ""}
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}]) slot[name=tooltip-enter],
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${enterIcon}</slot>
      <slot name="exit">${exitIcon}</slot>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (
    /*html*/
    `
    <slot name="tooltip-enter">${(0, import_i18n.t)("Start casting")}</slot>
    <slot name="tooltip-exit">${(0, import_i18n.t)("Stop casting")}</slot>
  `
  );
}
const updateAriaLabel = (el) => {
  const label = el.mediaIsCasting ? (0, import_i18n.t)("stop casting") : (0, import_i18n.t)("start casting");
  el.setAttribute("aria-label", label);
};
class MediaCastButton extends import_media_chrome_button.MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_IS_CASTING,
      import_constants.MediaUIAttributes.MEDIA_CAST_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    updateAriaLabel(this);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_IS_CASTING) {
      updateAriaLabel(this);
    }
  }
  /**
   * @type {boolean} Are we currently casting
   */
  get mediaIsCasting() {
    return (0, import_element_utils.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_CASTING);
  }
  set mediaIsCasting(value) {
    (0, import_element_utils.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_CASTING, value);
  }
  /**
   * @type {string | undefined} Cast unavailability state
   */
  get mediaCastUnavailable() {
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_CAST_UNAVAILABLE);
  }
  set mediaCastUnavailable(value) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_CAST_UNAVAILABLE, value);
  }
  handleClick() {
    const eventName = this.mediaIsCasting ? import_constants.MediaUIEvents.MEDIA_EXIT_CAST_REQUEST : import_constants.MediaUIEvents.MEDIA_ENTER_CAST_REQUEST;
    this.dispatchEvent(
      new import_server_safe_globals.globalThis.CustomEvent(eventName, { composed: true, bubbles: true })
    );
  }
}
MediaCastButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaCastButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-cast-button")) {
  import_server_safe_globals.globalThis.customElements.define("media-cast-button", MediaCastButton);
}
var media_cast_button_default = MediaCastButton;
