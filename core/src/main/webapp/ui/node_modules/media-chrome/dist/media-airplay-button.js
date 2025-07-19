import { MediaChromeButton } from "./media-chrome-button.js";
import { globalThis } from "./utils/server-safe-globals.js";
import { MediaUIEvents, MediaUIAttributes } from "./constants.js";
import { t } from "./utils/i18n.js";
import {
  getStringAttr,
  setStringAttr,
  getBooleanAttr,
  setBooleanAttr
} from "./utils/element-utils.js";
const airplayIcon = `<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.13 3H3.87a.87.87 0 0 0-.87.87v13.26a.87.87 0 0 0 .87.87h3.4L9 16H5V5h16v11h-4l1.72 2h3.4a.87.87 0 0 0 .87-.87V3.87a.87.87 0 0 0-.86-.87Zm-8.75 11.44a.5.5 0 0 0-.76 0l-4.91 5.73a.5.5 0 0 0 .38.83h9.82a.501.501 0 0 0 .38-.83l-4.91-5.73Z"/>
</svg>
`;
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host([${MediaUIAttributes.MEDIA_IS_AIRPLAYING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      ${/* Double negative, but safer if display doesn't equal 'block' */
    ""}
      :host(:not([${MediaUIAttributes.MEDIA_IS_AIRPLAYING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${MediaUIAttributes.MEDIA_IS_AIRPLAYING}]) slot[name=tooltip-enter],
      :host(:not([${MediaUIAttributes.MEDIA_IS_AIRPLAYING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${airplayIcon}</slot>
      <slot name="exit">${airplayIcon}</slot>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return (
    /*html*/
    `
    <slot name="tooltip-enter">${t("start airplay")}</slot>
    <slot name="tooltip-exit">${t("stop airplay")}</slot>
  `
  );
}
const updateAriaLabel = (el) => {
  const label = el.mediaIsAirplaying ? t("stop airplay") : t("start airplay");
  el.setAttribute("aria-label", label);
};
class MediaAirplayButton extends MediaChromeButton {
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_IS_AIRPLAYING,
      MediaUIAttributes.MEDIA_AIRPLAY_UNAVAILABLE
    ];
  }
  connectedCallback() {
    super.connectedCallback();
    updateAriaLabel(this);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === MediaUIAttributes.MEDIA_IS_AIRPLAYING) {
      updateAriaLabel(this);
    }
  }
  /**
   * Are we currently airplaying
   */
  get mediaIsAirplaying() {
    return getBooleanAttr(this, MediaUIAttributes.MEDIA_IS_AIRPLAYING);
  }
  set mediaIsAirplaying(value) {
    setBooleanAttr(this, MediaUIAttributes.MEDIA_IS_AIRPLAYING, value);
  }
  /**
   * Airplay unavailability state
   */
  get mediaAirplayUnavailable() {
    return getStringAttr(this, MediaUIAttributes.MEDIA_AIRPLAY_UNAVAILABLE);
  }
  set mediaAirplayUnavailable(value) {
    setStringAttr(this, MediaUIAttributes.MEDIA_AIRPLAY_UNAVAILABLE, value);
  }
  handleClick() {
    const evt = new globalThis.CustomEvent(
      MediaUIEvents.MEDIA_AIRPLAY_REQUEST,
      {
        composed: true,
        bubbles: true
      }
    );
    this.dispatchEvent(evt);
  }
}
MediaAirplayButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaAirplayButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-airplay-button")) {
  globalThis.customElements.define("media-airplay-button", MediaAirplayButton);
}
var media_airplay_button_default = MediaAirplayButton;
export {
  media_airplay_button_default as default
};
