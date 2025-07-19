import { MediaChromeButton } from "../media-chrome-button.js";
import { globalThis } from "../utils/server-safe-globals.js";
import { InvokeEvent } from "../utils/events.js";
import { getDocumentOrShadowRoot } from "../utils/element-utils.js";
class MediaChromeMenuButton extends MediaChromeButton {
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
      return (_a = getDocumentOrShadowRoot(this)) == null ? void 0 : _a.querySelector(
        `#${this.invokeTarget}`
      );
    }
    return null;
  }
  handleClick() {
    var _a;
    (_a = this.invokeTargetElement) == null ? void 0 : _a.dispatchEvent(
      new InvokeEvent({ relatedTarget: this })
    );
  }
}
if (!globalThis.customElements.get("media-chrome-menu-button")) {
  globalThis.customElements.define(
    "media-chrome-menu-button",
    MediaChromeMenuButton
  );
}
var media_chrome_menu_button_default = MediaChromeMenuButton;
export {
  MediaChromeMenuButton,
  media_chrome_menu_button_default as default
};
