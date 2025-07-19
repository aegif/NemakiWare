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
var media_playback_rate_menu_button_exports = {};
__export(media_playback_rate_menu_button_exports, {
  DEFAULT_RATE: () => DEFAULT_RATE,
  MediaPlaybackRateMenuButton: () => MediaPlaybackRateMenuButton,
  default: () => media_playback_rate_menu_button_default
});
module.exports = __toCommonJS(media_playback_rate_menu_button_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_constants = require("../constants.js");
var import_media_chrome_menu_button = require("./media-chrome-menu-button.js");
var import_element_utils = require("../utils/element-utils.js");
var import_i18n = require("../utils/i18n.js");
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
      
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${attrs["mediaplaybackrate"] || DEFAULT_RATE}x</slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Playback rate");
}
class MediaPlaybackRateMenuButton extends import_media_chrome_menu_button.MediaChromeMenuButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE
    ];
  }
  constructor() {
    var _a;
    super();
    this.container = this.shadowRoot.querySelector('slot[name="icon"]');
    this.container.innerHTML = `${(_a = this.mediaPlaybackRate) != null ? _a : DEFAULT_RATE}x`;
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
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
   * Returns the element with the id specified by the `invoketarget` attribute.
   */
  get invokeTargetElement() {
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return (0, import_element_utils.getMediaController)(this).querySelector("media-playback-rate-menu");
  }
  /**
   * The current playback rate
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
}
MediaPlaybackRateMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPlaybackRateMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-playback-rate-menu-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-playback-rate-menu-button",
    MediaPlaybackRateMenuButton
  );
}
var media_playback_rate_menu_button_default = MediaPlaybackRateMenuButton;
