import { globalThis } from "../utils/server-safe-globals.js";
import { MediaUIAttributes } from "../constants.js";
import { MediaChromeMenuButton } from "./media-chrome-menu-button.js";
import {
  getNumericAttr,
  setNumericAttr,
  getMediaController
} from "../utils/element-utils.js";
import { t } from "../utils/i18n.js";
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
  return t("Playback rate");
}
class MediaPlaybackRateMenuButton extends MediaChromeMenuButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_PLAYBACK_RATE
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
   * Returns the element with the id specified by the `invoketarget` attribute.
   */
  get invokeTargetElement() {
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return getMediaController(this).querySelector("media-playback-rate-menu");
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
MediaPlaybackRateMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaPlaybackRateMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-playback-rate-menu-button")) {
  globalThis.customElements.define(
    "media-playback-rate-menu-button",
    MediaPlaybackRateMenuButton
  );
}
var media_playback_rate_menu_button_default = MediaPlaybackRateMenuButton;
export {
  DEFAULT_RATE,
  MediaPlaybackRateMenuButton,
  media_playback_rate_menu_button_default as default
};
