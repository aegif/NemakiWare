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
var _audioTrackList, _prevState, _render, render_fn, _onChange, onChange_fn;
import { globalThis } from "../utils/server-safe-globals.js";
import { MediaUIAttributes, MediaUIEvents } from "../constants.js";
import { parseAudioTrackList } from "../utils/utils.js";
import {
  MediaChromeMenu,
  createMenuItem,
  createIndicator
} from "./media-chrome-menu.js";
import {
  getStringAttr,
  setStringAttr,
  getMediaController
} from "../utils/element-utils.js";
class MediaAudioTrackMenu extends MediaChromeMenu {
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
      MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST,
      MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED,
      MediaUIAttributes.MEDIA_AUDIO_TRACK_UNAVAILABLE
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED && oldValue !== newValue) {
      this.value = newValue;
    } else if (attrName === MediaUIAttributes.MEDIA_AUDIO_TRACK_LIST && oldValue !== newValue) {
      __privateSet(this, _audioTrackList, parseAudioTrackList(newValue != null ? newValue : ""));
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
    return (_a = getMediaController(this)) == null ? void 0 : _a.querySelector(
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
    return (_a = getStringAttr(this, MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED)) != null ? _a : "";
  }
  set mediaAudioTrackEnabled(id) {
    setStringAttr(this, MediaUIAttributes.MEDIA_AUDIO_TRACK_ENABLED, id);
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
    const item = createMenuItem({
      type: "radio",
      text,
      value: `${audioTrack.id}`,
      checked: audioTrack.enabled
    });
    item.prepend(createIndicator(this, "checked-indicator"));
    this.defaultSlot.append(item);
  }
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (this.value == null)
    return;
  const event = new globalThis.CustomEvent(
    MediaUIEvents.MEDIA_AUDIO_TRACK_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!globalThis.customElements.get("media-audio-track-menu")) {
  globalThis.customElements.define(
    "media-audio-track-menu",
    MediaAudioTrackMenu
  );
}
var media_audio_track_menu_default = MediaAudioTrackMenu;
export {
  MediaAudioTrackMenu,
  media_audio_track_menu_default as default
};
