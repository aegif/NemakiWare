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
var _mediaController, _delay;
import {
  MediaUIAttributes,
  MediaStateReceiverAttributes
} from "./constants.js";
import { globalThis } from "./utils/server-safe-globals.js";
import {
  getBooleanAttr,
  setBooleanAttr,
  getOrInsertCSSRule,
  setStringAttr,
  getStringAttr,
  namedNodeMapToObject
} from "./utils/element-utils.js";
import { t } from "./utils/i18n.js";
const Attributes = {
  LOADING_DELAY: "loadingdelay",
  NO_AUTOHIDE: "noautohide"
};
const DEFAULT_LOADING_DELAY = 500;
const loadingIndicatorIcon = `
<svg aria-hidden="true" viewBox="0 0 100 100">
  <path d="M73,50c0-12.7-10.3-23-23-23S27,37.3,27,50 M30.9,50c0-10.5,8.5-19.1,19.1-19.1S69.1,39.5,69.1,50">
    <animateTransform
       attributeName="transform"
       attributeType="XML"
       type="rotate"
       dur="1s"
       from="0 50 50"
       to="360 50 50"
       repeatCount="indefinite" />
  </path>
</svg>
`;
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        display: var(--media-control-display, var(--media-loading-indicator-display, inline-block));
        vertical-align: middle;
        box-sizing: border-box;
        --_loading-indicator-delay: var(--media-loading-indicator-transition-delay, ${DEFAULT_LOADING_DELAY}ms);
      }

      #status {
        color: rgba(0,0,0,0);
        width: 0px;
        height: 0px;
      }

      :host slot[name=icon] > *,
      :host ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 0);
        transition: opacity 0.15s;
      }

      :host([${MediaUIAttributes.MEDIA_LOADING}]:not([${MediaUIAttributes.MEDIA_PAUSED}])) slot[name=icon] > *,
      :host([${MediaUIAttributes.MEDIA_LOADING}]:not([${MediaUIAttributes.MEDIA_PAUSED}])) ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 1);
        transition: opacity 0.15s var(--_loading-indicator-delay);
      }

      :host #status {
        visibility: var(--media-loading-indicator-opacity, hidden);
        transition: visibility 0.15s;
      }

      :host([${MediaUIAttributes.MEDIA_LOADING}]:not([${MediaUIAttributes.MEDIA_PAUSED}])) #status {
        visibility: var(--media-loading-indicator-opacity, visible);
        transition: visibility 0.15s var(--_loading-indicator-delay);
      }

      svg, img, ::slotted(svg), ::slotted(img) {
        width: var(--media-loading-indicator-icon-width);
        height: var(--media-loading-indicator-icon-height, 100px);
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        vertical-align: middle;
      }
    </style>

    <slot name="icon">${loadingIndicatorIcon}</slot>
    <div id="status" role="status" aria-live="polite">${t("media loading")}</div>
  `
  );
}
class MediaLoadingIndicator extends globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _mediaController, void 0);
    __privateAdd(this, _delay, DEFAULT_LOADING_DELAY);
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = namedNodeMapToObject(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
  }
  static get observedAttributes() {
    return [
      MediaStateReceiverAttributes.MEDIA_CONTROLLER,
      MediaUIAttributes.MEDIA_PAUSED,
      MediaUIAttributes.MEDIA_LOADING,
      Attributes.LOADING_DELAY
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a, _b, _c, _d, _e;
    if (attrName === Attributes.LOADING_DELAY && oldValue !== newValue) {
      this.loadingDelay = Number(newValue);
    } else if (attrName === MediaStateReceiverAttributes.MEDIA_CONTROLLER) {
      if (oldValue) {
        (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
        __privateSet(this, _mediaController, null);
      }
      if (newValue && this.isConnected) {
        __privateSet(this, _mediaController, (_c = this.getRootNode()) == null ? void 0 : _c.getElementById(newValue));
        (_e = (_d = __privateGet(this, _mediaController)) == null ? void 0 : _d.associateElement) == null ? void 0 : _e.call(_d, this);
      }
    }
  }
  connectedCallback() {
    var _a, _b, _c;
    const mediaControllerId = this.getAttribute(
      MediaStateReceiverAttributes.MEDIA_CONTROLLER
    );
    if (mediaControllerId) {
      __privateSet(this, _mediaController, (_a = this.getRootNode()) == null ? void 0 : _a.getElementById(
        mediaControllerId
      ));
      (_c = (_b = __privateGet(this, _mediaController)) == null ? void 0 : _b.associateElement) == null ? void 0 : _c.call(_b, this);
    }
  }
  disconnectedCallback() {
    var _a, _b;
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    __privateSet(this, _mediaController, null);
  }
  /**
   * Delay in ms
   */
  get loadingDelay() {
    return __privateGet(this, _delay);
  }
  set loadingDelay(delay) {
    __privateSet(this, _delay, delay);
    const { style } = getOrInsertCSSRule(this.shadowRoot, ":host");
    style.setProperty(
      "--_loading-indicator-delay",
      `var(--media-loading-indicator-transition-delay, ${delay}ms)`
    );
  }
  /**
   * Is the media paused
   */
  get mediaPaused() {
    return getBooleanAttr(this, MediaUIAttributes.MEDIA_PAUSED);
  }
  set mediaPaused(value) {
    setBooleanAttr(this, MediaUIAttributes.MEDIA_PAUSED, value);
  }
  /**
   * Is the media loading
   */
  get mediaLoading() {
    return getBooleanAttr(this, MediaUIAttributes.MEDIA_LOADING);
  }
  set mediaLoading(value) {
    setBooleanAttr(this, MediaUIAttributes.MEDIA_LOADING, value);
  }
  get mediaController() {
    return getStringAttr(this, MediaStateReceiverAttributes.MEDIA_CONTROLLER);
  }
  set mediaController(value) {
    setStringAttr(this, MediaStateReceiverAttributes.MEDIA_CONTROLLER, value);
  }
  get noAutohide() {
    return getBooleanAttr(this, Attributes.NO_AUTOHIDE);
  }
  set noAutohide(value) {
    setBooleanAttr(this, Attributes.NO_AUTOHIDE, value);
  }
}
_mediaController = new WeakMap();
_delay = new WeakMap();
MediaLoadingIndicator.shadowRootOptions = { mode: "open" };
MediaLoadingIndicator.getTemplateHTML = getTemplateHTML;
if (!globalThis.customElements.get("media-loading-indicator")) {
  globalThis.customElements.define(
    "media-loading-indicator",
    MediaLoadingIndicator
  );
}
var media_loading_indicator_default = MediaLoadingIndicator;
export {
  Attributes,
  media_loading_indicator_default as default
};
