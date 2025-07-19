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
var __privateMethod = (obj, member, method) => {
  __accessCheck(obj, member, "access private method");
  return method;
};
var media_playback_rate_menu_exports = {};
__export(media_playback_rate_menu_exports, {
  Attributes: () => Attributes,
  MediaPlaybackRateMenu: () => MediaPlaybackRateMenu,
  default: () => media_playback_rate_menu_default
});
module.exports = __toCommonJS(media_playback_rate_menu_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_constants = require("../constants.js");
var import_attribute_token_list = require("../utils/attribute-token-list.js");
var import_element_utils = require("../utils/element-utils.js");
var import_media_playback_rate_button = require("../media-playback-rate-button.js");
var import_media_chrome_menu = require("./media-chrome-menu.js");
var _rates, _render, render_fn, _onChange, onChange_fn;
const Attributes = {
  RATES: "rates"
};
class MediaPlaybackRateMenu extends import_media_chrome_menu.MediaChromeMenu {
  constructor() {
    super();
    __privateAdd(this, _render);
    __privateAdd(this, _onChange);
    __privateAdd(this, _rates, new import_attribute_token_list.AttributeTokenList(this, Attributes.RATES, {
      defaultValue: import_media_playback_rate_button.DEFAULT_RATES
    }));
    __privateMethod(this, _render, render_fn).call(this);
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
    if (attrName === import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE && oldValue != newValue) {
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
    return (0, import_element_utils.getMediaController)(this).querySelector(
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
    return (0, import_element_utils.getNumericAttr)(
      this,
      import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      import_media_playback_rate_button.DEFAULT_RATE
    );
  }
  set mediaPlaybackRate(value) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE, value);
  }
}
_rates = new WeakMap();
_render = new WeakSet();
render_fn = function() {
  this.defaultSlot.textContent = "";
  for (const rate of __privateGet(this, _rates)) {
    const item = (0, import_media_chrome_menu.createMenuItem)({
      type: "radio",
      text: this.formatMenuItemText(`${rate}x`, rate),
      value: rate,
      checked: this.mediaPlaybackRate === Number(rate)
    });
    item.prepend((0, import_media_chrome_menu.createIndicator)(this, "checked-indicator"));
    this.defaultSlot.append(item);
  }
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (!this.value)
    return;
  const event = new import_server_safe_globals.globalThis.CustomEvent(
    import_constants.MediaUIEvents.MEDIA_PLAYBACK_RATE_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!import_server_safe_globals.globalThis.customElements.get("media-playback-rate-menu")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-playback-rate-menu",
    MediaPlaybackRateMenu
  );
}
var media_playback_rate_menu_default = MediaPlaybackRateMenu;
