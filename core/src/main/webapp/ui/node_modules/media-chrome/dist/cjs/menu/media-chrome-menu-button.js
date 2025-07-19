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
var media_chrome_menu_button_exports = {};
__export(media_chrome_menu_button_exports, {
  MediaChromeMenuButton: () => MediaChromeMenuButton,
  default: () => media_chrome_menu_button_default
});
module.exports = __toCommonJS(media_chrome_menu_button_exports);
var import_media_chrome_button = require("../media-chrome-button.js");
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_events = require("../utils/events.js");
var import_element_utils = require("../utils/element-utils.js");
class MediaChromeMenuButton extends import_media_chrome_button.MediaChromeButton {
  connectedCallback() {
    super.connectedCallback();
    if (this.invokeTargetElement) {
      this.setAttribute("aria-haspopup", "menu");
    }
  }
  get invokeTarget() {
    return this.getAttribute("invoketarget");
  }
  set invokeTarget(value) {
    this.setAttribute("invoketarget", `${value}`);
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   * @return {HTMLElement | null}
   */
  get invokeTargetElement() {
    var _a;
    if (this.invokeTarget) {
      return (_a = (0, import_element_utils.getDocumentOrShadowRoot)(this)) == null ? void 0 : _a.querySelector(
        `#${this.invokeTarget}`
      );
    }
    return null;
  }
  handleClick() {
    var _a;
    (_a = this.invokeTargetElement) == null ? void 0 : _a.dispatchEvent(
      new import_events.InvokeEvent({ relatedTarget: this })
    );
  }
}
if (!import_server_safe_globals.globalThis.customElements.get("media-chrome-menu-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-chrome-menu-button",
    MediaChromeMenuButton
  );
}
var media_chrome_menu_button_default = MediaChromeMenuButton;
