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
var media_chrome_range_exports = {};
__export(media_chrome_range_exports, {
  MediaChromeRange: () => MediaChromeRange,
  default: () => media_chrome_range_default
});
module.exports = __toCommonJS(media_chrome_range_exports);
var import_constants = require("./constants.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_element_utils = require("./utils/element-utils.js");
var import_resize_observer = require("./utils/resize-observer.js");
var _mediaController, _isInputTarget, _startpoint, _endpoint, _cssRules, _segments, _onFocusIn, _onFocusOut, _updateComputedStyles, _updateActiveSegment, updateActiveSegment_fn, _enableUserEvents, enableUserEvents_fn, _disableUserEvents, disableUserEvents_fn, _handlePointerDown, handlePointerDown_fn, _handlePointerEnter, handlePointerEnter_fn, _handlePointerUp, handlePointerUp_fn, _handlePointerLeave, handlePointerLeave_fn, _handlePointerMove, handlePointerMove_fn;
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        --_focus-box-shadow: var(--media-focus-box-shadow, inset 0 0 0 2px rgb(27 127 204 / .9));
        --_media-range-padding: var(--media-range-padding, var(--media-control-padding, 10px));

        box-shadow: var(--_focus-visible-box-shadow, none);
        background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        height: calc(var(--media-control-height, 24px) + 2 * var(--_media-range-padding));
        display: inline-flex;
        align-items: center;
        ${/* Don't horizontal align w/ justify-content! #container can go negative on the x-axis w/ small width. */
    ""}
        vertical-align: middle;
        box-sizing: border-box;
        position: relative;
        width: 100px;
        transition: background .15s linear;
        cursor: var(--media-cursor, pointer);
        pointer-events: auto;
        touch-action: none; ${/* Prevent scrolling when dragging on mobile. */
    ""}
      }

      ${/* Reset before `outline` on track could be set by a CSS var */
    ""}
      input[type=range]:focus {
        outline: 0;
      }
      input[type=range]:focus::-webkit-slider-runnable-track {
        outline: 0;
      }

      :host(:hover) {
        background: var(--media-control-hover-background, rgb(50 50 70 / .7));
      }

      #leftgap {
        padding-left: var(--media-range-padding-left, var(--_media-range-padding));
      }

      #rightgap {
        padding-right: var(--media-range-padding-right, var(--_media-range-padding));
      }

      #startpoint,
      #endpoint {
        position: absolute;
      }

      #endpoint {
        right: 0;
      }

      #container {
        ${/* Not using the CSS `padding` prop makes it easier for slide open volume ranges so the width can be zero. */
    ""}
        width: var(--media-range-track-width, 100%);
        transform: translate(var(--media-range-track-translate-x, 0px), var(--media-range-track-translate-y, 0px));
        position: relative;
        height: 100%;
        display: flex;
        align-items: center;
        min-width: 40px;
      }

      #range {
        ${/* The input range acts as a hover and hit zone for input events. */
    ""}
        display: var(--media-time-range-hover-display, block);
        bottom: var(--media-time-range-hover-bottom, -7px);
        height: var(--media-time-range-hover-height, max(100% + 7px, 25px));
        width: 100%;
        position: absolute;
        cursor: var(--media-cursor, pointer);

        -webkit-appearance: none; ${/* Hides the slider so that custom slider can be made */
    ""}
        -webkit-tap-highlight-color: transparent;
        background: transparent; ${/* Otherwise white in Chrome */
    ""}
        margin: 0;
        z-index: 1;
      }

      @media (hover: hover) {
        #range {
          bottom: var(--media-time-range-hover-bottom, -5px);
          height: var(--media-time-range-hover-height, max(100% + 5px, 20px));
        }
      }

      ${/* Special styling for WebKit/Blink */
    ""}
      ${/* Make thumb width/height small so it has no effect on range click position. */
    ""}
      #range::-webkit-slider-thumb {
        -webkit-appearance: none;
        background: transparent;
        width: .1px;
        height: .1px;
      }

      ${/* The thumb is not positioned relative to the track in Firefox */
    ""}
      #range::-moz-range-thumb {
        background: transparent;
        border: transparent;
        width: .1px;
        height: .1px;
      }

      #appearance {
        height: var(--media-range-track-height, 4px);
        display: flex;
        flex-direction: column;
        justify-content: center;
        width: 100%;
        position: absolute;
        ${/* Required for Safari to stop glitching track height on hover */
    ""}
        will-change: transform;
      }

      #track {
        background: var(--media-range-track-background, rgb(255 255 255 / .2));
        border-radius: var(--media-range-track-border-radius, 1px);
        border: var(--media-range-track-border, none);
        outline: var(--media-range-track-outline);
        outline-offset: var(--media-range-track-outline-offset);
        backdrop-filter: var(--media-range-track-backdrop-filter);
        -webkit-backdrop-filter: var(--media-range-track-backdrop-filter);
        box-shadow: var(--media-range-track-box-shadow, none);
        position: absolute;
        width: 100%;
        height: 100%;
        overflow: hidden;
      }

      #progress,
      #pointer {
        position: absolute;
        height: 100%;
        will-change: width;
      }

      #progress {
        background: var(--media-range-bar-color, var(--media-primary-color, rgb(238 238 238)));
        transition: var(--media-range-track-transition);
      }

      #pointer {
        background: var(--media-range-track-pointer-background);
        border-right: var(--media-range-track-pointer-border-right);
        transition: visibility .25s, opacity .25s;
        visibility: hidden;
        opacity: 0;
      }

      @media (hover: hover) {
        :host(:hover) #pointer {
          transition: visibility .5s, opacity .5s;
          visibility: visible;
          opacity: 1;
        }
      }

      #thumb,
      ::slotted([slot=thumb]) {
        width: var(--media-range-thumb-width, 10px);
        height: var(--media-range-thumb-height, 10px);
        transition: var(--media-range-thumb-transition);
        transform: var(--media-range-thumb-transform, none);
        opacity: var(--media-range-thumb-opacity, 1);
        translate: -50%;
        position: absolute;
        left: 0;
        cursor: var(--media-cursor, pointer);
      }

      #thumb {
        border-radius: var(--media-range-thumb-border-radius, 10px);
        background: var(--media-range-thumb-background, var(--media-primary-color, rgb(238 238 238)));
        box-shadow: var(--media-range-thumb-box-shadow, 1px 1px 1px transparent);
        border: var(--media-range-thumb-border, none);
      }

      :host([disabled]) #thumb {
        background-color: #777;
      }

      .segments #appearance {
        height: var(--media-range-segment-hover-height, 7px);
      }

      #track {
        clip-path: url(#segments-clipping);
      }

      #segments {
        --segments-gap: var(--media-range-segments-gap, 2px);
        position: absolute;
        width: 100%;
        height: 100%;
      }

      #segments-clipping {
        transform: translateX(calc(var(--segments-gap) / 2));
      }

      #segments-clipping:empty {
        display: none;
      }

      #segments-clipping rect {
        height: var(--media-range-track-height, 4px);
        y: calc((var(--media-range-segment-hover-height, 7px) - var(--media-range-track-height, 4px)) / 2);
        transition: var(--media-range-segment-transition, transform .1s ease-in-out);
        transform: var(--media-range-segment-transform, scaleY(1));
        transform-origin: center;
      }
    </style>
    <div id="leftgap"></div>
    <div id="container">
      <div id="startpoint"></div>
      <div id="endpoint"></div>
      <div id="appearance">
        <div id="track" part="track">
          <div id="pointer"></div>
          <div id="progress" part="progress"></div>
        </div>
        <slot name="thumb">
          <div id="thumb" part="thumb"></div>
        </slot>
        <svg id="segments"><clipPath id="segments-clipping"></clipPath></svg>
      </div>
      <input id="range" type="range" min="0" max="1" step="any" value="0">
    </div>
    <div id="rightgap"></div>
  `
  );
}
class MediaChromeRange extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _updateActiveSegment);
    __privateAdd(this, _enableUserEvents);
    __privateAdd(this, _disableUserEvents);
    __privateAdd(this, _handlePointerDown);
    __privateAdd(this, _handlePointerEnter);
    __privateAdd(this, _handlePointerUp);
    __privateAdd(this, _handlePointerLeave);
    __privateAdd(this, _handlePointerMove);
    __privateAdd(this, _mediaController, void 0);
    __privateAdd(this, _isInputTarget, void 0);
    __privateAdd(this, _startpoint, void 0);
    __privateAdd(this, _endpoint, void 0);
    __privateAdd(this, _cssRules, {});
    __privateAdd(this, _segments, []);
    __privateAdd(this, _onFocusIn, () => {
      if (this.range.matches(":focus-visible")) {
        const { style } = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, ":host");
        style.setProperty(
          "--_focus-visible-box-shadow",
          "var(--_focus-box-shadow)"
        );
      }
    });
    __privateAdd(this, _onFocusOut, () => {
      const { style } = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, ":host");
      style.removeProperty("--_focus-visible-box-shadow");
    });
    __privateAdd(this, _updateComputedStyles, () => {
      const clipping = this.shadowRoot.querySelector("#segments-clipping");
      if (clipping)
        clipping.parentNode.append(clipping);
    });
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      const html = this.constructor.getTemplateHTML(attrs);
      this.shadowRoot.setHTMLUnsafe ? this.shadowRoot.setHTMLUnsafe(html) : this.shadowRoot.innerHTML = html;
    }
    this.container = this.shadowRoot.querySelector("#container");
    __privateSet(this, _startpoint, this.shadowRoot.querySelector("#startpoint"));
    __privateSet(this, _endpoint, this.shadowRoot.querySelector("#endpoint"));
    this.range = this.shadowRoot.querySelector("#range");
    this.appearance = this.shadowRoot.querySelector("#appearance");
  }
  static get observedAttributes() {
    return [
      "disabled",
      "aria-disabled",
      import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER
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
    } else if (attrName === "disabled" || attrName === "aria-disabled" && oldValue !== newValue) {
      if (newValue == null) {
        this.range.removeAttribute(attrName);
        __privateMethod(this, _enableUserEvents, enableUserEvents_fn).call(this);
      } else {
        this.range.setAttribute(attrName, newValue);
        __privateMethod(this, _disableUserEvents, disableUserEvents_fn).call(this);
      }
    }
  }
  connectedCallback() {
    var _a, _b, _c;
    const { style } = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, ":host");
    style.setProperty(
      "display",
      `var(--media-control-display, var(--${this.localName}-display, inline-flex))`
    );
    __privateGet(this, _cssRules).pointer = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, "#pointer");
    __privateGet(this, _cssRules).progress = (0, import_element_utils.getOrInsertCSSRule)(this.shadowRoot, "#progress");
    __privateGet(this, _cssRules).thumb = (0, import_element_utils.getOrInsertCSSRule)(
      this.shadowRoot,
      '#thumb, ::slotted([slot="thumb"])'
    );
    __privateGet(this, _cssRules).activeSegment = (0, import_element_utils.getOrInsertCSSRule)(
      this.shadowRoot,
      "#segments-clipping rect:nth-child(0)"
    );
    const mediaControllerId = this.getAttribute(
      import_constants.MediaStateReceiverAttributes.MEDIA_CONTROLLER
    );
    if (mediaControllerId) {
      __privateSet(this, _mediaController, (_a = this.getRootNode()) == null ? void 0 : _a.getElementById(
        mediaControllerId
      ));
      (_c = (_b = __privateGet(this, _mediaController)) == null ? void 0 : _b.associateElement) == null ? void 0 : _c.call(_b, this);
    }
    this.updateBar();
    this.shadowRoot.addEventListener("focusin", __privateGet(this, _onFocusIn));
    this.shadowRoot.addEventListener("focusout", __privateGet(this, _onFocusOut));
    __privateMethod(this, _enableUserEvents, enableUserEvents_fn).call(this);
    (0, import_resize_observer.observeResize)(this.container, __privateGet(this, _updateComputedStyles));
  }
  disconnectedCallback() {
    var _a, _b;
    __privateMethod(this, _disableUserEvents, disableUserEvents_fn).call(this);
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    __privateSet(this, _mediaController, null);
    this.shadowRoot.removeEventListener("focusin", __privateGet(this, _onFocusIn));
    this.shadowRoot.removeEventListener("focusout", __privateGet(this, _onFocusOut));
    (0, import_resize_observer.unobserveResize)(this.container, __privateGet(this, _updateComputedStyles));
  }
  updatePointerBar(evt) {
    var _a;
    (_a = __privateGet(this, _cssRules).pointer) == null ? void 0 : _a.style.setProperty(
      "width",
      `${this.getPointerRatio(evt) * 100}%`
    );
  }
  updateBar() {
    var _a, _b;
    const rangePercent = this.range.valueAsNumber * 100;
    (_a = __privateGet(this, _cssRules).progress) == null ? void 0 : _a.style.setProperty("width", `${rangePercent}%`);
    (_b = __privateGet(this, _cssRules).thumb) == null ? void 0 : _b.style.setProperty("left", `${rangePercent}%`);
  }
  updateSegments(segments) {
    const clipping = this.shadowRoot.querySelector("#segments-clipping");
    clipping.textContent = "";
    this.container.classList.toggle("segments", !!(segments == null ? void 0 : segments.length));
    if (!(segments == null ? void 0 : segments.length))
      return;
    const normalized = [
      .../* @__PURE__ */ new Set([
        +this.range.min,
        ...segments.flatMap((s) => [s.start, s.end]),
        +this.range.max
      ])
    ];
    __privateSet(this, _segments, [...normalized]);
    const lastMarker = normalized.pop();
    for (const [i, marker] of normalized.entries()) {
      const [isFirst, isLast] = [i === 0, i === normalized.length - 1];
      const x = isFirst ? "calc(var(--segments-gap) / -1)" : `${marker * 100}%`;
      const x2 = isLast ? lastMarker : normalized[i + 1];
      const width = `calc(${(x2 - marker) * 100}%${isFirst || isLast ? "" : ` - var(--segments-gap)`})`;
      const segmentEl = import_server_safe_globals.document.createElementNS(
        "http://www.w3.org/2000/svg",
        "rect"
      );
      const cssRule = (0, import_element_utils.getOrInsertCSSRule)(
        this.shadowRoot,
        `#segments-clipping rect:nth-child(${i + 1})`
      );
      cssRule.style.setProperty("x", x);
      cssRule.style.setProperty("width", width);
      clipping.append(segmentEl);
    }
  }
  getPointerRatio(evt) {
    return (0, import_element_utils.getPointProgressOnLine)(
      evt.clientX,
      evt.clientY,
      __privateGet(this, _startpoint).getBoundingClientRect(),
      __privateGet(this, _endpoint).getBoundingClientRect()
    );
  }
  get dragging() {
    return this.hasAttribute("dragging");
  }
  handleEvent(evt) {
    switch (evt.type) {
      case "pointermove":
        __privateMethod(this, _handlePointerMove, handlePointerMove_fn).call(this, evt);
        break;
      case "input":
        this.updateBar();
        break;
      case "pointerenter":
        __privateMethod(this, _handlePointerEnter, handlePointerEnter_fn).call(this, evt);
        break;
      case "pointerdown":
        __privateMethod(this, _handlePointerDown, handlePointerDown_fn).call(this, evt);
        break;
      case "pointerup":
        __privateMethod(this, _handlePointerUp, handlePointerUp_fn).call(this);
        break;
      case "pointerleave":
        __privateMethod(this, _handlePointerLeave, handlePointerLeave_fn).call(this);
        break;
    }
  }
  get keysUsed() {
    return ["ArrowUp", "ArrowRight", "ArrowDown", "ArrowLeft"];
  }
}
_mediaController = new WeakMap();
_isInputTarget = new WeakMap();
_startpoint = new WeakMap();
_endpoint = new WeakMap();
_cssRules = new WeakMap();
_segments = new WeakMap();
_onFocusIn = new WeakMap();
_onFocusOut = new WeakMap();
_updateComputedStyles = new WeakMap();
_updateActiveSegment = new WeakSet();
updateActiveSegment_fn = function(evt) {
  const rule = __privateGet(this, _cssRules).activeSegment;
  if (!rule)
    return;
  const pointerRatio = this.getPointerRatio(evt);
  const segmentIndex = __privateGet(this, _segments).findIndex((start, i, arr) => {
    const end = arr[i + 1];
    return end != null && pointerRatio >= start && pointerRatio <= end;
  });
  const selectorText = `#segments-clipping rect:nth-child(${segmentIndex + 1})`;
  if (rule.selectorText != selectorText || !rule.style.transform) {
    rule.selectorText = selectorText;
    rule.style.setProperty(
      "transform",
      "var(--media-range-segment-hover-transform, scaleY(2))"
    );
  }
};
_enableUserEvents = new WeakSet();
enableUserEvents_fn = function() {
  if (this.hasAttribute("disabled"))
    return;
  this.addEventListener("input", this);
  this.addEventListener("pointerdown", this);
  this.addEventListener("pointerenter", this);
};
_disableUserEvents = new WeakSet();
disableUserEvents_fn = function() {
  var _a, _b;
  this.removeEventListener("input", this);
  this.removeEventListener("pointerdown", this);
  this.removeEventListener("pointerenter", this);
  (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.removeEventListener("pointerup", this);
  (_b = import_server_safe_globals.globalThis.window) == null ? void 0 : _b.removeEventListener("pointermove", this);
};
_handlePointerDown = new WeakSet();
handlePointerDown_fn = function(evt) {
  var _a;
  __privateSet(this, _isInputTarget, evt.composedPath().includes(this.range));
  (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.addEventListener("pointerup", this);
};
_handlePointerEnter = new WeakSet();
handlePointerEnter_fn = function(evt) {
  var _a;
  if (evt.pointerType !== "mouse")
    __privateMethod(this, _handlePointerDown, handlePointerDown_fn).call(this, evt);
  this.addEventListener("pointerleave", this);
  (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.addEventListener("pointermove", this);
};
_handlePointerUp = new WeakSet();
handlePointerUp_fn = function() {
  var _a;
  (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.removeEventListener("pointerup", this);
  this.toggleAttribute("dragging", false);
  this.range.disabled = this.hasAttribute("disabled");
};
_handlePointerLeave = new WeakSet();
handlePointerLeave_fn = function() {
  var _a, _b;
  this.removeEventListener("pointerleave", this);
  (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.removeEventListener("pointermove", this);
  this.toggleAttribute("dragging", false);
  this.range.disabled = this.hasAttribute("disabled");
  (_b = __privateGet(this, _cssRules).activeSegment) == null ? void 0 : _b.style.removeProperty("transform");
};
_handlePointerMove = new WeakSet();
handlePointerMove_fn = function(evt) {
  this.toggleAttribute(
    "dragging",
    evt.buttons === 1 || evt.pointerType !== "mouse"
  );
  this.updatePointerBar(evt);
  __privateMethod(this, _updateActiveSegment, updateActiveSegment_fn).call(this, evt);
  if (this.dragging && (evt.pointerType !== "mouse" || !__privateGet(this, _isInputTarget))) {
    this.range.disabled = true;
    this.range.valueAsNumber = this.getPointerRatio(evt);
    this.range.dispatchEvent(
      new Event("input", { bubbles: true, composed: true })
    );
  }
};
MediaChromeRange.shadowRootOptions = { mode: "open" };
MediaChromeRange.getTemplateHTML = getTemplateHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-chrome-range")) {
  import_server_safe_globals.globalThis.customElements.define("media-chrome-range", MediaChromeRange);
}
var media_chrome_range_default = MediaChromeRange;
