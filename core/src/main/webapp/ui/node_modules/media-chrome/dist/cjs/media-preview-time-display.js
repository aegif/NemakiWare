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
var __privateSet = (obj, member, value, setter) => {
  __accessCheck(obj, member, "write to private field");
  setter ? setter.call(obj, value) : member.set(obj, value);
  return value;
};
var media_preview_time_display_exports = {};
__export(media_preview_time_display_exports, {
  default: () => media_preview_time_display_default
});
module.exports = __toCommonJS(media_preview_time_display_exports);
var import_media_text_display = require("./media-text-display.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_time = require("./utils/time.js");
var import_constants = require("./constants.js");
var import_element_utils = require("./utils/element-utils.js");
var _slot;
class MediaPreviewTimeDisplay extends import_media_text_display.MediaTextDisplay {
  constructor() {
    super();
    __privateAdd(this, _slot, void 0);
    __privateSet(this, _slot, this.shadowRoot.querySelector("slot"));
    __privateGet(this, _slot).textContent = (0, import_time.formatTime)(0);
  }
  static get observedAttributes() {
    return [...super.observedAttributes, import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME && newValue != null) {
      __privateGet(this, _slot).textContent = (0, import_time.formatTime)(parseFloat(newValue));
    }
  }
  /**
   * Timeline preview time
   */
  get mediaPreviewTime() {
    return (0, import_element_utils.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME);
  }
  set mediaPreviewTime(value) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME, value);
  }
}
_slot = new WeakMap();
if (!import_server_safe_globals.globalThis.customElements.get("media-preview-time-display")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-preview-time-display",
    MediaPreviewTimeDisplay
  );
}
var media_preview_time_display_default = MediaPreviewTimeDisplay;
