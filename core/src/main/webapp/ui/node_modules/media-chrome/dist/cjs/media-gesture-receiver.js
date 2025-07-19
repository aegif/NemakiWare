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
var media_gesture_receiver_exports = {};
__export(media_gesture_receiver_exports, {
  default: () => media_gesture_receiver_default
});
module.exports = __toCommonJS(media_gesture_receiver_exports);
var import_constants = require("./constants.js");
var import_element_utils = require("./utils/element-utils.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var _mediaController;
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        display: var(--media-control-display, var(--media-gesture-receiver-display, inline-block));
        box-sizing: border-box;
      }
    </style>
  `
  );
}
class MediaGestureReceiver extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _mediaController, void 0);
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
  }
  // NOTE: Currently "baking in" actions + attrs until we come up with
  // a more robust architecture (CJP)
  static get observedAttributes() {
    return [
      import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER,
      import_constants.MediaUIAttributes.MEDIA_PAUSED
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a, _b, _c, _d, _e;
    if (attrName === import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER) {
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
    var _a, _b, _c, _d;
    this.tabIndex = -1;
    this.setAttribute("aria-hidden", "true");
    __privateSet(this, _mediaController, getMediaControllerEl(this));
    if (this.getAttribute(import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER)) {
      (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.associateElement) == null ? void 0 : _b.call(_a, this);
    }
    (_c = __privateGet(this, _mediaController)) == null ? void 0 : _c.addEventListener("pointerdown", this);
    (_d = __privateGet(this, _mediaController)) == null ? void 0 : _d.addEventListener("click", this);
  }
  disconnectedCallback() {
    var _a, _b, _c, _d;
    if (this.getAttribute(import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER)) {
      (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    }
    (_c = __privateGet(this, _mediaController)) == null ? void 0 : _c.removeEventListener("pointerdown", this);
    (_d = __privateGet(this, _mediaController)) == null ? void 0 : _d.removeEventListener("click", this);
    __privateSet(this, _mediaController, null);
  }
  handleEvent(event) {
    var _a;
    const composedTarget = (_a = event.composedPath()) == null ? void 0 : _a[0];
    const allowList = ["video", "media-controller"];
    if (!allowList.includes(composedTarget == null ? void 0 : composedTarget.localName))
      return;
    if (event.type === "pointerdown") {
      this._pointerType = event.pointerType;
    } else if (event.type === "click") {
      const { clientX, clientY } = event;
      const { left, top, width, height } = this.getBoundingClientRect();
      const x = clientX - left;
      const y = clientY - top;
      if (x < 0 || y < 0 || x > width || y > height || // In case this element has no dimensions (or display: none) return.
      width === 0 && height === 0) {
        return;
      }
      const { pointerType = this._pointerType } = event;
      this._pointerType = void 0;
      if (pointerType === import_constants.PointerTypes.TOUCH) {
        this.handleTap(event);
        return;
      } else if (pointerType === import_constants.PointerTypes.MOUSE) {
        this.handleMouseClick(event);
        return;
      }
    }
  }
  /**
   * @type {boolean} Is the media paused
   */
  get mediaPaused() {
    return (0, import_element_utils.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED);
  }
  set mediaPaused(value) {
    (0, import_element_utils.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED, value);
  }
  // NOTE: Currently "baking in" actions + attrs until we come up with
  // a more robust architecture (CJP)
  /**
   * @abstract
   * @argument {Event} e
   */
  handleTap(e) {
  }
  // eslint-disable-line
  // eslint-disable-next-line
  handleMouseClick(e) {
    const eventName = this.mediaPaused ? import_constants.MediaUIEvents.MEDIA_PLAY_REQUEST : import_constants.MediaUIEvents.MEDIA_PAUSE_REQUEST;
    this.dispatchEvent(
      new import_server_safe_globals.globalThis.CustomEvent(eventName, { composed: true, bubbles: true })
    );
  }
}
_mediaController = new WeakMap();
MediaGestureReceiver.shadowRootOptions = { mode: "open" };
MediaGestureReceiver.getTemplateHTML = getTemplateHTML;
function getMediaControllerEl(controlEl) {
  var _a;
  const mediaControllerId = controlEl.getAttribute(
    import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER
  );
  if (mediaControllerId) {
    return (_a = controlEl.getRootNode()) == null ? void 0 : _a.getElementById(mediaControllerId);
  }
  return (0, import_element_utils.closestComposedNode)(controlEl, "media-controller");
}
if (!import_server_safe_globals.globalThis.customElements.get("media-gesture-receiver")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-gesture-receiver",
    MediaGestureReceiver
  );
}
var media_gesture_receiver_default = MediaGestureReceiver;
