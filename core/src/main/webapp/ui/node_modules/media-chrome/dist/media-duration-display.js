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
function getSlotTemplateHTML(_attrs, props) {
  return (
    /*html*/
    `
    <slot>${formatTime(props.mediaDuration)}</slot>
  `
  );
}
class MediaDurationDisplay extends MediaTextDisplay {
  constructor() {
    var _a;
    super();
    __privateAdd(this, _slot, void 0);
    __privateSet(this, _slot, this.shadowRoot.querySelector("slot"));
    __privateGet(this, _slot).textContent = formatTime((_a = this.mediaDuration) != null ? _a : 0);
  }
  static get observedAttributes() {
    return [...super.observedAttributes, MediaUIAttributes.MEDIA_DURATION];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === MediaUIAttributes.MEDIA_DURATION) {
      __privateGet(this, _slot).textContent = formatTime(+newValue);
    }
    super.attributeChangedCallback(attrName, oldValue, newValue);
  }
  /**
   * @type {number | undefined} In seconds
   */
  get mediaDuration() {
    return getNumericAttr(this, MediaUIAttributes.MEDIA_DURATION);
  }
  set mediaDuration(time) {
    setNumericAttr(this, MediaUIAttributes.MEDIA_DURATION, time);
  }
}
_slot = new WeakMap();
MediaDurationDisplay.getSlotTemplateHTML = getSlotTemplateHTML;
if (!globalThis.customElements.get("media-duration-display")) {
  globalThis.customElements.define(
    "media-duration-display",
    MediaDurationDisplay
  );
}
var media_duration_display_default = MediaDurationDisplay;
export {
  media_duration_display_default as default
};
