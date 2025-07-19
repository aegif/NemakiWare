import { MediaChromeButton } from "./media-chrome-button.js";
import { globalThis } from "./utils/server-safe-globals.js";
import { MediaUIEvents, MediaUIAttributes } from "./constants.js";
import { getNumericAttr, setNumericAttr, getSlotted, updateIconText } from "./utils/element-utils.js";
import { t } from "./utils/i18n.js";
const Attributes = {
  SEEK_OFFSET: "seekoffset"
};
const DEFAULT_SEEK_OFFSET = 30;
const backwardIcon = (seekOffset) => `
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(2.18 19.87)">${seekOffset}</text>
    <path d="M10 6V3L4.37 7 10 10.94V8a5.54 5.54 0 0 1 1.9 10.48v2.12A7.5 7.5 0 0 0 10 6Z"/>
  </svg>`;
function getSlotTemplateHTML(_attrs, props) {
  return (
    /*html*/
    `
    <slot name="icon">${backwardIcon(props.seekOffset)}</slot>
  `
  );
}
function getTooltipContentHTML() {
  return t("Seek backward");
}
const DEFAULT_TIME = 0;
class MediaSeekBackwardButton extends MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_CURRENT_TIME,
      Attributes.SEEK_OFFSET
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    this.seekOffset = getNumericAttr(
      this,
      Attributes.SEEK_OFFSET,
      DEFAULT_SEEK_OFFSET
    );
  }
  attributeChangedCallback(attrName, _oldValue, newValue) {
    super.attributeChangedCallback(attrName, _oldValue, newValue);
    if (attrName === Attributes.SEEK_OFFSET) {
      this.seekOffset = getNumericAttr(
        this,
        Attributes.SEEK_OFFSET,
        DEFAULT_SEEK_OFFSET
      );
    }
  }
  // Own props
  /**
   * Seek amount in seconds
   */
  get seekOffset() {
    return getNumericAttr(this, Attributes.SEEK_OFFSET, DEFAULT_SEEK_OFFSET);
  }
  set seekOffset(value) {
    setNumericAttr(this, Attributes.SEEK_OFFSET, value);
    this.setAttribute(
      "aria-label",
      t("seek back {seekOffset} seconds", { seekOffset: this.seekOffset })
    );
    updateIconText(getSlotted(this, "icon"), this.seekOffset);
  }
  // Props derived from Media UI Attributes
  /**
   * The current time in seconds
   */
  get mediaCurrentTime() {
    return getNumericAttr(
      this,
      MediaUIAttributes.MEDIA_CURRENT_TIME,
      DEFAULT_TIME
    );
  }
  set mediaCurrentTime(time) {
    setNumericAttr(this, MediaUIAttributes.MEDIA_CURRENT_TIME, time);
  }
  handleClick() {
    const detail = Math.max(this.mediaCurrentTime - this.seekOffset, 0);
    const evt = new globalThis.CustomEvent(MediaUIEvents.MEDIA_SEEK_REQUEST, {
      composed: true,
      bubbles: true,
      detail
    });
    this.dispatchEvent(evt);
  }
}
MediaSeekBackwardButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaSeekBackwardButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-seek-backward-button")) {
  globalThis.customElements.define(
    "media-seek-backward-button",
    MediaSeekBackwardButton
  );
}
var media_seek_backward_button_default = MediaSeekBackwardButton;
export {
  Attributes,
  media_seek_backward_button_default as default
};
