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
var media_pip_button_exports = {};
__export(media_pip_button_exports, {
  default: () => media_pip_button_default
});
module.exports = __toCommonJS(media_pip_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_i18n = require("./utils/i18n.js");
var import_element_utils = require("./utils/element-utils.js");
const pipIcon = `<svg aria-hidden="true" viewBox="0 0 28 24">
  <path d="M24 3H4a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1Zm-1 16H5V5h18v14Zm-3-8h-7v5h7v-5Z"/>
</svg>`;
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host([${import_constants.MediaUIAttributes.MEDIA_IS_PIP}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_PIP}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_IS_PIP}]) slot[name=tooltip-enter],
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_IS_PIP}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${pipIcon}</slot>
      <slot name="exit">${pipIcon}</slot>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (
    /*html*/
    `
    <slot name="tooltip-enter">${(0, import_i18n.t)("Enter picture in picture mode")}</slot>
    <slot name="tooltip-exit">${(0, import_i18n.t)("Exit picture in picture mode")}</slot>
  `
  );
}
const updateAriaLabel = (el) => {
  const label = el.mediaIsPip ? (0, import_i18n.t)("exit picture in picture mode") : (0, import_i18n.t)("enter picture in picture mode");
  el.setAttribute("aria-label", label);
};
class MediaPipButton extends import_media_chrome_button.MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_IS_PIP,
      import_constants.MediaUIAttributes.MEDIA_PIP_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    updateAriaLabel(this);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_IS_PIP) {
      updateAriaLabel(this);
    }
  }
  /**
   * @type {string | undefined} Pip unavailability state
   */
  get mediaPipUnavailable() {
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_PIP_UNAVAILABLE);
  }
  set mediaPipUnavailable(value) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_PIP_UNAVAILABLE, value);
  }
  /**
   * @type {boolean} Is the media currently playing picture-in-picture
   */
  get mediaIsPip() {
    return (0, import_element_utils.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_PIP);
  }
  set mediaIsPip(value) {
    (0, import_element_utils.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_IS_PIP, value);
  }
  handleClick() {
    const eventName = this.mediaIsPip ? import_constants.MediaUIEvents.MEDIA_EXIT_PIP_REQUEST : import_constants.MediaUIEvents.MEDIA_ENTER_PIP_REQUEST;
    this.dispatchEvent(
      new import_server_safe_globals.globalThis.CustomEvent(eventName, { composed: true, bubbles: true })
    );
  }
}
MediaPipButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPipButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-pip-button")) {
  import_server_safe_globals.globalThis.customElements.define("media-pip-button", MediaPipButton);
}
var media_pip_button_default = MediaPipButton;
