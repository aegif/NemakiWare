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
var media_play_button_exports = {};
__export(media_play_button_exports, {
  MediaPlayButton: () => MediaPlayButton,
  default: () => media_play_button_default
});
module.exports = __toCommonJS(media_play_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_i18n = require("./utils/i18n.js");
var import_element_utils = require("./utils/element-utils.js");
const playIcon = `<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="m6 21 15-9L6 3v18Z"/>
</svg>`;
const pauseIcon = `<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M6 20h4V4H6v16Zm8-16v16h4V4h-4Z"/>
</svg>`;
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host([${import_constants.MediaUIAttributes.MEDIA_PAUSED}]) slot[name=pause],
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_PAUSED}])) slot[name=play] {
        display: none !important;
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_PAUSED}]) slot[name=tooltip-pause],
      :host(:not([${import_constants.MediaUIAttributes.MEDIA_PAUSED}])) slot[name=tooltip-play] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="play">${playIcon}</slot>
      <slot name="pause">${pauseIcon}</slot>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (
    /*html*/
    `
    <slot name="tooltip-play">${(0, import_i18n.t)("Play")}</slot>
    <slot name="tooltip-pause">${(0, import_i18n.t)("Pause")}</slot>
  `
  );
}
const updateAriaLabel = (el) => {
  const label = el.mediaPaused ? (0, import_i18n.t)("play") : (0, import_i18n.t)("pause");
  el.setAttribute("aria-label", label);
};
class MediaPlayButton extends import_media_chrome_button.MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_PAUSED,
      import_constants.MediaUIAttributes.MEDIA_ENDED
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    updateAriaLabel(this);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_PAUSED) {
      updateAriaLabel(this);
    }
  }
  /**
   * Is the media paused
   */
  get mediaPaused() {
    return (0, import_element_utils.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED);
  }
  set mediaPaused(value) {
    (0, import_element_utils.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED, value);
  }
  handleClick() {
    const eventName = this.mediaPaused ? import_constants.MediaUIEvents.MEDIA_PLAY_REQUEST : import_constants.MediaUIEvents.MEDIA_PAUSE_REQUEST;
    this.dispatchEvent(
      new import_server_safe_globals.globalThis.CustomEvent(eventName, { composed: true, bubbles: true })
    );
  }
}
MediaPlayButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPlayButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-play-button")) {
  import_server_safe_globals.globalThis.customElements.define("media-play-button", MediaPlayButton);
}
var media_play_button_default = MediaPlayButton;
