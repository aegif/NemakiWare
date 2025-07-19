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
var __accessCheck = (obj, member, msg) => {
  if (!member.has(obj))
    throw TypeError("Cannot " + msg);
};
var __privateGet = (obj, member, getter) => {
  __accessCheck(obj, member, "read from private field");
  return getter ? getter.call(obj) : member.get(obj);
};
var __privateAdd = (obj, member, value) => {
  if (member.has(obj))
    throw TypeError("Cannot add the same private member more than once");
  member instanceof WeakSet ? member.add(obj) : member.set(obj, value);
};
var media_playback_rate_button_exports = {};
__export(media_playback_rate_button_exports, {
  Attributes: () => Attributes,
  DEFAULT_RATE: () => DEFAULT_RATE,
  DEFAULT_RATES: () => DEFAULT_RATES,
  default: () => media_playback_rate_button_default
});
module.exports = __toCommonJS(media_playback_rate_button_exports);
var import_media_chrome_button = require("./media-chrome-button.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_attribute_token_list = require("./utils/attribute-token-list.js");
var import_element_utils = require("./utils/element-utils.js");
var import_i18n = require("./utils/i18n.js");
var _rates;
const Attributes = {
  RATES: "rates"
};
const DEFAULT_RATES = [1, 1.2, 1.5, 1.7, 2];
const DEFAULT_RATE = 1;
function getSlotTemplateHTML(attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
    </style>
    <slot name="icon">${attrs["mediaplaybackrate"] || DEFAULT_RATE}x</slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Playback rate");
}
class MediaPlaybackRateButton extends import_media_chrome_button.MediaChromeButton {
  constructor() {
    var _a;
    super();
    __privateAdd(this, _rates, new import_attribute_token_list.AttributeTokenList(this, Attributes.RATES, {
      defaultValue: DEFAULT_RATES
    }));
    this.container = this.shadowRoot.querySelector('slot[name="icon"]');
    this.container.innerHTML = `${(_a = this.mediaPlaybackRate) != null ? _a : DEFAULT_RATE}x`;
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      Attributes.RATES
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === Attributes.RATES) {
      __privateGet(this, _rates).value = newValue;
    }
    if (attrName === import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE) {
      const newPlaybackRate = newValue ? +newValue : Number.NaN;
      const playbackRate = !Number.isNaN(newPlaybackRate) ? newPlaybackRate : DEFAULT_RATE;
      this.container.innerHTML = `${playbackRate}x`;
      this.setAttribute(
        "aria-label",
        (0, import_i18n.t)("Playback rate {playbackRate}", { playbackRate })
      );
    }
  }
  /**
   * Get the playback rates for the button.
   */
  get rates() {
    return __privateGet(this, _rates);
  }
  /**
   * Set the playback rates for the button.
   * For React 19+ compatibility, accept a string of space-separated rates.
   */
  set rates(value) {
    if (!value) {
      __privateGet(this, _rates).value = "";
    } else if (Array.isArray(value)) {
      __privateGet(this, _rates).value = value.join(" ");
    } else if (typeof value === "string") {
      __privateGet(this, _rates).value = value;
    }
  }
  /**
   * @type {number} The current playback rate
   */
  get mediaPlaybackRate() {
    return (0, import_element_utils.getNumericAttr)(
      this,
      import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      DEFAULT_RATE
    );
  }
  set mediaPlaybackRate(value) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE, value);
  }
  handleClick() {
    var _a, _b;
    const availableRates = Array.from(__privateGet(this, _rates).values(), (str) => +str).sort(
      (a, b) => a - b
    );
    const detail = (_b = (_a = availableRates.find((r) => r > this.mediaPlaybackRate)) != null ? _a : availableRates[0]) != null ? _b : DEFAULT_RATE;
    const evt = new import_server_safe_globals.globalThis.CustomEvent(
      import_constants.MediaUIEvents.MEDIA_PLAYBACK_RATE_REQUEST,
      { composed: true, bubbles: true, detail }
    );
    this.dispatchEvent(evt);
  }
}
_rates = new WeakMap();
MediaPlaybackRateButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPlaybackRateButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-playback-rate-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-playback-rate-button",
    MediaPlaybackRateButton
  );
}
var media_playback_rate_button_default = MediaPlaybackRateButton;
