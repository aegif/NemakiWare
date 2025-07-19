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
var __privateSet = (obj, member, value, setter) => {
  __accessCheck(obj, member, "write to private field");
  setter ? setter.call(obj, value) : member.set(obj, value);
  return value;
};
var _slot;
import { MediaTextDisplay } from "./media-text-display.js";
import { globalThis } from "./utils/server-safe-globals.js";
import { formatTime } from "./utils/time.js";
import { MediaUIAttributes } from "./constants.js";
import { getNumericAttr, setNumericAttr } from "./utils/element-utils.js";
class MediaPreviewTimeDisplay extends MediaTextDisplay {
  constructor() {
    super();
    __privateAdd(this, _slot, void 0);
    __privateSet(this, _slot, this.shadowRoot.querySelector("slot"));
    __privateGet(this, _slot).textContent = formatTime(0);
  }
  static get observedAttributes() {
    return [...super.observedAttributes, MediaUIAttributes.MEDIA_PREVIEW_TIME];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === MediaUIAttributes.MEDIA_PREVIEW_TIME && newValue != null) {
      __privateGet(this, _slot).textContent = formatTime(parseFloat(newValue));
    }
  }
  /**
   * Timeline preview time
   */
  get mediaPreviewTime() {
    return getNumericAttr(this, MediaUIAttributes.MEDIA_PREVIEW_TIME);
  }
  set mediaPreviewTime(value) {
    setNumericAttr(this, MediaUIAttributes.MEDIA_PREVIEW_TIME, value);
  }
}
_slot = new WeakMap();
if (!globalThis.customElements.get("media-preview-time-display")) {
  globalThis.customElements.define(
    "media-preview-time-display",
    MediaPreviewTimeDisplay
  );
}
var media_preview_time_display_default = MediaPreviewTimeDisplay;
export {
  media_preview_time_display_default as default
};
