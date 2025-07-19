import { MediaChromeMenuButton } from "./media-chrome-menu-button.js";
import { globalThis } from "../utils/server-safe-globals.js";
import { getMediaController } from "../utils/element-utils.js";
import { t } from "../utils/i18n.js";
function getSlotTemplateHTML() {
  return (
    /*html*/
    `
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">
      <svg aria-hidden="true" viewBox="0 0 24 24">
        <path d="M4.5 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/>
      </svg>
    </slot>
  `
  );
}
function getTooltipContentHTML() {
  return t("Settings");
}
class MediaSettingsMenuButton extends MediaChromeMenuButton {
  static get observedAttributes() {
    return [...super.observedAttributes, "target"];
  }
  connectedCallback() {
    super.connectedCallback();
    this.setAttribute("aria-label", t("settings"));
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute.
   * @return {HTMLElement | null}
   */
  get invokeTargetElement() {
    if (this.invokeTarget != void 0)
      return super.invokeTargetElement;
    return getMediaController(this).querySelector("media-settings-menu");
  }
}
MediaSettingsMenuButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaSettingsMenuButton.getTooltipContentHTML = getTooltipContentHTML;
if (!globalThis.customElements.get("media-settings-menu-button")) {
  globalThis.customElements.define(
    "media-settings-menu-button",
    MediaSettingsMenuButton
  );
}
var media_settings_menu_button_default = MediaSettingsMenuButton;
export {
  MediaSettingsMenuButton,
  media_settings_menu_button_default as default
};
