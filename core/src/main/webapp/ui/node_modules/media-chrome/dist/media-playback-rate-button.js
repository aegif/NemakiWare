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
var _rates;
import { MediaChromeButton } from "./media-chrome-button.js";
import { globalThis } from "./utils/server-safe-globals.js";
import { MediaUIEvents, MediaUIAttributes } from "./constants.js";
import { AttributeTokenList } from "./utils/attribute-token-list.js";
import { getNumericAttr, setNumericAttr } from "./utils/element-utils.js";
import { t } from "./utils/i18n.js";
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
  return t("Playback rate");
}
class MediaPlaybackRateButton extends MediaChromeButton {
  constructor() {
    var _a;
    super();
    __privateAdd(this, _rates, new AttributeTokenList(this, Attributes.RATES, {
      defaultValue: DEFAULT_RATES
    }));
    this.container = this.shadowRoot.querySelector('slot[name="icon"]');
    this.container.innerHTML = `${(_a = this.mediaPlaybackRate) != null ? _a : DEFAULT_RATE}x`;
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      Attributes.RATES
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === Attributes.RATES) {
      __privateGet(this, _rates).value = newValue;
    }
    if (attrName === MediaUIAttributes.MEDIA_PLAYBACK_RATE) {
      const newPlaybackRate = newValue ? +newValue : Number.NaN;
      const playbackRate = !Number.isNaN(newPlaybackRate) ? newPlaybackRate : DEFAULT_RATE;
      this.container.innerHTML = `${playbackRate}x`;
      this.setAttribute(
        "aria-label",
        t("Playback rate {playbackRate}", { playbackRate })
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
    return getNumericAttr(
      this,
      MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      DEFAULT_RATE
    );
  }
  set mediaPlaybackRate(value) {
    setNumericAttr(this, MediaUIAttributes.MEDIA_PLAYBACK_RATE, value);
  }
  handleClick() {
    var _a, _b;
    const availableRates = Array.from(__privateGet(this, _rates).values(), (str) => +str).sort(
      (a, b) => a - b
    );
    const detail = (_b = (_a = availableRates.find((r) => r > this.mediaPlaybackRate)) != null ? _a : availableRates[0]) != null ? _b : DEFAULT_RATE;
    const evt = new globalThis.CustomEvent(
      MediaUIEvents.MEDIA_PLAYBACK_RATE_REQUEST,
      { composed: true, bubbles: true, detail }
    );
    this.dispatchEvent(evt);
  }
}
_rates = new WeakMap();
MediaPlaybackRateButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPlaybackRateButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-playback-rate-button")) {
  globalThis.customElements.define(
    "media-playback-rate-button",
    MediaPlaybackRateButton
  );
}
var media_playback_rate_button_default = MediaPlaybackRateButton;
export {
  Attributes,
  DEFAULT_RATE,
  DEFAULT_RATES,
  media_playback_rate_button_default as default
};
