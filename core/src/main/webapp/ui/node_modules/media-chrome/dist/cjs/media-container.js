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
var media_container_exports = {};
__export(media_container_exports, {
  Attributes: () => Attributes,
  MediaContainer: () => MediaContainer,
  default: () => media_container_default
});
module.exports = __toCommonJS(media_container_exports);
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_resize_observer = require("./utils/resize-observer.js");
var import_media_gesture_receiver = require("./media-gesture-receiver.js");
var import_i18n = require("./utils/i18n.js");
var import_element_utils = require("./utils/element-utils.js");
var import_media_gesture_receiver2 = __toESM(require("./media-gesture-receiver.js"), 1);
var _pointerDownTimeStamp, _currentMedia, _inactiveTimeout, _autohide, _mutationObserver, _handleMutation, handleMutation_fn, _isResizePending, _handleResize, _handlePointerMove, handlePointerMove_fn, _handlePointerUp, handlePointerUp_fn, _setInactive, setInactive_fn, _setActive, setActive_fn, _scheduleInactive, scheduleInactive_fn;
const Attributes = {
  AUDIO: "audio",
  AUTOHIDE: "autohide",
  BREAKPOINTS: "breakpoints",
  GESTURES_DISABLED: "gesturesdisabled",
  KEYBOARD_CONTROL: "keyboardcontrol",
  NO_AUTOHIDE: "noautohide",
  USER_INACTIVE: "userinactive",
  AUTOHIDE_OVER_CONTROLS: "autohideovercontrols"
};
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      ${/*
    * outline on media is turned off because it is allowed to get focus to faciliate hotkeys.
    * However, on keyboard interactions, the focus outline is shown,
    * which is particularly noticeable when going fullscreen via hotkeys.
    */
    ""}
      :host([${import_constants.MediaUIAttributes.MEDIA_IS_FULLSCREEN}]) ::slotted([slot=media]) {
        outline: none;
      }

      :host {
        box-sizing: border-box;
        position: relative;
        display: inline-block;
        line-height: 0;
        background-color: var(--media-background-color, #000);
      }

      :host(:not([${Attributes.AUDIO}])) [part~=layer]:not([part~=media-layer]) {
        position: absolute;
        top: 0;
        left: 0;
        bottom: 0;
        right: 0;
        display: flex;
        flex-flow: column nowrap;
        align-items: start;
        pointer-events: none;
        background: none;
      }

      slot[name=media] {
        display: var(--media-slot-display, contents);
      }

      ${/*
    * when in audio mode, hide the slotted media element by default
    */
    ""}
      :host([${Attributes.AUDIO}]) slot[name=media] {
        display: var(--media-slot-display, none);
      }

      ${/*
    * when in audio mode, hide the gesture-layer which causes media-controller to be taller than the control bar
    */
    ""}
      :host([${Attributes.AUDIO}]) [part~=layer][part~=gesture-layer] {
        height: 0;
        display: block;
      }

      ${/*
    * if gestures are disabled, don't accept pointer-events
    */
    ""}
      :host(:not([${Attributes.AUDIO}])[${Attributes.GESTURES_DISABLED}]) ::slotted([slot=gestures-chrome]),
          :host(:not([${Attributes.AUDIO}])[${Attributes.GESTURES_DISABLED}]) media-gesture-receiver[slot=gestures-chrome] {
        display: none;
      }

      ${/*
    * any slotted element that isn't a poster or media slot should be pointer-events auto
    * we'll want to add here any slotted elements that shouldn't get pointer-events by default when slotted
    */
    ""}
      ::slotted(:not([slot=media]):not([slot=poster]):not(media-loading-indicator):not([role=dialog]):not([hidden])) {
        pointer-events: auto;
      }

      :host(:not([${Attributes.AUDIO}])) *[part~=layer][part~=centered-layer] {
        align-items: center;
        justify-content: center;
      }

      :host(:not([${Attributes.AUDIO}])) ::slotted(media-gesture-receiver[slot=gestures-chrome]),
      :host(:not([${Attributes.AUDIO}])) media-gesture-receiver[slot=gestures-chrome] {
        align-self: stretch;
        flex-grow: 1;
      }

      slot[name=middle-chrome] {
        display: inline;
        flex-grow: 1;
        pointer-events: none;
        background: none;
      }

      ${/* Position the media and poster elements to fill the container */
    ""}
      ::slotted([slot=media]),
      ::slotted([slot=poster]) {
        width: 100%;
        height: 100%;
      }

      ${/* Video specific styles */
    ""}
      :host(:not([${Attributes.AUDIO}])) .spacer {
        flex-grow: 1;
      }

      ${/* Safari needs this to actually make the element fill the window */
    ""}
      :host(:-webkit-full-screen) {
        ${/* Needs to use !important otherwise easy to break */
    ""}
        width: 100% !important;
        height: 100% !important;
      }

      ${/* Only add these if auto hide is not disabled */
    ""}
      ::slotted(:not([slot=media]):not([slot=poster]):not([${Attributes.NO_AUTOHIDE}]):not([hidden]):not([role=dialog])) {
        opacity: 1;
        transition: var(--media-control-transition-in, opacity 0.25s);
      }

      ${/* Hide controls when inactive, not paused, not audio and auto hide not disabled */
    ""}
      :host([${Attributes.USER_INACTIVE}]:not([${import_constants.MediaUIAttributes.MEDIA_PAUSED}]):not([${import_constants.MediaUIAttributes.MEDIA_IS_AIRPLAYING}]):not([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}]):not([${Attributes.AUDIO}])) ::slotted(:not([slot=media]):not([slot=poster]):not([${Attributes.NO_AUTOHIDE}]):not([role=dialog])) {
        opacity: 0;
        transition: var(--media-control-transition-out, opacity 1s);
      }

      :host([${Attributes.USER_INACTIVE}]:not([${Attributes.NO_AUTOHIDE}]):not([${import_constants.MediaUIAttributes.MEDIA_PAUSED}]):not([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}]):not([${Attributes.AUDIO}])) ::slotted([slot=media]) {
        cursor: none;
      }

      :host([${Attributes.USER_INACTIVE}][${Attributes.AUTOHIDE_OVER_CONTROLS}]:not([${Attributes.NO_AUTOHIDE}]):not([${import_constants.MediaUIAttributes.MEDIA_PAUSED}]):not([${import_constants.MediaUIAttributes.MEDIA_IS_CASTING}]):not([${Attributes.AUDIO}])) * {
        --media-cursor: none;
        cursor: none;
      }


      ::slotted(media-control-bar)  {
        align-self: stretch;
      }

      ${/* ::slotted([slot=poster]) doesn't work for slot fallback content so hide parent slot instead */
    ""}
      :host(:not([${Attributes.AUDIO}])[${import_constants.MediaUIAttributes.MEDIA_HAS_PLAYED}]) slot[name=poster] {
        display: none;
      }

      ::slotted([role=dialog]) {
        width: 100%;
        height: 100%;
        align-self: center;
      }

      ::slotted([role=menu]) {
        align-self: end;
      }
    </style>

    <slot name="media" part="layer media-layer"></slot>
    <slot name="poster" part="layer poster-layer"></slot>
    <slot name="gestures-chrome" part="layer gesture-layer">
      <media-gesture-receiver slot="gestures-chrome">
        <template shadowrootmode="${import_media_gesture_receiver2.default.shadowRootOptions.mode}">
          ${import_media_gesture_receiver2.default.getTemplateHTML({})}
        </template>
      </media-gesture-receiver>
    </slot>
    <span part="layer vertical-layer">
      <slot name="top-chrome" part="top chrome"></slot>
      <slot name="middle-chrome" part="middle chrome"></slot>
      <slot name="centered-chrome" part="layer centered-layer center centered chrome"></slot>
      ${/* default, effectively "bottom-chrome" */
    ""}
      <slot part="bottom chrome"></slot>
    </span>
    <slot name="dialog" part="layer dialog-layer"></slot>
  `
  );
}
const MEDIA_UI_ATTRIBUTE_NAMES = Object.values(import_constants.MediaUIAttributes);
const defaultBreakpoints = "sm:384 md:576 lg:768 xl:960";
function resizeCallback(entry) {
  setBreakpoints(entry.target, entry.contentRect.width);
}
function setBreakpoints(container, width) {
  var _a;
  if (!container.isConnected)
    return;
  const breakpoints = (_a = container.getAttribute(Attributes.BREAKPOINTS)) != null ? _a : defaultBreakpoints;
  const ranges = createBreakpointMap(breakpoints);
  const activeBreakpoints = getBreakpoints(ranges, width);
  let changed = false;
  Object.keys(ranges).forEach((name) => {
    if (activeBreakpoints.includes(name)) {
      if (!container.hasAttribute(`breakpoint${name}`)) {
        container.setAttribute(`breakpoint${name}`, "");
        changed = true;
      }
      return;
    }
    if (container.hasAttribute(`breakpoint${name}`)) {
      container.removeAttribute(`breakpoint${name}`);
      changed = true;
    }
  });
  if (changed) {
    const evt = new CustomEvent(import_constants.MediaStateChangeEvents.BREAKPOINTS_CHANGE, {
      detail: activeBreakpoints
    });
    container.dispatchEvent(evt);
  }
  if (!container.breakpointsComputed) {
    container.breakpointsComputed = true;
    container.dispatchEvent(
      new CustomEvent(import_constants.MediaStateChangeEvents.BREAKPOINTS_COMPUTED, {
        bubbles: true,
        composed: true
      })
    );
  }
}
function createBreakpointMap(breakpoints) {
  const pairs = breakpoints.split(/\s+/);
  return Object.fromEntries(pairs.map((pair) => pair.split(":")));
}
function getBreakpoints(breakpoints, width) {
  return Object.keys(breakpoints).filter((name) => {
    return width >= parseInt(breakpoints[name]);
  });
}
class MediaContainer extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _handleMutation);
    __privateAdd(this, _handlePointerMove);
    __privateAdd(this, _handlePointerUp);
    __privateAdd(this, _setInactive);
    __privateAdd(this, _setActive);
    __privateAdd(this, _scheduleInactive);
    __privateAdd(this, _pointerDownTimeStamp, 0);
    __privateAdd(this, _currentMedia, null);
    __privateAdd(this, _inactiveTimeout, null);
    __privateAdd(this, _autohide, void 0);
    this.breakpointsComputed = false;
    __privateAdd(this, _mutationObserver, new MutationObserver(__privateMethod(this, _handleMutation, handleMutation_fn).bind(this)));
    __privateAdd(this, _isResizePending, false);
    __privateAdd(this, _handleResize, (entry) => {
      if (__privateGet(this, _isResizePending))
        return;
      setTimeout(() => {
        resizeCallback(entry);
        __privateSet(this, _isResizePending, false);
      }, 0);
      __privateSet(this, _isResizePending, true);
    });
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      const html = this.constructor.getTemplateHTML(attrs);
      this.shadowRoot.setHTMLUnsafe ? this.shadowRoot.setHTMLUnsafe(html) : this.shadowRoot.innerHTML = html;
    }
    const chainedSlot = this.querySelector(
      ":scope > slot[slot=media]"
    );
    if (chainedSlot) {
      chainedSlot.addEventListener("slotchange", () => {
        const slotEls = chainedSlot.assignedElements({ flatten: true });
        if (!slotEls.length) {
          if (__privateGet(this, _currentMedia)) {
            this.mediaUnsetCallback(__privateGet(this, _currentMedia));
          }
          return;
        }
        this.handleMediaUpdated(this.media);
      });
    }
  }
  static get observedAttributes() {
    return [Attributes.AUTOHIDE, Attributes.GESTURES_DISABLED].concat(MEDIA_UI_ATTRIBUTE_NAMES).filter(
      (name) => ![
        import_constants.MediaUIAttributes.MEDIA_RENDITION_LIST,
        import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST,
        import_constants.MediaUIAttributes.MEDIA_CHAPTERS_CUES,
        import_constants.MediaUIAttributes.MEDIA_WIDTH,
        import_constants.MediaUIAttributes.MEDIA_HEIGHT,
        import_constants.MediaUIAttributes.MEDIA_ERROR,
        import_constants.MediaUIAttributes.MEDIA_ERROR_MESSAGE
      ].includes(name)
    );
  }
  // Could share this code with media-chrome-html-element instead
  attributeChangedCallback(attrName, _oldValue, newValue) {
    if (attrName.toLowerCase() == Attributes.AUTOHIDE) {
      this.autohide = newValue;
    }
  }
  // First direct child with slot=media, or null
  get media() {
    let media = this.querySelector(":scope > [slot=media]");
    if ((media == null ? void 0 : media.nodeName) == "SLOT")
      media = media.assignedElements({ flatten: true })[0];
    return media;
  }
  async handleMediaUpdated(media) {
    if (!media)
      return;
    __privateSet(this, _currentMedia, media);
    if (media.localName.includes("-")) {
      await import_server_safe_globals.globalThis.customElements.whenDefined(media.localName);
    }
    this.mediaSetCallback(media);
  }
  connectedCallback() {
    var _a;
    __privateGet(this, _mutationObserver).observe(this, { childList: true, subtree: true });
    (0, import_resize_observer.observeResize)(this, __privateGet(this, _handleResize));
    const isAudioChrome = this.getAttribute(Attributes.AUDIO) != null;
    const label = isAudioChrome ? (0, import_i18n.t)("audio player") : (0, import_i18n.t)("video player");
    this.setAttribute("role", "region");
    this.setAttribute("aria-label", label);
    this.handleMediaUpdated(this.media);
    this.setAttribute(Attributes.USER_INACTIVE, "");
    setBreakpoints(this, this.getBoundingClientRect().width);
    this.addEventListener("pointerdown", this);
    this.addEventListener("pointermove", this);
    this.addEventListener("pointerup", this);
    this.addEventListener("mouseleave", this);
    this.addEventListener("keyup", this);
    (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.addEventListener("mouseup", this);
  }
  disconnectedCallback() {
    var _a;
    __privateGet(this, _mutationObserver).disconnect();
    (0, import_resize_observer.unobserveResize)(this, __privateGet(this, _handleResize));
    if (this.media) {
      this.mediaUnsetCallback(this.media);
    }
    (_a = import_server_safe_globals.globalThis.window) == null ? void 0 : _a.removeEventListener("mouseup", this);
  }
  /**
   * @abstract
   */
  mediaSetCallback(_media) {
  }
  mediaUnsetCallback(_media) {
    __privateSet(this, _currentMedia, null);
  }
  handleEvent(event) {
    switch (event.type) {
      case "pointerdown":
        __privateSet(this, _pointerDownTimeStamp, event.timeStamp);
        break;
      case "pointermove":
        __privateMethod(this, _handlePointerMove, handlePointerMove_fn).call(this, event);
        break;
      case "pointerup":
        __privateMethod(this, _handlePointerUp, handlePointerUp_fn).call(this, event);
        break;
      case "mouseleave":
        __privateMethod(this, _setInactive, setInactive_fn).call(this);
        break;
      case "mouseup":
        this.removeAttribute(Attributes.KEYBOARD_CONTROL);
        break;
      case "keyup":
        __privateMethod(this, _scheduleInactive, scheduleInactive_fn).call(this);
        this.setAttribute(Attributes.KEYBOARD_CONTROL, "");
        break;
    }
  }
  set autohide(seconds) {
    const parsedSeconds = Number(seconds);
    __privateSet(this, _autohide, isNaN(parsedSeconds) ? 0 : parsedSeconds);
  }
  get autohide() {
    return (__privateGet(this, _autohide) === void 0 ? 2 : __privateGet(this, _autohide)).toString();
  }
  get breakpoints() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.BREAKPOINTS);
  }
  set breakpoints(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.BREAKPOINTS, value);
  }
  get audio() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.AUDIO);
  }
  set audio(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.AUDIO, value);
  }
  get gesturesDisabled() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.GESTURES_DISABLED);
  }
  set gesturesDisabled(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.GESTURES_DISABLED, value);
  }
  get keyboardControl() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.KEYBOARD_CONTROL);
  }
  set keyboardControl(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.KEYBOARD_CONTROL, value);
  }
  get noAutohide() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_AUTOHIDE);
  }
  set noAutohide(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_AUTOHIDE, value);
  }
  get autohideOverControls() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.AUTOHIDE_OVER_CONTROLS);
  }
  set autohideOverControls(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.AUTOHIDE_OVER_CONTROLS, value);
  }
  get userInteractive() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.USER_INACTIVE);
  }
  set userInteractive(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.USER_INACTIVE, value);
  }
}
_pointerDownTimeStamp = new WeakMap();
_currentMedia = new WeakMap();
_inactiveTimeout = new WeakMap();
_autohide = new WeakMap();
_mutationObserver = new WeakMap();
_handleMutation = new WeakSet();
handleMutation_fn = function(mutationsList) {
  const media = this.media;
  for (const mutation of mutationsList) {
    if (mutation.type !== "childList")
      continue;
    const removedNodes = mutation.removedNodes;
    for (const node of removedNodes) {
      if (node.slot != "media" || mutation.target != this)
        continue;
      let previousSibling = mutation.previousSibling && mutation.previousSibling.previousElementSibling;
      if (!previousSibling || !media) {
        this.mediaUnsetCallback(node);
      } else {
        let wasFirst = previousSibling.slot !== "media";
        while ((previousSibling = previousSibling.previousSibling) !== null) {
          if (previousSibling.slot == "media")
            wasFirst = false;
        }
        if (wasFirst)
          this.mediaUnsetCallback(node);
      }
    }
    if (media) {
      for (const node of mutation.addedNodes) {
        if (node === media)
          this.handleMediaUpdated(media);
      }
    }
  }
};
_isResizePending = new WeakMap();
_handleResize = new WeakMap();
_handlePointerMove = new WeakSet();
handlePointerMove_fn = function(event) {
  if (event.pointerType !== "mouse") {
    const MAX_TAP_DURATION = 250;
    if (event.timeStamp - __privateGet(this, _pointerDownTimeStamp) < MAX_TAP_DURATION)
      return;
  }
  __privateMethod(this, _setActive, setActive_fn).call(this);
  clearTimeout(__privateGet(this, _inactiveTimeout));
  const autohideOverControls = this.hasAttribute(
    Attributes.AUTOHIDE_OVER_CONTROLS
  );
  if ([this, this.media].includes(event.target) || autohideOverControls) {
    __privateMethod(this, _scheduleInactive, scheduleInactive_fn).call(this);
  }
};
_handlePointerUp = new WeakSet();
handlePointerUp_fn = function(event) {
  if (event.pointerType === "touch") {
    const controlsVisible = !this.hasAttribute(Attributes.USER_INACTIVE);
    if ([this, this.media].includes(event.target) && controlsVisible) {
      __privateMethod(this, _setInactive, setInactive_fn).call(this);
    } else {
      __privateMethod(this, _scheduleInactive, scheduleInactive_fn).call(this);
    }
  } else if (event.composedPath().some(
    (el) => ["media-play-button", "media-fullscreen-button"].includes(
      el == null ? void 0 : el.localName
    )
  )) {
    __privateMethod(this, _scheduleInactive, scheduleInactive_fn).call(this);
  }
};
_setInactive = new WeakSet();
setInactive_fn = function() {
  if (__privateGet(this, _autohide) < 0)
    return;
  if (this.hasAttribute(Attributes.USER_INACTIVE))
    return;
  this.setAttribute(Attributes.USER_INACTIVE, "");
  const evt = new import_server_safe_globals.globalThis.CustomEvent(
    import_constants.MediaStateChangeEvents.USER_INACTIVE_CHANGE,
    { composed: true, bubbles: true, detail: true }
  );
  this.dispatchEvent(evt);
};
_setActive = new WeakSet();
setActive_fn = function() {
  if (!this.hasAttribute(Attributes.USER_INACTIVE))
    return;
  this.removeAttribute(Attributes.USER_INACTIVE);
  const evt = new import_server_safe_globals.globalThis.CustomEvent(
    import_constants.MediaStateChangeEvents.USER_INACTIVE_CHANGE,
    { composed: true, bubbles: true, detail: false }
  );
  this.dispatchEvent(evt);
};
_scheduleInactive = new WeakSet();
scheduleInactive_fn = function() {
  __privateMethod(this, _setActive, setActive_fn).call(this);
  clearTimeout(__privateGet(this, _inactiveTimeout));
  const autohide = parseInt(this.autohide);
  if (autohide < 0)
    return;
  __privateSet(this, _inactiveTimeout, setTimeout(() => {
    __privateMethod(this, _setInactive, setInactive_fn).call(this);
  }, autohide * 1e3));
};
MediaContainer.shadowRootOptions = { mode: "open" };
MediaContainer.getTemplateHTML = getTemplateHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-container")) {
  import_server_safe_globals.globalThis.customElements.define("media-container", MediaContainer);
}
var media_container_default = MediaContainer;
