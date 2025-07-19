import { MediaUIAttributes } from "../constants.js";
import { MediaChromeMenuButton } from "./media-chrome-menu-button.js";
import { globalThis } from "../utils/server-safe-globals.js";
import {
  getStringAttr,
  setStringAttr,
  getMediaController
} from "../utils/element-utils.js";
import { t } from "../utils/i18n.js";
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
  return t("Audio");
}
class MediaAudioTrackMenuButton extends MediaChromeMenuButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED,
      MediaUIAttributes.MEDIA_AUDIO_TRACK_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    this.setAttribute("aria-label", t("Audio"));
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   * @return {HTMLElement | null}
   */
  get invokeTargetElement() {
    var _a;
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return (_a = getMediaController(this)) == null ? void 0 : _a.querySelector("media-audio-track-menu");
  }
  /**
   * Get enabled audio track id.
   * @return {string}
   */
  get mediaAudioTrackEnabled() {
    var _a;
    return (_a = getStringAttr(this, MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED)) != null ? _a : "";
  }
  set mediaAudioTrackEnabled(id) {
    setStringAttr(this, MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED, id);
  }
}
MediaAudioTrackMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaAudioTrackMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-audio-track-menu-button")) {
  globalThis.customElements.define(
    "media-audio-track-menu-button",
    MediaAudioTrackMenuButton
  );
}
var media_audio_track_menu_button_default = MediaAudioTrackMenuButton;
export {
  MediaAudioTrackMenuButton,
  media_audio_track_menu_button_default as default
};
