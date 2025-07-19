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
var __privateMethod = (obj, member, method) => {
  __accessCheck(obj, member, "access private method");
  return method;
};
var _rates, _render, render_fn, _onChange, onChange_fn;
import { globalThis } from "../utils/server-safe-globals.js";
import { MediaUIAttributes, MediaUIEvents } from "../constants.js";
import { AttributeTokenList } from "../utils/attribute-token-list.js";
import {
  getNumericAttr,
  setNumericAttr,
  getMediaController
} from "../utils/element-utils.js";
import { DEFAULT_RATES, DEFAULT_RATE } from "../media-playback-rate-button.js";
import {
  MediaChromeMenu,
  createMenuItem,
  createIndicator
} from "./media-chrome-menu.js";
const Attributes = {
  RATES: "rates"
};
class MediaPlaybackRateMenu extends MediaChromeMenu {
  constructor() {
    super();
    __privateAdd(this, _render);
    __privateAdd(this, _onChange);
    __privateAdd(this, _rates, new AttributeTokenList(this, Attributes.RATES, {
      defaultValue: DEFAULT_RATES
    }));
    __privateMethod(this, _render, render_fn).call(this);
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
    if (attrName === MediaUIAttributes.MEDIA_PLAYBACK_RATE && oldValue != newValue) {
      this.value = newValue;
    } else if (attrName === Attributes.RATES && oldValue != newValue) {
      __privateGet(this, _rates).value = newValue;
      __privateMethod(this, _render, render_fn).call(this);
    }
  }
  connectedCallback() {
    super.connectedCallback();
    this.addEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  /**
   * Returns the anchor element when it is a floating menu.
   */
  get anchorElement() {
    if (this.anchor !== "auto")
      return super.anchorElement;
    return getMediaController(this).querySelector(
      "media-playback-rate-menu-button"
    );
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
    __privateMethod(this, _render, render_fn).call(this);
  }
  /**
   * The current playback rate
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
}
_rates = new WeakMap();
_render = new WeakSet();
render_fn = function() {
  this.defaultSlot.textContent = "";
  for (const rate of __privateGet(this, _rates)) {
    const item = createMenuItem({
      type: "radio",
      text: this.formatMenuItemText(`${rate}x`, rate),
      value: rate,
      checked: this.mediaPlaybackRate === Number(rate)
    });
    item.prepend(createIndicator(this, "checked-indicator"));
    this.defaultSlot.append(item);
  }
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (!this.value)
    return;
  const event = new globalThis.CustomEvent(
    MediaUIEvents.MEDIA_PLAYBACK_RATE_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!globalThis.customElements.get("media-playback-rate-menu")) {
  globalThis.customElements.define(
    "media-playback-rate-menu",
    MediaPlaybackRateMenu
  );
}
var media_playback_rate_menu_default = MediaPlaybackRateMenu;
export {
  Attributes,
  MediaPlaybackRateMenu,
  media_playback_rate_menu_default as default
};
