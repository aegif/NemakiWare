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
var _isInit, _previouslyFocused, _invokerElement, _init, init_fn, _handleOpen, handleOpen_fn, _handleClosed, handleClosed_fn, _handleInvoke, handleInvoke_fn, _handleFocusOut, handleFocusOut_fn, _handleKeyDown, handleKeyDown_fn;
import { globalThis } from "./utils/server-safe-globals.js";
import {
  containsComposedNode,
  getActiveElement,
  getBooleanAttr,
  namedNodeMapToObject,
  setBooleanAttr,
  getOrInsertCSSRule
} from "./utils/element-utils.js";
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        font: var(--media-font,
          var(--media-font-weight, normal)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        display: var(--media-dialog-display, inline-flex);
        justify-content: center;
        align-items: center;
        ${/** The hide transition is defined below after a short delay. */
    ""}
        transition-behavior: allow-discrete;
        visibility: hidden;
        opacity: 0;
        transform: translateY(2px) scale(.99);
        pointer-events: none;
      }

      :host([open]) {
        transition: display .2s, visibility 0s, opacity .2s ease-out, transform .15s ease-out;
        visibility: visible;
        opacity: 1;
        transform: translateY(0) scale(1);
        pointer-events: auto;
      }

      #content {
        display: flex;
        position: relative;
        box-sizing: border-box;
        width: min(320px, 100%);
        word-wrap: break-word;
        max-height: 100%;
        overflow: auto;
        text-align: center;
        line-height: 1.4;
      }
    </style>
    ${this.getSlotTemplateHTML(_attrs)}
  `
  );
}
function getSlotTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <slot id="content"></slot>
  `
  );
}
const Attributes = {
  OPEN: "open",
  ANCHOR: "anchor"
};
class MediaChromeDialog extends globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _init);
    __privateAdd(this, _handleOpen);
    __privateAdd(this, _handleClosed);
    __privateAdd(this, _handleInvoke);
    __privateAdd(this, _handleFocusOut);
    __privateAdd(this, _handleKeyDown);
    __privateAdd(this, _isInit, false);
    __privateAdd(this, _previouslyFocused, null);
    __privateAdd(this, _invokerElement, null);
    this.addEventListener("invoke", this);
    this.addEventListener("focusout", this);
    this.addEventListener("keydown", this);
  }
  static get observedAttributes() {
    return [Attributes.OPEN, Attributes.ANCHOR];
  }
  get open() {
    return getBooleanAttr(this, Attributes.OPEN);
  }
  set open(value) {
    setBooleanAttr(this, Attributes.OPEN, value);
  }
  handleEvent(event) {
    switch (event.type) {
      case "invoke":
        __privateMethod(this, _handleInvoke, handleInvoke_fn).call(this, event);
        break;
      case "focusout":
        __privateMethod(this, _handleFocusOut, handleFocusOut_fn).call(this, event);
        break;
      case "keydown":
        __privateMethod(this, _handleKeyDown, handleKeyDown_fn).call(this, event);
        break;
    }
  }
  connectedCallback() {
    __privateMethod(this, _init, init_fn).call(this);
    if (!this.role) {
      this.role = "dialog";
    }
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    __privateMethod(this, _init, init_fn).call(this);
    if (attrName === Attributes.OPEN && newValue !== oldValue) {
      if (this.open) {
        __privateMethod(this, _handleOpen, handleOpen_fn).call(this);
      } else {
        __privateMethod(this, _handleClosed, handleClosed_fn).call(this);
      }
    }
  }
  focus() {
    __privateSet(this, _previouslyFocused, getActiveElement());
    const focusCancelled = !this.dispatchEvent(new Event("focus", { composed: true, cancelable: true }));
    const focusInCancelled = !this.dispatchEvent(new Event("focusin", { composed: true, bubbles: true, cancelable: true }));
    if (focusCancelled || focusInCancelled)
      return;
    const focusable = this.querySelector(
      '[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]'
    );
    focusable == null ? void 0 : focusable.focus();
  }
  get keysUsed() {
    return ["Escape", "Tab"];
  }
}
_isInit = new WeakMap();
_previouslyFocused = new WeakMap();
_invokerElement = new WeakMap();
_init = new WeakSet();
init_fn = function() {
  if (__privateGet(this, _isInit))
    return;
  __privateSet(this, _isInit, true);
  if (!this.shadowRoot) {
    this.attachShadow(this.constructor.shadowRootOptions);
    const attrs = namedNodeMapToObject(this.attributes);
    this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    queueMicrotask(() => {
      const { style } = getOrInsertCSSRule(this.shadowRoot, ":host");
      style.setProperty(
        "transition",
        `display .15s, visibility .15s, opacity .15s ease-in, transform .15s ease-in`
      );
    });
  }
};
_handleOpen = new WeakSet();
handleOpen_fn = function() {
  var _a;
  (_a = __privateGet(this, _invokerElement)) == null ? void 0 : _a.setAttribute("aria-expanded", "true");
  this.dispatchEvent(new Event("open", { composed: true, bubbles: true }));
  this.addEventListener("transitionend", () => this.focus(), { once: true });
};
_handleClosed = new WeakSet();
handleClosed_fn = function() {
  var _a;
  (_a = __privateGet(this, _invokerElement)) == null ? void 0 : _a.setAttribute("aria-expanded", "false");
  this.dispatchEvent(new Event("close", { composed: true, bubbles: true }));
};
_handleInvoke = new WeakSet();
handleInvoke_fn = function(event) {
  __privateSet(this, _invokerElement, event.relatedTarget);
  if (!containsComposedNode(this, event.relatedTarget)) {
    this.open = !this.open;
  }
};
_handleFocusOut = new WeakSet();
handleFocusOut_fn = function(event) {
  var _a;
  if (!containsComposedNode(this, event.relatedTarget)) {
    (_a = __privateGet(this, _previouslyFocused)) == null ? void 0 : _a.focus();
    if (__privateGet(this, _invokerElement) && __privateGet(this, _invokerElement) !== event.relatedTarget && this.open) {
      this.open = false;
    }
  }
};
_handleKeyDown = new WeakSet();
handleKeyDown_fn = function(event) {
  var _a, _b, _c, _d, _e;
  const { key, ctrlKey, altKey, metaKey } = event;
  if (ctrlKey || altKey || metaKey) {
    return;
  }
  if (!this.keysUsed.includes(key)) {
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  if (key === "Tab") {
    if (event.shiftKey) {
      (_b = (_a = this.previousElementSibling) == null ? void 0 : _a.focus) == null ? void 0 : _b.call(_a);
    } else {
      (_d = (_c = this.nextElementSibling) == null ? void 0 : _c.focus) == null ? void 0 : _d.call(_c);
    }
    this.blur();
  } else if (key === "Escape") {
    (_e = __privateGet(this, _previouslyFocused)) == null ? void 0 : _e.focus();
    this.open = false;
  }
};
MediaChromeDialog.shadowRootOptions = { mode: "open" };
MediaChromeDialog.getTemplateHTML = getTemplateHTML;
MediaChromeDialog.getSlotTemplateHTML = getSlotTemplateHTML;
if (!globalThis.customElements.get("media-chrome-dialog")) {
  globalThis.customElements.define("media-chrome-dialog", MediaChromeDialog);
}
var media_chrome_dialog_default = MediaChromeDialog;
export {
  Attributes,
  MediaChromeDialog,
  media_chrome_dialog_default as default
};
