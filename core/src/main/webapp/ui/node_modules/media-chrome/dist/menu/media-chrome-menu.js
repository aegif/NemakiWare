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
var _mediaController, _previouslyFocused, _invokerElement, _previousItems, _mutationObserver, _isPopover, _cssRule, _handleSlotChange, handleSlotChange_fn, _handleMenuItems, _updateLayoutStyle, updateLayoutStyle_fn, _handleInvoke, handleInvoke_fn, _handleOpen, handleOpen_fn, _handleClosed, handleClosed_fn, _handleBoundsResize, _handleMenuResize, _positionMenu, positionMenu_fn, _resizeMenu, resizeMenu_fn, _handleClick, handleClick_fn, _backButtonElement, backButtonElement_get, _handleToggle, handleToggle_fn, _checkSubmenuHasExpanded, checkSubmenuHasExpanded_fn, _handleFocusOut, handleFocusOut_fn, _handleKeyDown, handleKeyDown_fn, _getItem, getItem_fn, _getTabItem, getTabItem_fn, _setTabItem, setTabItem_fn, _selectItem, selectItem_fn;
import { MediaStateReceiverAttributes } from "../constants.js";
import { globalThis, document } from "../utils/server-safe-globals.js";
import { computePosition } from "../utils/anchor-utils.js";
import { observeResize, unobserveResize } from "../utils/resize-observer.js";
import { ToggleEvent, InvokeEvent } from "../utils/events.js";
import {
  getActiveElement,
  containsComposedNode,
  closestComposedNode,
  insertCSSRule,
  getMediaController,
  getAttributeMediaController,
  getDocumentOrShadowRoot,
  namedNodeMapToObject
} from "../utils/element-utils.js";
function createMenuItem({
  type,
  text,
  value,
  checked
}) {
  const item = document.createElement(
    "media-chrome-menu-item"
  );
  item.type = type != null ? type : "";
  item.part.add("menu-item");
  if (type)
    item.part.add(type);
  item.value = value;
  item.checked = checked;
  const label = document.createElement("span");
  label.textContent = text;
  item.append(label);
  return item;
}
function createIndicator(el, name) {
  let customIndicator = el.querySelector(`:scope > [slot="${name}"]`);
  if ((customIndicator == null ? void 0 : customIndicator.nodeName) == "SLOT")
    customIndicator = customIndicator.assignedElements({ flatten: true })[0];
  if (customIndicator) {
    customIndicator = customIndicator.cloneNode(true);
    return customIndicator;
  }
  const fallbackIndicator = el.shadowRoot.querySelector(
    `[name="${name}"] > svg`
  );
  if (fallbackIndicator) {
    return fallbackIndicator.cloneNode(true);
  }
  return "";
}
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
        --_menu-bg: rgb(20 20 30 / .8);
        background: var(--media-menu-background, var(--media-control-background, var(--media-secondary-color, var(--_menu-bg))));
        border-radius: var(--media-menu-border-radius);
        border: var(--media-menu-border, none);
        display: var(--media-menu-display, inline-flex);
        transition: var(--media-menu-transition-in,
          visibility 0s,
          opacity .2s ease-out,
          transform .15s ease-out,
          left .2s ease-in-out,
          min-width .2s ease-in-out,
          min-height .2s ease-in-out
        ) !important;
        ${/* ^^Prevent transition override by media-container */
    ""}
        visibility: var(--media-menu-visibility, visible);
        opacity: var(--media-menu-opacity, 1);
        max-height: var(--media-menu-max-height, var(--_menu-max-height, 300px));
        transform: var(--media-menu-transform-in, translateY(0) scale(1));
        flex-direction: column;
        ${/* Prevent overflowing a flex container */
    ""}
        min-height: 0;
        position: relative;
        bottom: var(--_menu-bottom);
        box-sizing: border-box;
      } 

      @-moz-document url-prefix() {
        :host{
          --_menu-bg: rgb(20 20 30);
        }
      }

      :host([hidden]) {
        transition: var(--media-menu-transition-out,
          visibility .15s ease-in,
          opacity .15s ease-in,
          transform .15s ease-in
        ) !important;
        visibility: var(--media-menu-hidden-visibility, hidden);
        opacity: var(--media-menu-hidden-opacity, 0);
        max-height: var(--media-menu-hidden-max-height,
          var(--media-menu-max-height, var(--_menu-max-height, 300px)));
        transform: var(--media-menu-transform-out, translateY(2px) scale(.99));
        pointer-events: none;
      }

      :host([slot="submenu"]) {
        background: none;
        width: 100%;
        min-height: 100%;
        position: absolute;
        bottom: 0;
        right: -100%;
      }

      #container {
        display: flex;
        flex-direction: column;
        min-height: 0;
        transition: transform .2s ease-out;
        transform: translate(0, 0);
      }

      #container.has-expanded {
        transition: transform .2s ease-in;
        transform: translate(-100%, 0);
      }

      button {
        background: none;
        color: inherit;
        border: none;
        padding: 0;
        font: inherit;
        outline: inherit;
        display: inline-flex;
        align-items: center;
      }

      slot[name="header"][hidden] {
        display: none;
      }

      slot[name="header"] > *,
      slot[name="header"]::slotted(*) {
        padding: .4em .7em;
        border-bottom: 1px solid rgb(255 255 255 / .25);
        cursor: var(--media-cursor, default);
      }

      slot[name="header"] > button[part~="back"],
      slot[name="header"]::slotted(button[part~="back"]) {
        cursor: var(--media-cursor, pointer);
      }

      svg[part~="back"] {
        height: var(--media-menu-icon-height, var(--media-control-height, 24px));
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        display: block;
        margin-right: .5ch;
      }

      slot:not([name]) {
        gap: var(--media-menu-gap);
        flex-direction: var(--media-menu-flex-direction, column);
        overflow: var(--media-menu-overflow, hidden auto);
        display: flex;
        min-height: 0;
      }

      :host([role="menu"]) slot:not([name]) {
        padding-block: .4em;
      }

      slot:not([name])::slotted([role="menu"]) {
        background: none;
      }

      media-chrome-menu-item > span {
        margin-right: .5ch;
        max-width: var(--media-menu-item-max-width);
        text-overflow: ellipsis;
        overflow: hidden;
      }
    </style>
    <style id="layout-row" media="width:0">

      slot[name="header"] > *,
      slot[name="header"]::slotted(*) {
        padding: .4em .5em;
      }

      slot:not([name]) {
        gap: var(--media-menu-gap, .25em);
        flex-direction: var(--media-menu-flex-direction, row);
        padding-inline: .5em;
      }

      media-chrome-menu-item {
        padding: .3em .5em;
      }

      media-chrome-menu-item[aria-checked="true"] {
        background: var(--media-menu-item-checked-background, rgb(255 255 255 / .2));
      }

      ${/* In row layout hide the checked indicator completely. */
    ""}
      media-chrome-menu-item::part(checked-indicator) {
        display: var(--media-menu-item-checked-indicator-display, none);
      }
    </style>
    <div id="container">
      <slot name="header" hidden>
        <button part="back button" aria-label="Back to previous menu">
          <slot name="back-icon">
            <svg aria-hidden="true" viewBox="0 0 20 24" part="back indicator">
              <path d="m11.88 17.585.742-.669-4.2-4.665 4.2-4.666-.743-.669-4.803 5.335 4.803 5.334Z"/>
            </svg>
          </slot>
          <slot name="title"></slot>
        </button>
      </slot>
      <slot></slot>
    </div>
    <slot name="checked-indicator" hidden></slot>
  `
  );
}
const Attributes = {
  STYLE: "style",
  HIDDEN: "hidden",
  DISABLED: "disabled",
  ANCHOR: "anchor"
};
class MediaChromeMenu extends globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _handleSlotChange);
    /**
     * Sets the layout style for the menu.
     * It can be a row or column layout. e.g. playback-rate-menu
     */
    __privateAdd(this, _updateLayoutStyle);
    __privateAdd(this, _handleInvoke);
    __privateAdd(this, _handleOpen);
    __privateAdd(this, _handleClosed);
    /**
     * Updates the popover menu position based on the anchor element.
     * @param  {number} [menuWidth]
     */
    __privateAdd(this, _positionMenu);
    /**
     * Resize this menu to fit the submenu.
     * @param  {boolean} animate
     */
    __privateAdd(this, _resizeMenu);
    __privateAdd(this, _handleClick);
    __privateAdd(this, _backButtonElement);
    /**
     * Handle the toggle event of submenus.
     * Closes all other open submenus when opening a submenu.
     * Resizes this menu to fit the submenu.
     *
     * @param  {ToggleEvent} event
     */
    __privateAdd(this, _handleToggle);
    /**
     * Check if any submenu is expanded and update the container class accordingly.
     * When the CSS :has() selector is supported, this can be done with CSS only.
     */
    __privateAdd(this, _checkSubmenuHasExpanded);
    __privateAdd(this, _handleFocusOut);
    __privateAdd(this, _handleKeyDown);
    __privateAdd(this, _getItem);
    __privateAdd(this, _getTabItem);
    __privateAdd(this, _setTabItem);
    __privateAdd(this, _selectItem);
    __privateAdd(this, _mediaController, null);
    __privateAdd(this, _previouslyFocused, null);
    __privateAdd(this, _invokerElement, null);
    __privateAdd(this, _previousItems, /* @__PURE__ */ new Set());
    __privateAdd(this, _mutationObserver, void 0);
    __privateAdd(this, _isPopover, false);
    __privateAdd(this, _cssRule, null);
    /**
     * Fires an event when a menu item is added or removed.
     * This is needed to update the description slot of an ancestor menu item.
     */
    __privateAdd(this, _handleMenuItems, () => {
      const previousItems = __privateGet(this, _previousItems);
      const currentItems = new Set(this.items);
      for (const item of previousItems) {
        if (!currentItems.has(item)) {
          this.dispatchEvent(new CustomEvent("removemenuitem", { detail: item }));
        }
      }
      for (const item of currentItems) {
        if (!previousItems.has(item)) {
          this.dispatchEvent(new CustomEvent("addmenuitem", { detail: item }));
        }
      }
      __privateSet(this, _previousItems, currentItems);
    });
    __privateAdd(this, _handleBoundsResize, () => {
      __privateMethod(this, _positionMenu, positionMenu_fn).call(this);
      __privateMethod(this, _resizeMenu, resizeMenu_fn).call(this, false);
    });
    __privateAdd(this, _handleMenuResize, () => {
      __privateMethod(this, _positionMenu, positionMenu_fn).call(this);
    });
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = namedNodeMapToObject(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
    this.container = this.shadowRoot.querySelector("#container");
    this.defaultSlot = this.shadowRoot.querySelector(
      "slot:not([name])"
    );
    this.shadowRoot.addEventListener("slotchange", this);
    __privateSet(this, _mutationObserver, new MutationObserver(__privateGet(this, _handleMenuItems)));
    __privateGet(this, _mutationObserver).observe(this.defaultSlot, { childList: true });
  }
  static get observedAttributes() {
    return [
      Attributes.DISABLED,
      Attributes.HIDDEN,
      Attributes.STYLE,
      Attributes.ANCHOR,
      MediaStateReceiverAttributes.MEDIA_CONTROLLER
    ];
  }
  static formatMenuItemText(text, _data) {
    return text;
  }
  enable() {
    this.addEventListener("click", this);
    this.addEventListener("focusout", this);
    this.addEventListener("keydown", this);
    this.addEventListener("invoke", this);
    this.addEventListener("toggle", this);
  }
  disable() {
    this.removeEventListener("click", this);
    this.removeEventListener("focusout", this);
    this.removeEventListener("keyup", this);
    this.removeEventListener("invoke", this);
    this.removeEventListener("toggle", this);
  }
  handleEvent(event) {
    switch (event.type) {
      case "slotchange":
        __privateMethod(this, _handleSlotChange, handleSlotChange_fn).call(this, event);
        break;
      case "invoke":
        __privateMethod(this, _handleInvoke, handleInvoke_fn).call(this, event);
        break;
      case "click":
        __privateMethod(this, _handleClick, handleClick_fn).call(this, event);
        break;
      case "toggle":
        __privateMethod(this, _handleToggle, handleToggle_fn).call(this, event);
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
    var _a, _b;
    __privateSet(this, _cssRule, insertCSSRule(this.shadowRoot, ":host"));
    __privateMethod(this, _updateLayoutStyle, updateLayoutStyle_fn).call(this);
    if (!this.hasAttribute("disabled")) {
      this.enable();
    }
    if (!this.role) {
      this.role = "menu";
    }
    __privateSet(this, _mediaController, getAttributeMediaController(this));
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.associateElement) == null ? void 0 : _b.call(_a, this);
    if (!this.hidden) {
      observeResize(getBoundsElement(this), __privateGet(this, _handleBoundsResize));
      observeResize(this, __privateGet(this, _handleMenuResize));
    }
  }
  disconnectedCallback() {
    var _a, _b;
    unobserveResize(getBoundsElement(this), __privateGet(this, _handleBoundsResize));
    unobserveResize(this, __privateGet(this, _handleMenuResize));
    this.disable();
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    __privateSet(this, _mediaController, null);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a, _b, _c, _d;
    if (attrName === Attributes.HIDDEN && newValue !== oldValue) {
      if (!__privateGet(this, _isPopover))
        __privateSet(this, _isPopover, true);
      if (this.hidden) {
        __privateMethod(this, _handleClosed, handleClosed_fn).call(this);
      } else {
        __privateMethod(this, _handleOpen, handleOpen_fn).call(this);
      }
      this.dispatchEvent(
        new ToggleEvent({
          oldState: this.hidden ? "open" : "closed",
          newState: this.hidden ? "closed" : "open",
          bubbles: true
        })
      );
    } else if (attrName === MediaStateReceiverAttributes.MEDIA_CONTROLLER) {
      if (oldValue) {
        (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
        __privateSet(this, _mediaController, null);
      }
      if (newValue && this.isConnected) {
        __privateSet(this, _mediaController, getAttributeMediaController(this));
        (_d = (_c = __privateGet(this, _mediaController)) == null ? void 0 : _c.associateElement) == null ? void 0 : _d.call(_c, this);
      }
    } else if (attrName === Attributes.DISABLED && newValue !== oldValue) {
      if (newValue == null) {
        this.enable();
      } else {
        this.disable();
      }
    } else if (attrName === Attributes.STYLE && newValue !== oldValue) {
      __privateMethod(this, _updateLayoutStyle, updateLayoutStyle_fn).call(this);
    }
  }
  formatMenuItemText(text, data) {
    return this.constructor.formatMenuItemText(
      text,
      data
    );
  }
  get anchor() {
    return this.getAttribute("anchor");
  }
  set anchor(value) {
    this.setAttribute("anchor", `${value}`);
  }
  /**
   * Returns the anchor element when it is a floating menu.
   */
  get anchorElement() {
    var _a;
    if (this.anchor) {
      return (_a = getDocumentOrShadowRoot(this)) == null ? void 0 : _a.querySelector(
        `#${this.anchor}`
      );
    }
    return null;
  }
  /**
   * Returns the menu items.
   */
  get items() {
    return this.defaultSlot.assignedElements({ flatten: true }).filter(isMenuItem);
  }
  get radioGroupItems() {
    return this.items.filter((item) => item.role === "menuitemradio");
  }
  get checkedItems() {
    return this.items.filter((item) => item.checked);
  }
  get value() {
    var _a, _b;
    return (_b = (_a = this.checkedItems[0]) == null ? void 0 : _a.value) != null ? _b : "";
  }
  set value(newValue) {
    const item = this.items.find((item2) => item2.value === newValue);
    if (!item)
      return;
    __privateMethod(this, _selectItem, selectItem_fn).call(this, item);
  }
  focus() {
    __privateSet(this, _previouslyFocused, getActiveElement());
    if (this.items.length) {
      __privateMethod(this, _setTabItem, setTabItem_fn).call(this, this.items[0]);
      this.items[0].focus();
      return;
    }
    const focusable = this.querySelector(
      '[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]'
    );
    focusable == null ? void 0 : focusable.focus();
  }
  handleSelect(event) {
    var _a;
    const item = __privateMethod(this, _getItem, getItem_fn).call(this, event);
    if (!item)
      return;
    __privateMethod(this, _selectItem, selectItem_fn).call(this, item, item.type === "checkbox");
    if (__privateGet(this, _invokerElement) && !this.hidden) {
      (_a = __privateGet(this, _previouslyFocused)) == null ? void 0 : _a.focus();
      this.hidden = true;
    }
  }
  get keysUsed() {
    return [
      "Enter",
      "Escape",
      "Tab",
      " ",
      "ArrowDown",
      "ArrowUp",
      "Home",
      "End"
    ];
  }
  handleMove(event) {
    var _a, _b;
    const { key } = event;
    const items = this.items;
    const currentItem = (_b = (_a = __privateMethod(this, _getItem, getItem_fn).call(this, event)) != null ? _a : __privateMethod(this, _getTabItem, getTabItem_fn).call(this)) != null ? _b : items[0];
    const currentIndex = items.indexOf(currentItem);
    let index = Math.max(0, currentIndex);
    if (key === "ArrowDown") {
      index++;
    } else if (key === "ArrowUp") {
      index--;
    } else if (event.key === "Home") {
      index = 0;
    } else if (event.key === "End") {
      index = items.length - 1;
    }
    if (index < 0) {
      index = items.length - 1;
    }
    if (index > items.length - 1) {
      index = 0;
    }
    __privateMethod(this, _setTabItem, setTabItem_fn).call(this, items[index]);
    items[index].focus();
  }
}
_mediaController = new WeakMap();
_previouslyFocused = new WeakMap();
_invokerElement = new WeakMap();
_previousItems = new WeakMap();
_mutationObserver = new WeakMap();
_isPopover = new WeakMap();
_cssRule = new WeakMap();
_handleSlotChange = new WeakSet();
handleSlotChange_fn = function(event) {
  const slot = event.target;
  for (const node of slot.assignedNodes({ flatten: true })) {
    if (node.nodeType === 3 && node.textContent.trim() === "") {
      node.remove();
    }
  }
  if (["header", "title"].includes(slot.name)) {
    const header = this.shadowRoot.querySelector(
      'slot[name="header"]'
    );
    header.hidden = slot.assignedNodes().length === 0;
  }
  if (!slot.name) {
    __privateGet(this, _handleMenuItems).call(this);
  }
};
_handleMenuItems = new WeakMap();
_updateLayoutStyle = new WeakSet();
updateLayoutStyle_fn = function() {
  var _a;
  const layoutRowStyle = this.shadowRoot.querySelector("#layout-row");
  const menuLayout = (_a = getComputedStyle(this).getPropertyValue("--media-menu-layout")) == null ? void 0 : _a.trim();
  layoutRowStyle.setAttribute("media", menuLayout === "row" ? "" : "width:0");
};
_handleInvoke = new WeakSet();
handleInvoke_fn = function(event) {
  __privateSet(this, _invokerElement, event.relatedTarget);
  if (!containsComposedNode(this, event.relatedTarget)) {
    this.hidden = !this.hidden;
  }
};
_handleOpen = new WeakSet();
handleOpen_fn = function() {
  var _a;
  (_a = __privateGet(this, _invokerElement)) == null ? void 0 : _a.setAttribute("aria-expanded", "true");
  this.addEventListener("transitionend", () => this.focus(), { once: true });
  observeResize(getBoundsElement(this), __privateGet(this, _handleBoundsResize));
  observeResize(this, __privateGet(this, _handleMenuResize));
};
_handleClosed = new WeakSet();
handleClosed_fn = function() {
  var _a;
  (_a = __privateGet(this, _invokerElement)) == null ? void 0 : _a.setAttribute("aria-expanded", "false");
  unobserveResize(getBoundsElement(this), __privateGet(this, _handleBoundsResize));
  unobserveResize(this, __privateGet(this, _handleMenuResize));
};
_handleBoundsResize = new WeakMap();
_handleMenuResize = new WeakMap();
_positionMenu = new WeakSet();
positionMenu_fn = function(menuWidth) {
  if (this.hasAttribute("mediacontroller") && !this.anchor)
    return;
  if (this.hidden || !this.anchorElement)
    return;
  const { x, y } = computePosition({
    anchor: this.anchorElement,
    floating: this,
    placement: "top-start"
  });
  menuWidth != null ? menuWidth : menuWidth = this.offsetWidth;
  const bounds = getBoundsElement(this);
  const boundsRect = bounds.getBoundingClientRect();
  const right = boundsRect.width - x - menuWidth;
  const bottom = boundsRect.height - y - this.offsetHeight;
  const { style } = __privateGet(this, _cssRule);
  style.setProperty("position", "absolute");
  style.setProperty("right", `${Math.max(0, right)}px`);
  style.setProperty("--_menu-bottom", `${bottom}px`);
  const computedStyle = getComputedStyle(this);
  const isBottomCalc = style.getPropertyValue("--_menu-bottom") === computedStyle.bottom;
  const realBottom = isBottomCalc ? bottom : parseFloat(computedStyle.bottom);
  const maxHeight = boundsRect.height - realBottom - parseFloat(computedStyle.marginBottom);
  this.style.setProperty("--_menu-max-height", `${maxHeight}px`);
};
_resizeMenu = new WeakSet();
resizeMenu_fn = function(animate) {
  const expandedMenuItem = this.querySelector(
    '[role="menuitem"][aria-haspopup][aria-expanded="true"]'
  );
  const expandedSubmenu = expandedMenuItem == null ? void 0 : expandedMenuItem.querySelector(
    '[role="menu"]'
  );
  const { style } = __privateGet(this, _cssRule);
  if (!animate) {
    style.setProperty("--media-menu-transition-in", "none");
  }
  if (expandedSubmenu) {
    const height = expandedSubmenu.offsetHeight;
    const width = Math.max(
      expandedSubmenu.offsetWidth,
      expandedMenuItem.offsetWidth
    );
    this.style.setProperty("min-width", `${width}px`);
    this.style.setProperty("min-height", `${height}px`);
    __privateMethod(this, _positionMenu, positionMenu_fn).call(this, width);
  } else {
    this.style.removeProperty("min-width");
    this.style.removeProperty("min-height");
    __privateMethod(this, _positionMenu, positionMenu_fn).call(this);
  }
  style.removeProperty("--media-menu-transition-in");
};
_handleClick = new WeakSet();
handleClick_fn = function(event) {
  var _a;
  event.stopPropagation();
  if (event.composedPath().includes(__privateGet(this, _backButtonElement, backButtonElement_get))) {
    (_a = __privateGet(this, _previouslyFocused)) == null ? void 0 : _a.focus();
    this.hidden = true;
    return;
  }
  const item = __privateMethod(this, _getItem, getItem_fn).call(this, event);
  if (!item || item.hasAttribute("disabled"))
    return;
  __privateMethod(this, _setTabItem, setTabItem_fn).call(this, item);
  this.handleSelect(event);
};
_backButtonElement = new WeakSet();
backButtonElement_get = function() {
  var _a;
  const headerSlot = this.shadowRoot.querySelector(
    'slot[name="header"]'
  );
  return (_a = headerSlot.assignedElements({ flatten: true })) == null ? void 0 : _a.find((el) => el.matches('button[part~="back"]'));
};
_handleToggle = new WeakSet();
handleToggle_fn = function(event) {
  if (event.target === this)
    return;
  __privateMethod(this, _checkSubmenuHasExpanded, checkSubmenuHasExpanded_fn).call(this);
  const menuItemsWithSubmenu = Array.from(
    this.querySelectorAll('[role="menuitem"][aria-haspopup]')
  );
  for (const item of menuItemsWithSubmenu) {
    if (item.invokeTargetElement == event.target)
      continue;
    if (event.newState == "open" && item.getAttribute("aria-expanded") == "true" && !item.invokeTargetElement.hidden) {
      item.invokeTargetElement.dispatchEvent(
        new InvokeEvent({ relatedTarget: item })
      );
    }
  }
  for (const item of menuItemsWithSubmenu) {
    item.setAttribute("aria-expanded", `${!item.submenuElement.hidden}`);
  }
  __privateMethod(this, _resizeMenu, resizeMenu_fn).call(this, true);
};
_checkSubmenuHasExpanded = new WeakSet();
checkSubmenuHasExpanded_fn = function() {
  const selector = '[role="menuitem"] > [role="menu"]:not([hidden])';
  const expandedMenuItem = this.querySelector(selector);
  this.container.classList.toggle("has-expanded", !!expandedMenuItem);
};
_handleFocusOut = new WeakSet();
handleFocusOut_fn = function(event) {
  var _a;
  if (!containsComposedNode(this, event.relatedTarget)) {
    if (__privateGet(this, _isPopover)) {
      (_a = __privateGet(this, _previouslyFocused)) == null ? void 0 : _a.focus();
    }
    if (__privateGet(this, _invokerElement) && __privateGet(this, _invokerElement) !== event.relatedTarget && !this.hidden) {
      this.hidden = true;
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
    if (__privateGet(this, _isPopover)) {
      this.hidden = true;
      return;
    }
    if (event.shiftKey) {
      (_b = (_a = this.previousElementSibling) == null ? void 0 : _a.focus) == null ? void 0 : _b.call(_a);
    } else {
      (_d = (_c = this.nextElementSibling) == null ? void 0 : _c.focus) == null ? void 0 : _d.call(_c);
    }
    this.blur();
  } else if (key === "Escape") {
    (_e = __privateGet(this, _previouslyFocused)) == null ? void 0 : _e.focus();
    if (__privateGet(this, _isPopover)) {
      this.hidden = true;
    }
  } else if (key === "Enter" || key === " ") {
    this.handleSelect(event);
  } else {
    this.handleMove(event);
  }
};
_getItem = new WeakSet();
getItem_fn = function(event) {
  return event.composedPath().find((el) => {
    return ["menuitemradio", "menuitemcheckbox"].includes(
      el.role
    );
  });
};
_getTabItem = new WeakSet();
getTabItem_fn = function() {
  return this.items.find((item) => item.tabIndex === 0);
};
_setTabItem = new WeakSet();
setTabItem_fn = function(tabItem) {
  for (const item of this.items) {
    item.tabIndex = item === tabItem ? 0 : -1;
  }
};
_selectItem = new WeakSet();
selectItem_fn = function(item, toggle) {
  const oldCheckedItems = [...this.checkedItems];
  if (item.type === "radio") {
    this.radioGroupItems.forEach((el) => el.checked = false);
  }
  if (toggle) {
    item.checked = !item.checked;
  } else {
    item.checked = true;
  }
  if (this.checkedItems.some((opt, i) => opt != oldCheckedItems[i])) {
    this.dispatchEvent(
      new Event("change", { bubbles: true, composed: true })
    );
  }
};
MediaChromeMenu.shadowRootOptions = { mode: "open" };
MediaChromeMenu.getTemplateHTML = getTemplateHTML;
function isMenuItem(element) {
  return ["menuitem", "menuitemradio", "menuitemcheckbox"].includes(
    element == null ? void 0 : element.role
  );
}
function getBoundsElement(host) {
  var _a;
  return (_a = host.getAttribute("bounds") ? closestComposedNode(host, `#${host.getAttribute("bounds")}`) : getMediaController(host) || host.parentElement) != null ? _a : host;
}
if (!globalThis.customElements.get("media-chrome-menu")) {
  globalThis.customElements.define("media-chrome-menu", MediaChromeMenu);
}
var media_chrome_menu_default = MediaChromeMenu;
export {
  Attributes,
  MediaChromeMenu,
  createIndicator,
  createMenuItem,
  media_chrome_menu_default as default
};
