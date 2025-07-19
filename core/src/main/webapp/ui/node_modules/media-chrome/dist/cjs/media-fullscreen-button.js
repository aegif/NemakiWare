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
var media_fullscreen_button_exports = {};
__export(media_fullscreen_button_exports, {
  default: () => media_fullscreen_button_default
});
module.exports = __toCommonJS(media_fullscreen_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_i18n = require("./utils/i18n.js");
var import_element_utils = require("./utils/element-utils.js");
const enterFullscreenIcon = `<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M16 3v2.5h3.5V9H22V3h-6ZM4 9h2.5V5.5H10V3H4v6Zm15.5 9.5H16V21h6v-6h-2.5v3.5ZM6.5 15H4v6h6v-2.5H6.5V15Z"/>
</svg>`;
const exitFullscreenIcon = `<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M18.5 6.5V3H16v6h6V6.5h-3.5ZM16 21h2.5v-3.5H22V15h-6v6ZM4 17.5h3.5V21H10v-6H4v2.5Zm3.5-11H4V9h6V3H7.5v3.5Z"/>
</svg>`;
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host([${import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      ${/* Double negative, but safer if display doesn't equal 'block' */
    ""}
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN}]) slot[name=tooltip-enter],
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${enterFullscreenIcon}</slot>
      <slot name="exit">${exitFullscreenIcon}</slot>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (
    /*html*/
    `
    <slot name="tooltip-enter">${(0, import_i18n.t)("Enter fullscreen mode")}</slot>
    <slot name="tooltip-exit">${(0, import_i18n.t)("Exit fullscreen mode")}</slot>
  `
  );
}
const updateAriaLabel = (el) => {
  const label = el.mediaIsFullscreen ? (0, import_i18n.t)("exit fullscreen mode") : (0, import_i18n.t)("enter fullscreen mode");
  el.setAttribute("aria-label", label);
};
class MediaFullscreenButton extends import_media_chrome_button.MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN,
      import_constants.MediaUIAttributes.MEDIA_FULLSCREEN_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    updateAriaLabel(this);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN) {
      updateAriaLabel(this);
    }
  }
  /**
   * @type {string | undefined} Fullscreen unavailability state
   */
  get mediaFullscreenUnavailable() {
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_FULLSCREEN_UNAVAILABLE);
  }
  set mediaFullscreenUnavailable(value) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_FULLSCREEN_UNAVAILABLE, value);
  }
  /**
   * @type {boolean} Whether fullscreen is available
   */
  get mediaIsFullscreen() {
    return (0, import_element_utils.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN);
  }
  set mediaIsFullscreen(value) {
    (0, import_element_utils.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN, value);
  }
  handleClick() {
    const eventName = this.mediaIsFullscreen ? import_constants.MediaUIEvents.MEDIA_EXIT_FULLSCREEN_REQUEST : import_constants.MediaUIEvents.MEDIA_ENTER_FULLSCREEN_REQUEST;
    this.dispatchEvent(
      new import_server_safe_globals.globalThis.CustomEvent(eventName, { composed: true, bubbles: true })
    );
  }
}
MediaFullscreenButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaFullscreenButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-fullscreen-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-fullscreen-button",
    MediaFullscreenButton
  );
}
var media_fullscreen_button_default = MediaFullscreenButton;
