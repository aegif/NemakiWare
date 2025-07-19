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
var media_audio_track_menu_button_exports = {};
__export(media_audio_track_menu_button_exports, {
  MediaAudioTrackMenuButton: () => MediaAudioTrackMenuButton,
  default: () => media_audio_track_menu_button_default
});
module.exports = __toCommonJS(media_audio_track_menu_button_exports);
var import_constants = require("../constants.js");
var import_media_chrome_menu_button = require("./media-chrome-menu-button.js");
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_element_utils = require("../utils/element-utils.js");
var import_i18n = require("../utils/i18n.js");
const audioTrackIcon = (
  /*html*/
  `<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M11 17H9.5V7H11v10Zm-3-3H6.5v-4H8v4Zm6-5h-1.5v6H14V9Zm3 7h-1.5V8H17v8Z"/>
  <path d="M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Zm-2 0a8 8 0 1 0-16 0 8 8 0 0 0 16 0Z"/>
</svg>`
);
function getSlotTemplateHTML() {
  return (
    /*html*/
    `
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${audioTrackIcon}</slot>
  `
  );
}
function getTooltipContentHTML() {
  return (0, import_i18n.t)("Audio");
}
class MediaAudioTrackMenuButton extends import_media_chrome_menu_button.MediaChromeMenuButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED,
      import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    this.setAttribute("aria-label", (0, import_i18n.t)("Audio"));
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   * @return {HTMLElement | null}
   */
  get invokeTargetElement() {
    var _a;
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return (_a = (0, import_element_utils.getMediaController)(this)) == null ? void 0 : _a.querySelector("media-audio-track-menu");
  }
  /**
   * Get enabled audio track id.
   * @return {string}
   */
  get mediaAudioTrackEnabled() {
    var _a;
    return (_a = (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED)) != null ? _a : "";
  }
  set mediaAudioTrackEnabled(id) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED, id);
  }
}
MediaAudioTrackMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaAudioTrackMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-audio-track-menu-button")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-audio-track-menu-button",
    MediaAudioTrackMenuButton
  );
}
var media_audio_track_menu_button_default = MediaAudioTrackMenuButton;
