var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
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
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
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
var __privateMethod = (obj, member, method) => {
  __accessCheck(obj, member, "access private method");
  return method;
};
var media_chrome_button_exports = {};
__export(media_chrome_button_exports, {
  MediaChromeButton: () => MediaChromeButton,
  default: () => media_chrome_button_default
});
module.exports = __toCommonJS(media_chrome_button_exports);
var import_constants = require("./constants.js");
var import_media_tooltip = __toESM(require("./media-tooltip.js"), 1);
var import_element_utils = require("./utils/element-utils.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var _mediaController, _clickListener, _positionTooltip, _keyupListener, _keydownListener, _setupTooltip, setupTooltip_fn;
const Attributes = {
  TOOLTIP_PLACEMENT: "tooltipplacement",
  DISABLED: "disabled",
  NO_TOOLTIP: "notooltip"
};
function getTemplateHTML(_attrs, _props = {}) {
  return (
    /*html*/
    `
    <style>
      :host {
        position: relative;
        font: var(--media-font,
          var(--media-font-weight, bold)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        padding: var(--media-button-padding, var(--media-control-padding, 10px));
        justify-content: var(--media-button-justify-content, center);
        display: inline-flex;
        align-items: center;
        vertical-align: middle;
        box-sizing: border-box;
        transition: background .15s linear;
        pointer-events: auto;
        cursor: var(--media-cursor, pointer);
        -webkit-tap-highlight-color: transparent;
      }

      ${/*
      Only show outline when keyboard focusing.
      https://drafts.csswg.org/selectors-4/#the-focus-visible-pseudo
    */
    ""}
      :host(:focus-visible) {
        box-shadow: inset 0 0 0 2px rgb(27 127 204 / .9);
        outline: 0;
      }
      ${/*
    * hide default focus ring, particularly when using mouse
    */
    ""}
      :host(:where(:focus)) {
        box-shadow: none;
        outline: 0;
      }

      :host(:hover) {
        background: var(--media-control-hover-background, rgba(50 50 70 / .7));
      }

      svg, img, ::slotted(svg), ::slotted(img) {
        width: var(--media-button-icon-width);
        height: var(--media-button-icon-height, var(--media-control-height, 24px));
        transform: var(--media-button-icon-transform);
        transition: var(--media-button-icon-transition);
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        vertical-align: middle;
        max-width: 100%;
        max-height: 100%;
        min-width: 100%;
      }

      media-tooltip {
        ${/** Make sure unpositioned tooltip doesn't cause page overflow (scroll). */
    ""}
        max-width: 0;
        overflow-x: clip;
        opacity: 0;
        transition: opacity .3s, max-width 0s 9s;
      }

      :host(:hover) media-tooltip,
      :host(:focus-visible) media-tooltip {
        max-width: 100vw;
        opacity: 1;
        transition: opacity .3s;
      }

      :host([notooltip]) slot[name="tooltip"] {
        display: none;
      }
    </style>

    ${this.getSlotTemplateHTML(_attrs, _props)}

    <slot name="tooltip">
      <media-tooltip part="tooltip" aria-hidden="true">
        <template shadowrootmode="${import_media_tooltip.default.shadowRootOptions.mode}">
          ${import_media_tooltip.default.getTemplateHTML({})}
        </template>
        <slot name="tooltip-content">
          ${this.getTooltipContentHTML(_attrs)}
        </slot>
      </media-tooltip>
    </slot>
  `
  );
}
function getSlotTemplateHTML(_attrs, _props) {
  return (
    /*html*/
    `
    <slot></slot>
  `
  );
}
function getTooltipContentHTML() {
  return "";
}
class MediaChromeButton extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    // Called when we know the tooltip is ready / defined
    __privateAdd(this, _setupTooltip);
    __privateAdd(this, _mediaController, void 0);
    this.preventClick = false;
    this.tooltipEl = null;
    __privateAdd(this, _clickListener, (e) => {
      if (!this.preventClick) {
        this.handleClick(e);
      }
      setTimeout(__privateGet(this, _positionTooltip), 0);
    });
    __privateAdd(this, _positionTooltip, () => {
      var _a, _b;
      (_b = (_a = this.tooltipEl) == null ? void 0 : _a.updateXOffset) == null ? void 0 : _b.call(_a);
    });
    // NOTE: There are definitely some "false positive" cases with multi-key pressing,
    // but this should be good enough for most use cases.
    __privateAdd(this, _keyupListener, (e) => {
      const { key } = e;
      if (!this.keysUsed.includes(key)) {
        this.removeEventListener("keyup", __privateGet(this, _keyupListener));
        return;
      }
      if (!this.preventClick) {
        this.handleClick(e);
      }
    });
    __privateAdd(this, _keydownListener, (e) => {
      const { metaKey, altKey, key } = e;
      if (metaKey || altKey || !this.keysUsed.includes(key)) {
        this.removeEventListener("keyup", __privateGet(this, _keyupListener));
        return;
      }
      this.addEventListener("keyup", __privateGet(this, _keyupListener), { once: true });
    });
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      const html = this.constructor.getTemplateHTML(attrs);
      this.shadowRoot.setHTMLUnsafe ? this.shadowRoot.setHTMLUnsafe(html) : this.shadowRoot.innerHTML = html;
    }
    this.tooltipEl = this.shadowRoot.querySelector("media-tooltip");
  }
  static get observedAttributes() {
    return [
      "disabled",
      Attributes.TOOLTIP_PLACEMENT,
      import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER
    ];
  }
  enable() {
    this.addEventListener("click", __privateGet(this, _clickListener));
    this.addEventListener("keydown", __privateGet(this, _keydownListener));
    this.tabIndex = 0;
  }
  disable() {
    this.removeEventListener("click", __privateGet(this, _clickListener));
    this.removeEventListener("keydown", __privateGet(this, _keydownListener));
    this.removeEventListener("keyup", __privateGet(this, _keyupListener));
    this.tabIndex = -1;
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
    } else if (attrName === "disabled" && newValue !== oldValue) {
      if (newValue == null) {
        this.enable();
      } else {
        this.disable();
      }
    } else if (attrName === Attributes.TOOLTIP_PLACEMENT && this.tooltipEl && newValue !== oldValue) {
      this.tooltipEl.placement = newValue;
    }
    __privateGet(this, _positionTooltip).call(this);
  }
  connectedCallback() {
    var _a, _b, _c;
    const { style } = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, ":host");
    style.setProperty(
      "display",
      `var(--media-control-display, var(--${this.localName}-display, inline-flex))`
    );
    if (!this.hasAttribute("disabled")) {
      this.enable();
    } else {
      this.disable();
    }
    this.setAttribute("role", "button");
    const mediaControllerId = this.getAttribute(
      import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER
    );
    if (mediaControllerId) {
      __privateSet(
        this,
        _mediaController,
        // @ts-ignore
        (_a = this.getRootNode()) == null ? void 0 : _a.getElementById(mediaControllerId)
      );
      (_c = (_b = __privateGet(this, _mediaController)) == null ? void 0 : _b.associateElement) == null ? void 0 : _c.call(_b, this);
    }
    import_server_safe_globals.globalThis.customElements.whenDefined("media-tooltip").then(() => __privateMethod(this, _setupTooltip, setupTooltip_fn).call(this));
  }
  disconnectedCallback() {
    var _a, _b;
    this.disable();
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    __privateSet(this, _mediaController, null);
    this.removeEventListener("mouseenter", __privateGet(this, _positionTooltip));
    this.removeEventListener("focus", __privateGet(this, _positionTooltip));
    this.removeEventListener("click", __privateGet(this, _clickListener));
  }
  get keysUsed() {
    return ["Enter", " "];
  }
  /**
   * Get or set tooltip placement
   */
  get tooltipPlacement() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.TOOLTIP_PLACEMENT);
  }
  set tooltipPlacement(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.TOOLTIP_PLACEMENT, value);
  }
  get mediaController() {
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER);
  }
  set mediaController(value) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER, value);
  }
  get disabled() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.DISABLED);
  }
  set disabled(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.DISABLED, value);
  }
  get noTooltip() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_TOOLTIP);
  }
  set noTooltip(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_TOOLTIP, value);
  }
  /**
   * @abstract
   * @argument {Event} e
   */
  handleClick(e) {
  }
  // eslint-disable-line
}
_mediaController = new WeakMap();
_clickListener = new WeakMap();
_positionTooltip = new WeakMap();
_keyupListener = new WeakMap();
_keydownListener = new WeakMap();
_setupTooltip = new WeakSet();
setupTooltip_fn = function() {
  this.addEventListener("mouseenter", __privateGet(this, _positionTooltip));
  this.addEventListener("focus", __privateGet(this, _positionTooltip));
  this.addEventListener("click", __privateGet(this, _clickListener));
  const initialPlacement = this.tooltipPlacement;
  if (initialPlacement && this.tooltipEl) {
    this.tooltipEl.placement = initialPlacement;
  }
};
MediaChromeButton.shadowRootOptions = { mode: "open" };
MediaChromeButton.getTemplateHTML = getTemplateHTML;
MediaChromeButton.getSlotTemplateHTML = getSlotTemplateHTML;
MediaChromeButton.getTooltipContentHTML = getTooltipContentHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-chrome-button")) {
  import_server_safe_globals.globalThis.customElements.define("media-chrome-button", MediaChromeButton);
}
var media_chrome_button_default = MediaChromeButton;
