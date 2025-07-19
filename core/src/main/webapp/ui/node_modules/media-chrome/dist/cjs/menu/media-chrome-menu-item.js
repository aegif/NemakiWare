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
var __privateMethod = (obj, member, method) => {
  __accessCheck(obj, member, "access private method");
  return method;
};
var media_chrome_menu_item_exports = {};
__export(media_chrome_menu_item_exports, {
  Attributes: () => Attributes,
  MediaChromeMenuItem: () => MediaChromeMenuItem,
  default: () => media_chrome_menu_item_default
});
module.exports = __toCommonJS(media_chrome_menu_item_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_events = require("../utils/events.js");
var import_element_utils = require("../utils/element-utils.js");
var _dirty, _ownerElement, _handleSlotChange, handleSlotChange_fn, _submenuConnected, submenuConnected_fn, _submenuDisconnected, submenuDisconnected_fn, _handleMenuItem, _handleKeyUp, handleKeyUp_fn, _handleKeyDown, handleKeyDown_fn, _reset, reset_fn;
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        transition: var(--media-menu-item-transition,
          background .15s linear,
          opacity .2s ease-in-out
        );
        outline: var(--media-menu-item-outline, 0);
        outline-offset: var(--media-menu-item-outline-offset, -1px);
        cursor: var(--media-cursor, pointer);
        display: flex;
        align-items: center;
        align-self: stretch;
        justify-self: stretch;
        white-space: nowrap;
        white-space-collapse: collapse;
        text-wrap: nowrap;
        padding: .4em .8em .4em 1em;
      }

      :host(:focus-visible) {
        box-shadow: var(--media-menu-item-focus-shadow, inset 0 0 0 2px rgb(27 127 204 / .9));
        outline: var(--media-menu-item-hover-outline, 0);
        outline-offset: var(--media-menu-item-hover-outline-offset,  var(--media-menu-item-outline-offset, -1px));
      }

      :host(:hover) {
        cursor: var(--media-cursor, pointer);
        background: var(--media-menu-item-hover-background, rgb(92 92 102 / .5));
        outline: var(--media-menu-item-hover-outline);
        outline-offset: var(--media-menu-item-hover-outline-offset,  var(--media-menu-item-outline-offset, -1px));
      }

      :host([aria-checked="true"]) {
        background: var(--media-menu-item-checked-background);
      }

      :host([hidden]) {
        display: none;
      }

      :host([disabled]) {
        pointer-events: none;
        color: rgba(255, 255, 255, .3);
      }

      slot:not([name]) {
        width: 100%;
      }

      slot:not([name="submenu"]) {
        display: inline-flex;
        align-items: center;
        transition: inherit;
        opacity: var(--media-menu-item-opacity, 1);
      }

      slot[name="description"] {
        justify-content: end;
      }

      slot[name="description"] > span {
        display: inline-block;
        margin-inline: 1em .2em;
        max-width: var(--media-menu-item-description-max-width, 100px);
        text-overflow: ellipsis;
        overflow: hidden;
        font-size: .8em;
        font-weight: 400;
        text-align: right;
        position: relative;
        top: .04em;
      }

      slot[name="checked-indicator"] {
        display: none;
      }

      :host(:is([role="menuitemradio"],[role="menuitemcheckbox"])) slot[name="checked-indicator"] {
        display: var(--media-menu-item-checked-indicator-display, inline-block);
      }

      ${/* For all slotted icons in prefix and suffix. */
    ""}
      svg, img, ::slotted(svg), ::slotted(img) {
        height: var(--media-menu-item-icon-height, var(--media-control-height, 24px));
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        display: block;
      }

      ${/* Only for indicator icons like checked-indicator or captions-indicator. */
    ""}
      [part~="indicator"],
      ::slotted([part~="indicator"]) {
        fill: var(--media-menu-item-indicator-fill,
          var(--media-icon-color, var(--media-primary-color, rgb(238 238 238))));
        height: var(--media-menu-item-indicator-height, 1.25em);
        margin-right: .5ch;
      }

      [part~="checked-indicator"] {
        visibility: hidden;
      }

      :host([aria-checked="true"]) [part~="checked-indicator"] {
        visibility: visible;
      }
    </style>
    <slot name="checked-indicator">
      <svg aria-hidden="true" viewBox="0 1 24 24" part="checked-indicator indicator">
        <path d="m10 15.17 9.193-9.191 1.414 1.414-10.606 10.606-6.364-6.364 1.414-1.414 4.95 4.95Z"/>
      </svg>
    </slot>
    <slot name="prefix"></slot>
    <slot></slot>
    <slot name="description"></slot>
    <slot name="suffix">
      ${this.getSuffixSlotInnerHTML(_attrs)}
    </slot>
    <slot name="submenu"></slot>
  `
  );
}
function getSuffixSlotInnerHTML(_attrs) {
  return "";
}
const Attributes = {
  TYPE: "type",
  VALUE: "value",
  CHECKED: "checked",
  DISABLED: "disabled"
};
class MediaChromeMenuItem extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _handleSlotChange);
    __privateAdd(this, _submenuConnected);
    __privateAdd(this, _submenuDisconnected);
    __privateAdd(this, _handleKeyUp);
    __privateAdd(this, _handleKeyDown);
    __privateAdd(this, _reset);
    __privateAdd(this, _dirty, false);
    __privateAdd(this, _ownerElement, void 0);
    /**
     * If there is a slotted submenu the fallback content of the description slot
     * is populated with the text of the first checked item.
     */
    __privateAdd(this, _handleMenuItem, () => {
      var _a, _b;
      this.setAttribute("submenusize", `${this.submenuElement.items.length}`);
      const descriptionSlot = this.shadowRoot.querySelector(
        'slot[name="description"]'
      );
      const checkedItem = (_a = this.submenuElement.checkedItems) == null ? void 0 : _a[0];
      const description = (_b = checkedItem == null ? void 0 : checkedItem.dataset.description) != null ? _b : checkedItem == null ? void 0 : checkedItem.text;
      const span = import_server_safe_globals.document.createElement("span");
      span.textContent = description != null ? description : "";
      descriptionSlot.replaceChildren(span);
    });
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
    this.shadowRoot.addEventListener("slotchange", this);
  }
  static get observedAttributes() {
    return [
      Attributes.TYPE,
      Attributes.DISABLED,
      Attributes.CHECKED,
      Attributes.VALUE
    ];
  }
  enable() {
    if (!this.hasAttribute("tabindex")) {
      this.setAttribute("tabindex", "-1");
    }
    if (isCheckable(this) && !this.hasAttribute("aria-checked")) {
      this.setAttribute("aria-checked", "false");
    }
    this.addEventListener("click", this);
    this.addEventListener("keydown", this);
  }
  disable() {
    this.removeAttribute("tabindex");
    this.removeEventListener("click", this);
    this.removeEventListener("keydown", this);
    this.removeEventListener("keyup", this);
  }
  handleEvent(event) {
    switch (event.type) {
      case "slotchange":
        __privateMethod(this, _handleSlotChange, handleSlotChange_fn).call(this, event);
        break;
      case "click":
        this.handleClick(event);
        break;
      case "keydown":
        __privateMethod(this, _handleKeyDown, handleKeyDown_fn).call(this, event);
        break;
      case "keyup":
        __privateMethod(this, _handleKeyUp, handleKeyUp_fn).call(this, event);
        break;
    }
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === Attributes.CHECKED && isCheckable(this) && !__privateGet(this, _dirty)) {
      this.setAttribute("aria-checked", newValue != null ? "true" : "false");
    } else if (attrName === Attributes.TYPE && newValue !== oldValue) {
      this.role = "menuitem" + newValue;
    } else if (attrName === Attributes.DISABLED && newValue !== oldValue) {
      if (newValue == null) {
        this.enable();
      } else {
        this.disable();
      }
    }
  }
  connectedCallback() {
    if (!this.hasAttribute(Attributes.DISABLED)) {
      this.enable();
    }
    this.role = "menuitem" + this.type;
    __privateSet(this, _ownerElement, closestMenuItemsContainer(this, this.parentNode));
    __privateMethod(this, _reset, reset_fn).call(this);
  }
  disconnectedCallback() {
    this.disable();
    __privateMethod(this, _reset, reset_fn).call(this);
    __privateSet(this, _ownerElement, null);
  }
  get invokeTarget() {
    return this.getAttribute("invoketarget");
  }
  set invokeTarget(value) {
    this.setAttribute("invoketarget", `${value}`);
  }
  /**
   * Returns the element with the id specified by the `invoketarget` attribute
   * or the slotted submenu element.
   */
  get invokeTargetElement() {
    var _a;
    if (this.invokeTarget) {
      return (_a = (0, import_element_utils.getDocumentOrShadowRoot)(this)) == null ? void 0 : _a.querySelector(
        `#${this.invokeTarget}`
      );
    }
    return this.submenuElement;
  }
  /**
   * Returns the slotted submenu element.
   */
  get submenuElement() {
    const submenuSlot = this.shadowRoot.querySelector(
      'slot[name="submenu"]'
    );
    return submenuSlot.assignedElements({
      flatten: true
    })[0];
  }
  get type() {
    var _a;
    return (_a = this.getAttribute(Attributes.TYPE)) != null ? _a : "";
  }
  set type(val) {
    this.setAttribute(Attributes.TYPE, `${val}`);
  }
  get value() {
    var _a;
    return (_a = this.getAttribute(Attributes.VALUE)) != null ? _a : this.text;
  }
  set value(val) {
    this.setAttribute(Attributes.VALUE, val);
  }
  get text() {
    var _a;
    return ((_a = this.textContent) != null ? _a : "").trim();
  }
  get checked() {
    if (!isCheckable(this))
      return void 0;
    return this.getAttribute("aria-checked") === "true";
  }
  set checked(value) {
    if (!isCheckable(this))
      return;
    __privateSet(this, _dirty, true);
    this.setAttribute("aria-checked", value ? "true" : "false");
    if (value) {
      this.part.add("checked");
    } else {
      this.part.remove("checked");
    }
  }
  handleClick(event) {
    if (isCheckable(this))
      return;
    if (this.invokeTargetElement && (0, import_element_utils.containsComposedNode)(this, event.target)) {
      this.invokeTargetElement.dispatchEvent(
        new import_events.InvokeEvent({ relatedTarget: this })
      );
    }
  }
  get keysUsed() {
    return ["Enter", " "];
  }
}
_dirty = new WeakMap();
_ownerElement = new WeakMap();
_handleSlotChange = new WeakSet();
handleSlotChange_fn = function(event) {
  const slot = event.target;
  const isDefaultSlot = !(slot == null ? void 0 : slot.name);
  if (isDefaultSlot) {
    for (const node of slot.assignedNodes({ flatten: true })) {
      if (node instanceof Text && node.textContent.trim() === "") {
        node.remove();
      }
    }
  }
  if (slot.name === "submenu") {
    if (this.submenuElement) {
      __privateMethod(this, _submenuConnected, submenuConnected_fn).call(this);
    } else {
      __privateMethod(this, _submenuDisconnected, submenuDisconnected_fn).call(this);
    }
  }
};
_submenuConnected = new WeakSet();
submenuConnected_fn = async function() {
  this.setAttribute("aria-haspopup", "menu");
  this.setAttribute("aria-expanded", `${!this.submenuElement.hidden}`);
  this.submenuElement.addEventListener("change", __privateGet(this, _handleMenuItem));
  this.submenuElement.addEventListener("addmenuitem", __privateGet(this, _handleMenuItem));
  this.submenuElement.addEventListener(
    "removemenuitem",
    __privateGet(this, _handleMenuItem)
  );
  __privateGet(this, _handleMenuItem).call(this);
};
_submenuDisconnected = new WeakSet();
submenuDisconnected_fn = function() {
  this.removeAttribute("aria-haspopup");
  this.removeAttribute("aria-expanded");
  this.submenuElement.removeEventListener("change", __privateGet(this, _handleMenuItem));
  this.submenuElement.removeEventListener(
    "addmenuitem",
    __privateGet(this, _handleMenuItem)
  );
  this.submenuElement.removeEventListener(
    "removemenuitem",
    __privateGet(this, _handleMenuItem)
  );
  __privateGet(this, _handleMenuItem).call(this);
};
_handleMenuItem = new WeakMap();
_handleKeyUp = new WeakSet();
handleKeyUp_fn = function(event) {
  const { key } = event;
  if (!this.keysUsed.includes(key)) {
    this.removeEventListener("keyup", __privateMethod(this, _handleKeyUp, handleKeyUp_fn));
    return;
  }
  this.handleClick(event);
};
_handleKeyDown = new WeakSet();
handleKeyDown_fn = function(event) {
  const { metaKey, altKey, key } = event;
  if (metaKey || altKey || !this.keysUsed.includes(key)) {
    this.removeEventListener("keyup", __privateMethod(this, _handleKeyUp, handleKeyUp_fn));
    return;
  }
  this.addEventListener("keyup", __privateMethod(this, _handleKeyUp, handleKeyUp_fn), { once: true });
};
_reset = new WeakSet();
reset_fn = function() {
  var _a;
  const items = (_a = __privateGet(this, _ownerElement)) == null ? void 0 : _a.radioGroupItems;
  if (!items)
    return;
  let checkedItem = items.filter((item) => item.getAttribute("aria-checked") === "true").pop();
  if (!checkedItem)
    checkedItem = items[0];
  for (const item of items) {
    item.setAttribute("aria-checked", "false");
  }
  checkedItem == null ? void 0 : checkedItem.setAttribute("aria-checked", "true");
};
MediaChromeMenuItem.shadowRootOptions = { mode: "open" };
MediaChromeMenuItem.getTemplateHTML = getTemplateHTML;
MediaChromeMenuItem.getSuffixSlotInnerHTML = getSuffixSlotInnerHTML;
function isCheckable(item) {
  return item.type === "radio" || item.type === "checkbox";
}
function closestMenuItemsContainer(childNode, parentNode) {
  if (!childNode)
    return null;
  const { host } = childNode.getRootNode();
  if (!parentNode && host)
    return closestMenuItemsContainer(childNode, host);
  if (parentNode == null ? void 0 : parentNode.items)
    return parentNode;
  return closestMenuItemsContainer(parentNode, parentNode == null ? void 0 : parentNode.parentNode);
}
if (!import_server_safe_globals.globalThis.customElements.get("media-chrome-menu-item")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-chrome-menu-item",
    MediaChromeMenuItem
  );
}
var media_chrome_menu_item_default = MediaChromeMenuItem;
