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
var media_controller_exports = {};
__export(media_controller_exports, {
  Attributes: () => Attributes,
  MediaController: () => MediaController,
  default: () => media_controller_default
});
module.exports = __toCommonJS(media_controller_exports);
var import_media_container = require("./media-container.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_attribute_token_list = require("./utils/attribute-token-list.js");
var import_utils = require("./utils/utils.js");
var import_captions = require("./utils/captions.js");
var import_constants = require("./constants.js");
var import_element_utils = require("./utils/element-utils.js");
var import_media_store = require("./media-store/media-store.js");
var import_i18n = require("./utils/i18n.js");
var _hotKeys, _fullscreenElement, _mediaStore, _mediaStateCallback, _mediaStoreUnsubscribe, _mediaStateEventHandler, _setupDefaultStore, setupDefaultStore_fn, _keyUpHandler, keyUpHandler_fn, _keyDownHandler, keyDownHandler_fn;
const ButtonPressedKeys = [
  "ArrowLeft",
  "ArrowRight",
  "Enter",
  " ",
  "f",
  "m",
  "k",
  "c"
];
const DEFAULT_SEEK_OFFSET = 10;
const Attributes = {
  DEFAULT_SUBTITLES: "defaultsubtitles",
  DEFAULT_STREAM_TYPE: "defaultstreamtype",
  DEFAULT_DURATION: "defaultduration",
  FULLSCREEN_ELEMENT: "fullscreenelement",
  HOTKEYS: "hotkeys",
  KEYS_USED: "keysused",
  LIVE_EDGE_OFFSET: "liveedgeoffset",
  SEEK_TO_LIVE_OFFSET: "seektoliveoffset",
  NO_AUTO_SEEK_TO_LIVE: "noautoseektolive",
  NO_HOTKEYS: "nohotkeys",
  NO_VOLUME_PREF: "novolumepref",
  NO_SUBTITLES_LANG_PREF: "nosubtitleslangpref",
  NO_DEFAULT_STORE: "nodefaultstore",
  KEYBOARD_FORWARD_SEEK_OFFSET: "keyboardforwardseekoffset",
  KEYBOARD_BACKWARD_SEEK_OFFSET: "keyboardbackwardseekoffset",
  LANG: "lang"
};
class MediaController extends import_media_container.MediaContainer {
  constructor() {
    super();
    __privateAdd(this, _setupDefaultStore);
    __privateAdd(this, _keyUpHandler);
    __privateAdd(this, _keyDownHandler);
    this.mediaStateReceivers = [];
    this.associatedElementSubscriptions = /* @__PURE__ */ new Map();
    __privateAdd(this, _hotKeys, new import_attribute_token_list.AttributeTokenList(this, Attributes.HOTKEYS));
    __privateAdd(this, _fullscreenElement, void 0);
    __privateAdd(this, _mediaStore, void 0);
    __privateAdd(this, _mediaStateCallback, void 0);
    __privateAdd(this, _mediaStoreUnsubscribe, void 0);
    __privateAdd(this, _mediaStateEventHandler, (event) => {
      var _a;
      (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch(event);
    });
    this.associateElement(this);
    let prevState = {};
    __privateSet(this, _mediaStateCallback, (nextState) => {
      Object.entries(nextState).forEach(([stateName, stateValue]) => {
        if (stateName in prevState && prevState[stateName] === stateValue)
          return;
        this.propagateMediaState(stateName, stateValue);
        const attrName = stateName.toLowerCase();
        const evt = new import_server_safe_globals.globalThis.CustomEvent(
          import_constants.AttributeToStateChangeEventMap[attrName],
          { composed: true, detail: stateValue }
        );
        this.dispatchEvent(evt);
      });
      prevState = nextState;
    });
    this.enableHotkeys();
  }
  static get observedAttributes() {
    return super.observedAttributes.concat(
      Attributes.NO_HOTKEYS,
      Attributes.HOTKEYS,
      Attributes.DEFAULT_STREAM_TYPE,
      Attributes.DEFAULT_SUBTITLES,
      Attributes.DEFAULT_DURATION,
      Attributes.LANG
    );
  }
  get mediaStore() {
    return __privateGet(this, _mediaStore);
  }
  set mediaStore(value) {
    var _a, _b;
    if (__privateGet(this, _mediaStore)) {
      (_a = __privateGet(this, _mediaStoreUnsubscribe)) == null ? void 0 : _a.call(this);
      __privateSet(this, _mediaStoreUnsubscribe, void 0);
    }
    __privateSet(this, _mediaStore, value);
    if (!__privateGet(this, _mediaStore) && !this.hasAttribute(Attributes.NO_DEFAULT_STORE)) {
      __privateMethod(this, _setupDefaultStore, setupDefaultStore_fn).call(this);
      return;
    }
    __privateSet(this, _mediaStoreUnsubscribe, (_b = __privateGet(this, _mediaStore)) == null ? void 0 : _b.subscribe(
      __privateGet(this, _mediaStateCallback)
    ));
  }
  get fullscreenElement() {
    var _a;
    return (_a = __privateGet(this, _fullscreenElement)) != null ? _a : this;
  }
  set fullscreenElement(element) {
    var _a;
    if (this.hasAttribute(Attributes.FULLSCREEN_ELEMENT)) {
      this.removeAttribute(Attributes.FULLSCREEN_ELEMENT);
    }
    __privateSet(this, _fullscreenElement, element);
    (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch({
      type: "fullscreenelementchangerequest",
      detail: this.fullscreenElement
    });
  }
  get defaultSubtitles() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.DEFAULT_SUBTITLES);
  }
  set defaultSubtitles(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.DEFAULT_SUBTITLES, value);
  }
  get defaultStreamType() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.DEFAULT_STREAM_TYPE);
  }
  set defaultStreamType(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.DEFAULT_STREAM_TYPE, value);
  }
  get defaultDuration() {
    return (0, import_element_utils.getNumericAttr)(this, Attributes.DEFAULT_DURATION);
  }
  set defaultDuration(value) {
    (0, import_element_utils.setNumericAttr)(this, Attributes.DEFAULT_DURATION, value);
  }
  get noHotkeys() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_HOTKEYS);
  }
  set noHotkeys(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_HOTKEYS, value);
  }
  get keysUsed() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.KEYS_USED);
  }
  set keysUsed(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.KEYS_USED, value);
  }
  get liveEdgeOffset() {
    return (0, import_element_utils.getNumericAttr)(this, Attributes.LIVE_EDGE_OFFSET);
  }
  set liveEdgeOffset(value) {
    (0, import_element_utils.setNumericAttr)(this, Attributes.LIVE_EDGE_OFFSET, value);
  }
  get noAutoSeekToLive() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_AUTO_SEEK_TO_LIVE);
  }
  set noAutoSeekToLive(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_AUTO_SEEK_TO_LIVE, value);
  }
  get noVolumePref() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_VOLUME_PREF);
  }
  set noVolumePref(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_VOLUME_PREF, value);
  }
  get noSubtitlesLangPref() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_SUBTITLES_LANG_PREF);
  }
  set noSubtitlesLangPref(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_SUBTITLES_LANG_PREF, value);
  }
  get noDefaultStore() {
    return (0, import_element_utils.getBooleanAttr)(this, Attributes.NO_DEFAULT_STORE);
  }
  set noDefaultStore(value) {
    (0, import_element_utils.setBooleanAttr)(this, Attributes.NO_DEFAULT_STORE, value);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a, _b, _c, _d, _e, _f, _g, _h;
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === Attributes.NO_HOTKEYS) {
      if (newValue !== oldValue && newValue === "") {
        if (this.hasAttribute(Attributes.HOTKEYS)) {
          console.warn(
            "Media Chrome: Both `hotkeys` and `nohotkeys` have been set. All hotkeys will be disabled."
          );
        }
        this.disableHotkeys();
      } else if (newValue !== oldValue && newValue === null) {
        this.enableHotkeys();
      }
    } else if (attrName === Attributes.HOTKEYS) {
      __privateGet(this, _hotKeys).value = newValue;
    } else if (attrName === Attributes.DEFAULT_SUBTITLES && newValue !== oldValue) {
      (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch({
        type: "optionschangerequest",
        detail: {
          defaultSubtitles: this.hasAttribute(Attributes.DEFAULT_SUBTITLES)
        }
      });
    } else if (attrName === Attributes.DEFAULT_STREAM_TYPE) {
      (_c = __privateGet(this, _mediaStore)) == null ? void 0 : _c.dispatch({
        type: "optionschangerequest",
        detail: {
          defaultStreamType: (_b = this.getAttribute(Attributes.DEFAULT_STREAM_TYPE)) != null ? _b : void 0
        }
      });
    } else if (attrName === Attributes.LIVE_EDGE_OFFSET) {
      (_d = __privateGet(this, _mediaStore)) == null ? void 0 : _d.dispatch({
        type: "optionschangerequest",
        detail: {
          liveEdgeOffset: this.hasAttribute(Attributes.LIVE_EDGE_OFFSET) ? +this.getAttribute(Attributes.LIVE_EDGE_OFFSET) : void 0,
          seekToLiveOffset: !this.hasAttribute(Attributes.SEEK_TO_LIVE_OFFSET) ? +this.getAttribute(Attributes.LIVE_EDGE_OFFSET) : void 0
        }
      });
    } else if (attrName === Attributes.SEEK_TO_LIVE_OFFSET) {
      (_e = __privateGet(this, _mediaStore)) == null ? void 0 : _e.dispatch({
        type: "optionschangerequest",
        detail: {
          seekToLiveOffset: this.hasAttribute(Attributes.SEEK_TO_LIVE_OFFSET) ? +this.getAttribute(Attributes.SEEK_TO_LIVE_OFFSET) : void 0
        }
      });
    } else if (attrName === Attributes.NO_AUTO_SEEK_TO_LIVE) {
      (_f = __privateGet(this, _mediaStore)) == null ? void 0 : _f.dispatch({
        type: "optionschangerequest",
        detail: {
          noAutoSeekToLive: this.hasAttribute(Attributes.NO_AUTO_SEEK_TO_LIVE)
        }
      });
    } else if (attrName === Attributes.FULLSCREEN_ELEMENT) {
      const el = newValue ? (_g = this.getRootNode()) == null ? void 0 : _g.getElementById(newValue) : void 0;
      __privateSet(this, _fullscreenElement, el);
      (_h = __privateGet(this, _mediaStore)) == null ? void 0 : _h.dispatch({
        type: "fullscreenelementchangerequest",
        detail: this.fullscreenElement
      });
    } else if (attrName === Attributes.LANG && newValue !== oldValue) {
      (0, import_i18n.setLanguage)(newValue);
    }
  }
  connectedCallback() {
    var _a, _b;
    if (!__privateGet(this, _mediaStore) && !this.hasAttribute(Attributes.NO_DEFAULT_STORE)) {
      __privateMethod(this, _setupDefaultStore, setupDefaultStore_fn).call(this);
    }
    (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch({
      type: "documentelementchangerequest",
      detail: import_server_safe_globals.document
    });
    super.connectedCallback();
    if (__privateGet(this, _mediaStore) && !__privateGet(this, _mediaStoreUnsubscribe)) {
      __privateSet(this, _mediaStoreUnsubscribe, (_b = __privateGet(this, _mediaStore)) == null ? void 0 : _b.subscribe(
        __privateGet(this, _mediaStateCallback)
      ));
    }
    this.enableHotkeys();
  }
  disconnectedCallback() {
    var _a, _b, _c, _d;
    (_a = super.disconnectedCallback) == null ? void 0 : _a.call(this);
    if (__privateGet(this, _mediaStore)) {
      (_b = __privateGet(this, _mediaStore)) == null ? void 0 : _b.dispatch({
        type: "documentelementchangerequest",
        detail: void 0
      });
      (_c = __privateGet(this, _mediaStore)) == null ? void 0 : _c.dispatch({
        type: import_constants.MediaUIEvents.MEDIA_TOGGLE_SUBTITLES_REQUEST,
        detail: false
      });
    }
    if (__privateGet(this, _mediaStoreUnsubscribe)) {
      (_d = __privateGet(this, _mediaStoreUnsubscribe)) == null ? void 0 : _d.call(this);
      __privateSet(this, _mediaStoreUnsubscribe, void 0);
    }
  }
  /**
   * @override
   * @param {HTMLMediaElement} media
   */
  mediaSetCallback(media) {
    var _a;
    super.mediaSetCallback(media);
    (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch({
      type: "mediaelementchangerequest",
      detail: media
    });
    if (!media.hasAttribute("tabindex")) {
      media.tabIndex = -1;
    }
  }
  /**
   * @override
   * @param {HTMLMediaElement} media
   */
  mediaUnsetCallback(media) {
    var _a;
    super.mediaUnsetCallback(media);
    (_a = __privateGet(this, _mediaStore)) == null ? void 0 : _a.dispatch({
      type: "mediaelementchangerequest",
      detail: void 0
    });
  }
  propagateMediaState(stateName, state) {
    propagateMediaState(this.mediaStateReceivers, stateName, state);
  }
  associateElement(element) {
    if (!element)
      return;
    const { associatedElementSubscriptions } = this;
    if (associatedElementSubscriptions.has(element))
      return;
    const registerMediaStateReceiver = this.registerMediaStateReceiver.bind(this);
    const unregisterMediaStateReceiver = this.unregisterMediaStateReceiver.bind(this);
    const unsubscribe = monitorForMediaStateReceivers(
      element,
      registerMediaStateReceiver,
      unregisterMediaStateReceiver
    );
    Object.values(import_constants.MediaUIEvents).forEach((eventName) => {
      element.addEventListener(eventName, __privateGet(this, _mediaStateEventHandler));
    });
    associatedElementSubscriptions.set(element, unsubscribe);
  }
  unassociateElement(element) {
    if (!element)
      return;
    const { associatedElementSubscriptions } = this;
    if (!associatedElementSubscriptions.has(element))
      return;
    const unsubscribe = associatedElementSubscriptions.get(element);
    unsubscribe();
    associatedElementSubscriptions.delete(element);
    Object.values(import_constants.MediaUIEvents).forEach((eventName) => {
      element.removeEventListener(eventName, __privateGet(this, _mediaStateEventHandler));
    });
  }
  registerMediaStateReceiver(el) {
    if (!el)
      return;
    const els = this.mediaStateReceivers;
    const index = els.indexOf(el);
    if (index > -1)
      return;
    els.push(el);
    if (__privateGet(this, _mediaStore)) {
      Object.entries(__privateGet(this, _mediaStore).getState()).forEach(
        ([stateName, stateValue]) => {
          propagateMediaState([el], stateName, stateValue);
        }
      );
    }
  }
  unregisterMediaStateReceiver(el) {
    const els = this.mediaStateReceivers;
    const index = els.indexOf(el);
    if (index < 0)
      return;
    els.splice(index, 1);
  }
  enableHotkeys() {
    this.addEventListener("keydown", __privateMethod(this, _keyDownHandler, keyDownHandler_fn));
  }
  disableHotkeys() {
    this.removeEventListener("keydown", __privateMethod(this, _keyDownHandler, keyDownHandler_fn));
    this.removeEventListener("keyup", __privateMethod(this, _keyUpHandler, keyUpHandler_fn));
  }
  get hotkeys() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.HOTKEYS);
  }
  set hotkeys(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.HOTKEYS, value);
  }
  keyboardShortcutHandler(e) {
    var _a, _b, _c, _d, _e;
    const target = e.target;
    const keysUsed = ((_c = (_b = (_a = target.getAttribute(Attributes.KEYS_USED)) == null ? void 0 : _a.split(" ")) != null ? _b : target == null ? void 0 : target.keysUsed) != null ? _c : []).map((key) => key === "Space" ? " " : key).filter(Boolean);
    if (keysUsed.includes(e.key)) {
      return;
    }
    let eventName, detail, evt;
    if (__privateGet(this, _hotKeys).contains(`no${e.key.toLowerCase()}`))
      return;
    if (e.key === " " && __privateGet(this, _hotKeys).contains(`nospace`))
      return;
    switch (e.key) {
      case " ":
      case "k":
        eventName = __privateGet(this, _mediaStore).getState().mediaPaused ? import_constants.MediaUIEvents.MEDIA_PLAY_REQUEST : import_constants.MediaUIEvents.MEDIA_PAUSE_REQUEST;
        this.dispatchEvent(
          new import_server_safe_globals.globalThis.CustomEvent(eventName, {
            composed: true,
            bubbles: true
          })
        );
        break;
      case "m":
        eventName = this.mediaStore.getState().mediaVolumeLevel === "off" ? import_constants.MediaUIEvents.MEDIA_UNMUTE_REQUEST : import_constants.MediaUIEvents.MEDIA_MUTE_REQUEST;
        this.dispatchEvent(
          new import_server_safe_globals.globalThis.CustomEvent(eventName, {
            composed: true,
            bubbles: true
          })
        );
        break;
      case "f":
        eventName = this.mediaStore.getState().mediaIsFullscreen ? import_constants.MediaUIEvents.MEDIA_EXIT_FULLSCREEN_REQUEST : import_constants.MediaUIEvents.MEDIA_ENTER_FULLSCREEN_REQUEST;
        this.dispatchEvent(
          new import_server_safe_globals.globalThis.CustomEvent(eventName, {
            composed: true,
            bubbles: true
          })
        );
        break;
      case "c":
        this.dispatchEvent(
          new import_server_safe_globals.globalThis.CustomEvent(
            import_constants.MediaUIEvents.MEDIA_TOGGLE_SUBTITLES_REQUEST,
            { composed: true, bubbles: true }
          )
        );
        break;
      case "ArrowLeft": {
        const offsetValue = this.hasAttribute(
          Attributes.KEYBOARD_BACKWARD_SEEK_OFFSET
        ) ? +this.getAttribute(Attributes.KEYBOARD_BACKWARD_SEEK_OFFSET) : DEFAULT_SEEK_OFFSET;
        detail = Math.max(
          ((_d = this.mediaStore.getState().mediaCurrentTime) != null ? _d : 0) - offsetValue,
          0
        );
        evt = new import_server_safe_globals.globalThis.CustomEvent(import_constants.MediaUIEvents.MEDIA_SEEK_REQUEST, {
          composed: true,
          bubbles: true,
          detail
        });
        this.dispatchEvent(evt);
        break;
      }
      case "ArrowRight": {
        const offsetValue = this.hasAttribute(
          Attributes.KEYBOARD_FORWARD_SEEK_OFFSET
        ) ? +this.getAttribute(Attributes.KEYBOARD_FORWARD_SEEK_OFFSET) : DEFAULT_SEEK_OFFSET;
        detail = Math.max(
          ((_e = this.mediaStore.getState().mediaCurrentTime) != null ? _e : 0) + offsetValue,
          0
        );
        evt = new import_server_safe_globals.globalThis.CustomEvent(import_constants.MediaUIEvents.MEDIA_SEEK_REQUEST, {
          composed: true,
          bubbles: true,
          detail
        });
        this.dispatchEvent(evt);
        break;
      }
      default:
        break;
    }
  }
}
_hotKeys = new WeakMap();
_fullscreenElement = new WeakMap();
_mediaStore = new WeakMap();
_mediaStateCallback = new WeakMap();
_mediaStoreUnsubscribe = new WeakMap();
_mediaStateEventHandler = new WeakMap();
_setupDefaultStore = new WeakSet();
setupDefaultStore_fn = function() {
  var _a;
  this.mediaStore = (0, import_media_store.createMediaStore)({
    media: this.media,
    fullscreenElement: this.fullscreenElement,
    options: {
      defaultSubtitles: this.hasAttribute(Attributes.DEFAULT_SUBTITLES),
      defaultDuration: this.hasAttribute(Attributes.DEFAULT_DURATION) ? +this.getAttribute(Attributes.DEFAULT_DURATION) : void 0,
      defaultStreamType: (
        /** @type {import('./media-store/state-mediator.js').StreamTypeValue} */
        (_a = this.getAttribute(
          Attributes.DEFAULT_STREAM_TYPE
        )) != null ? _a : void 0
      ),
      liveEdgeOffset: this.hasAttribute(Attributes.LIVE_EDGE_OFFSET) ? +this.getAttribute(Attributes.LIVE_EDGE_OFFSET) : void 0,
      seekToLiveOffset: this.hasAttribute(Attributes.SEEK_TO_LIVE_OFFSET) ? +this.getAttribute(Attributes.SEEK_TO_LIVE_OFFSET) : this.hasAttribute(Attributes.LIVE_EDGE_OFFSET) ? +this.getAttribute(Attributes.LIVE_EDGE_OFFSET) : void 0,
      noAutoSeekToLive: this.hasAttribute(Attributes.NO_AUTO_SEEK_TO_LIVE),
      // NOTE: This wasn't updated if it was changed later. Should it be? (CJP)
      noVolumePref: this.hasAttribute(Attributes.NO_VOLUME_PREF),
      noSubtitlesLangPref: this.hasAttribute(
        Attributes.NO_SUBTITLES_LANG_PREF
      )
    }
  });
};
_keyUpHandler = new WeakSet();
keyUpHandler_fn = function(e) {
  const { key } = e;
  if (!ButtonPressedKeys.includes(key)) {
    this.removeEventListener("keyup", __privateMethod(this, _keyUpHandler, keyUpHandler_fn));
    return;
  }
  this.keyboardShortcutHandler(e);
};
_keyDownHandler = new WeakSet();
keyDownHandler_fn = function(e) {
  const { metaKey, altKey, key } = e;
  if (metaKey || altKey || !ButtonPressedKeys.includes(key)) {
    this.removeEventListener("keyup", __privateMethod(this, _keyUpHandler, keyUpHandler_fn));
    return;
  }
  if ([" ", "ArrowLeft", "ArrowRight"].includes(key) && !(__privateGet(this, _hotKeys).contains(`no${key.toLowerCase()}`) || key === " " && __privateGet(this, _hotKeys).contains("nospace"))) {
    e.preventDefault();
  }
  this.addEventListener("keyup", __privateMethod(this, _keyUpHandler, keyUpHandler_fn), { once: true });
};
const MEDIA_UI_ATTRIBUTE_NAMES = Object.values(import_constants.MediaUIAttributes);
const MEDIA_UI_PROP_NAMES = Object.values(import_constants.MediaUIProps);
const getMediaUIAttributesFrom = (child) => {
  var _a, _b, _c, _d;
  let { observedAttributes } = child.constructor;
  if (!observedAttributes && ((_a = child.nodeName) == null ? void 0 : _a.includes("-"))) {
    import_server_safe_globals.globalThis.customElements.upgrade(child);
    ({ observedAttributes } = child.constructor);
  }
  const mediaChromeAttributesList = (_d = (_c = (_b = child == null ? void 0 : child.getAttribute) == null ? void 0 : _b.call(child, import_constants.MediaStateReceiverAttributes.MEDIA_CHROME_ATTRIBUTES)) == null ? void 0 : _c.split) == null ? void 0 : _d.call(_c, /\s+/);
  if (!Array.isArray(observedAttributes || mediaChromeAttributesList))
    return [];
  return (observedAttributes || mediaChromeAttributesList).filter(
    (attrName) => MEDIA_UI_ATTRIBUTE_NAMES.includes(attrName)
  );
};
const hasMediaUIProps = (mediaStateReceiverCandidate) => {
  var _a, _b;
  if (((_a = mediaStateReceiverCandidate.nodeName) == null ? void 0 : _a.includes("-")) && !!import_server_safe_globals.globalThis.customElements.get(
    (_b = mediaStateReceiverCandidate.nodeName) == null ? void 0 : _b.toLowerCase()
  ) && !(mediaStateReceiverCandidate instanceof import_server_safe_globals.globalThis.customElements.get(
    mediaStateReceiverCandidate.nodeName.toLowerCase()
  ))) {
    import_server_safe_globals.globalThis.customElements.upgrade(mediaStateReceiverCandidate);
  }
  return MEDIA_UI_PROP_NAMES.some(
    (propName) => propName in mediaStateReceiverCandidate
  );
};
const isMediaStateReceiver = (child) => {
  return hasMediaUIProps(child) || !!getMediaUIAttributesFrom(child).length;
};
const serializeTuple = (tuple) => {
  var _a;
  return (_a = tuple == null ? void 0 : tuple.join) == null ? void 0 : _a.call(tuple, ":");
};
const CustomAttrSerializer = {
  [import_constants.MediaUIAttributes.MEDIA_SUBTITLES_LIST]: import_captions.stringifyTextTrackList,
  [import_constants.MediaUIAttributes.MEDIA_SUBTITLES_SHOWING]: import_captions.stringifyTextTrackList,
  [import_constants.MediaUIAttributes.MEDIA_SEEKABLE]: serializeTuple,
  [import_constants.MediaUIAttributes.MEDIA_BUFFERED]: (tuples) => tuples == null ? void 0 : tuples.map(serializeTuple).join(" "),
  [import_constants.MediaUIAttributes.MEDIA_PREVIEW_COORDS]: (coords) => coords == null ? void 0 : coords.join(" "),
  [import_constants.MediaUIAttributes.MEDIA_RENDITION_LIST]: import_utils.stringifyRenditionList,
  [import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST]: import_utils.stringifyAudioTrackList
};
const setAttr = async (child, attrName, attrValue) => {
  var _a, _b;
  if (!child.isConnected) {
    await (0, import_utils.delay)(0);
  }
  if (typeof attrValue === "boolean" || attrValue == null) {
    return (0, import_element_utils.setBooleanAttr)(child, attrName, attrValue);
  }
  if (typeof attrValue === "number") {
    return (0, import_element_utils.setNumericAttr)(child, attrName, attrValue);
  }
  if (typeof attrValue === "string") {
    return (0, import_element_utils.setStringAttr)(child, attrName, attrValue);
  }
  if (Array.isArray(attrValue) && !attrValue.length) {
    return child.removeAttribute(attrName);
  }
  const val = (_b = (_a = CustomAttrSerializer[attrName]) == null ? void 0 : _a.call(CustomAttrSerializer, attrValue)) != null ? _b : attrValue;
  return child.setAttribute(attrName, val);
};
const isMediaSlotElementDescendant = (el) => {
  var _a;
  return !!((_a = el.closest) == null ? void 0 : _a.call(el, '*[slot="media"]'));
};
const traverseForMediaStateReceivers = (rootNode, mediaStateReceiverCallback) => {
  if (isMediaSlotElementDescendant(rootNode)) {
    return;
  }
  const traverseForMediaStateReceiversSync = (rootNode2, mediaStateReceiverCallback2) => {
    var _a, _b;
    if (isMediaStateReceiver(rootNode2)) {
      mediaStateReceiverCallback2(rootNode2);
    }
    const { children = [] } = rootNode2 != null ? rootNode2 : {};
    const shadowChildren = (_b = (_a = rootNode2 == null ? void 0 : rootNode2.shadowRoot) == null ? void 0 : _a.children) != null ? _b : [];
    const allChildren = [...children, ...shadowChildren];
    allChildren.forEach(
      (child) => traverseForMediaStateReceivers(
        child,
        mediaStateReceiverCallback2
      )
    );
  };
  const name = rootNode == null ? void 0 : rootNode.nodeName.toLowerCase();
  if (name.includes("-") && !isMediaStateReceiver(rootNode)) {
    import_server_safe_globals.globalThis.customElements.whenDefined(name).then(() => {
      traverseForMediaStateReceiversSync(rootNode, mediaStateReceiverCallback);
    });
    return;
  }
  traverseForMediaStateReceiversSync(rootNode, mediaStateReceiverCallback);
};
const propagateMediaState = (els, stateName, val) => {
  els.forEach((el) => {
    if (stateName in el) {
      el[stateName] = val;
      return;
    }
    const relevantAttrs = getMediaUIAttributesFrom(el);
    const attrName = stateName.toLowerCase();
    if (!relevantAttrs.includes(attrName))
      return;
    setAttr(el, attrName, val);
  });
};
const monitorForMediaStateReceivers = (rootNode, registerMediaStateReceiver, unregisterMediaStateReceiver) => {
  traverseForMediaStateReceivers(rootNode, registerMediaStateReceiver);
  const registerMediaStateReceiverHandler = (evt) => {
    var _a;
    const el = (_a = evt == null ? void 0 : evt.composedPath()[0]) != null ? _a : evt.target;
    registerMediaStateReceiver(el);
  };
  const unregisterMediaStateReceiverHandler = (evt) => {
    var _a;
    const el = (_a = evt == null ? void 0 : evt.composedPath()[0]) != null ? _a : evt.target;
    unregisterMediaStateReceiver(el);
  };
  rootNode.addEventListener(
    import_constants.MediaUIEvents.REGISTER_MEDIA_STATE_RECEIVER,
    registerMediaStateReceiverHandler
  );
  rootNode.addEventListener(
    import_constants.MediaUIEvents.UNREGISTER_MEDIA_STATE_RECEIVER,
    unregisterMediaStateReceiverHandler
  );
  const mutationCallback = (mutationsList) => {
    mutationsList.forEach((mutationRecord) => {
      const {
        addedNodes = [],
        removedNodes = [],
        type,
        target,
        attributeName
      } = mutationRecord;
      if (type === "childList") {
        Array.prototype.forEach.call(
          addedNodes,
          (node) => traverseForMediaStateReceivers(
            node,
            registerMediaStateReceiver
          )
        );
        Array.prototype.forEach.call(
          removedNodes,
          (node) => traverseForMediaStateReceivers(
            node,
            unregisterMediaStateReceiver
          )
        );
      } else if (type === "attributes" && attributeName === import_constants.MediaStateReceiverAttributes.MEDIA_CHROME_ATTRIBUTES) {
        if (isMediaStateReceiver(target)) {
          registerMediaStateReceiver(target);
        } else {
          unregisterMediaStateReceiver(target);
        }
      }
    });
  };
  let prevSlotted = [];
  const slotChangeHandler = (event) => {
    const slotEl = event.target;
    if (slotEl.name === "media")
      return;
    prevSlotted.forEach(
      (node) => traverseForMediaStateReceivers(node, unregisterMediaStateReceiver)
    );
    prevSlotted = [
      ...slotEl.assignedElements({ flatten: true })
    ];
    prevSlotted.forEach(
      (node) => traverseForMediaStateReceivers(node, registerMediaStateReceiver)
    );
  };
  rootNode.addEventListener("slotchange", slotChangeHandler);
  const observer = new MutationObserver(mutationCallback);
  observer.observe(rootNode, {
    childList: true,
    attributes: true,
    subtree: true
  });
  const unsubscribe = () => {
    traverseForMediaStateReceivers(rootNode, unregisterMediaStateReceiver);
    rootNode.removeEventListener("slotchange", slotChangeHandler);
    observer.disconnect();
    rootNode.removeEventListener(
      import_constants.MediaUIEvents.REGISTER_MEDIA_STATE_RECEIVER,
      registerMediaStateReceiverHandler
    );
    rootNode.removeEventListener(
      import_constants.MediaUIEvents.UNREGISTER_MEDIA_STATE_RECEIVER,
      unregisterMediaStateReceiverHandler
    );
  };
  return unsubscribe;
};
if (!import_server_safe_globals.globalThis.customElements.get("media-controller")) {
  import_server_safe_globals.globalThis.customElements.define("media-controller", MediaController);
}
var media_controller_default = MediaController;
