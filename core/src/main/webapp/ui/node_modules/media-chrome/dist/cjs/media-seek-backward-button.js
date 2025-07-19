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
var media_seek_backward_button_exports = {};
__export(media_seek_backward_button_exports, {
  Attributes: () => Attributes,
  default: () => media_seek_backward_button_default
});
module.exports = __toCommonJS(media_seek_backward_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_element_utils = require("./utils/element-utils.js");
var import_i18n = require("./utils/i18n.js");
const Attributes = {
  SEEK_OFFSET: "seekoffset"
};
const DEFAULT_SEEK_OFFSET = 30;
const backwardIcon = (seekOffset) => `
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(2.18 19.87)">${seekOffset}</text>
    <path d="M10 6V3L4.37 7 10 10.94V8a5.54 5.54 0 0 1 1.9 10.48v2.12A7.5 7.5 0 0 0 10 6Z"/>
  </svg>`;
function getSlotTemplateHTML(_attrs, props) {
  return (
    /*html*/
    `
    <slot name="icon">${backwardIcon(props.seekOffset)}</slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Seek backward");
}
const DEFAULT_TIME = 0;
class MediaSeekBackwardButton extends import_media_chrome_button.MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME,
      Attributes.SEEK_OFFSET
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    this.seekOffset = (0, import_element_utils.getNumericAttr)(
      this,
      Attributes.SEEK_OFFSET,
      DEFAULT_SEEK_OFFSET
    );
  }
  attributeChangedCallback(attrName, _oldValue, newValue) {
    super.attributeChangedCallback(attrName, _oldValue, newValue);
    if (attrName === Attributes.SEEK_OFFSET) {
      this.seekOffset = (0, import_element_utils.getNumericAttr)(
        this,
        Attributes.SEEK_OFFSET,
        DEFAULT_SEEK_OFFSET
      );
    }
  }
  // Own props
  /**
   * Seek amount in seconds
   */
  get seekOffset() {
    return (0, import_element_utils.getNumericAttr)(this, Attributes.SEEK_OFFSET, DEFAULT_SEEK_OFFSET);
  }
  set seekOffset(value) {
    (0, import_element_utils.setNumericAttr)(this, Attributes.SEEK_OFFSET, value);
    this.setAttribute(
      "aria-label",
      (0, import_i18n.t)("seek back {seekOffset} seconds", { seekOffset: this.seekOffset })
    );
    (0, import_element_utils.updateIconText)((0, import_element_utils.getSlotted)(this, "icon"), this.seekOffset);
  }
  // Props derived from Media UI Attributes
  /**
   * The current time in seconds
   */
  get mediaCurrentTime() {
    return (0, import_element_utils.getNumericAttr)(
      this,
      import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME,
      DEFAULT_TIME
    );
  }
  set mediaCurrentTime(time) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME, time);
  }
  handleClick() {
    const detail = Math.max(this.mediaCurrentTime - this.seekOffset, 0);
    const evt = new import_server_safe_globals.globalThis.CustomEvent(import_constants.MediaUIEvents.MEDIA_SEEK_REQUEST, {
      composed: true,
      bubbles: true,
      detail
    });
    this.dispatchEvent(evt);
  }
}
MediaSeekBackwardButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaSeekBackwardButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-seek-backward-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-seek-backward-button",
    MediaSeekBackwardButton
  );
}
var media_seek_backward_button_default = MediaSeekBackwardButton;
