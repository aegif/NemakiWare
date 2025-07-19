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
var media_audio_track_menu_exports = {};
__export(media_audio_track_menu_exports, {
  MediaAudioTrackMenu: () => MediaAudioTrackMenu,
  default: () => media_audio_track_menu_default
});
module.exports = __toCommonJS(media_audio_track_menu_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_constants = require("../constants.js");
var import_utils = require("../utils/utils.js");
var import_media_chrome_menu = require("./media-chrome-menu.js");
var import_element_utils = require("../utils/element-utils.js");
var _audioTrackList, _prevState, _render, render_fn, _onChange, onChange_fn;
class MediaAudioTrackMenu extends import_media_chrome_menu.MediaChromeMenu {
  constructor() {
    super(...arguments);
    __privateAdd(this, _render);
    __privateAdd(this, _onChange);
    __privateAdd(this, _audioTrackList, []);
    __privateAdd(this, _prevState, void 0);
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST,
      import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED,
      import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_UNAVAILABLE
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED && oldValue !== newValue) {
      this.value = newValue;
    } else if (attrName === import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST && oldValue !== newValue) {
      __privateSet(this, _audioTrackList, (0, import_utils.parseAudioTrackList)(newValue != null ? newValue : ""));
      __privateMethod(this, _render, render_fn).call(this);
    }
  }
  connectedCallback() {
    super.connectedCallback();
    this.addEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  /**
   * Returns the anchor element when it is a floating menu.
   */
  get anchorElement() {
    var _a;
    if (this.anchor !== "auto")
      return super.anchorElement;
    return (_a = (0, import_element_utils.getMediaController)(this)) == null ? void 0 : _a.querySelector(
      "media-audio-track-menu-button"
    );
  }
  get mediaAudioTrackList() {
    return __privateGet(this, _audioTrackList);
  }
  set mediaAudioTrackList(list) {
    __privateSet(this, _audioTrackList, list);
    __privateMethod(this, _render, render_fn).call(this);
  }
  /**
   * Get enabled audio track id.
   */
  get mediaAudioTrackEnabled() {
    var _a;
    return (_a = (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED)) != null ? _a : "";
  }
  set mediaAudioTrackEnabled(id) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED, id);
  }
}
_audioTrackList = new WeakMap();
_prevState = new WeakMap();
_render = new WeakSet();
render_fn = function() {
  if (__privateGet(this, _prevState) === JSON.stringify(this.mediaAudioTrackList))
    return;
  __privateSet(this, _prevState, JSON.stringify(this.mediaAudioTrackList));
  const audioTrackList = this.mediaAudioTrackList;
  this.defaultSlot.textContent = "";
  for (const audioTrack of audioTrackList) {
    const text = this.formatMenuItemText(audioTrack.label, audioTrack);
    const item = (0, import_media_chrome_menu.createMenuItem)({
      type: "radio",
      text,
      value: `${audioTrack.id}`,
      checked: audioTrack.enabled
    });
    item.prepend((0, import_media_chrome_menu.createIndicator)(this, "checked-indicator"));
    this.defaultSlot.append(item);
  }
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (this.value == null)
    return;
  const event = new import_server_safe_globals.globalThis.CustomEvent(
    import_constants.MediaUIEvents.MEDIA_AUDIO_TRACK_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!import_server_safe_globals.globalThis.customElements.get("media-audio-track-menu")) {
  import_server_safe_globals.globalThis.customElements.define(
    "media-audio-track-menu",
    MediaAudioTrackMenu
  );
}
var media_audio_track_menu_default = MediaAudioTrackMenu;
