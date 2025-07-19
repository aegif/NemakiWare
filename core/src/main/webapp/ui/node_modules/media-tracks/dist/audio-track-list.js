import { TrackEvent } from "./track-event.js";
import { getPrivate } from "./utils.js";
function addAudioTrack(media, track) {
  const trackList = media.audioTracks;
  getPrivate(track).media = media;
  if (!getPrivate(track).renditionSet) {
    getPrivate(track).renditionSet = /* @__PURE__ */ new Set();
  }
  const trackSet = getPrivate(trackList).trackSet;
  trackSet.add(track);
  const index = trackSet.size - 1;
  if (!(index in AudioTrackList.prototype)) {
    Object.defineProperty(AudioTrackList.prototype, index, {
      get() {
        return [...getPrivate(this).trackSet][index];
      }
    });
  }
  queueMicrotask(() => {
    trackList.dispatchEvent(new TrackEvent("addtrack", { track }));
  });
}
function removeAudioTrack(track) {
  const trackList = getPrivate(track).media?.audioTracks;
  if (!trackList) return;
  const trackSet = getPrivate(trackList).trackSet;
  trackSet.delete(track);
  queueMicrotask(() => {
    trackList.dispatchEvent(new TrackEvent("removetrack", { track }));
  });
}
function enabledChanged(track) {
  const trackList = getPrivate(track).media.audioTracks;
  if (!trackList || getPrivate(trackList).changeRequested) return;
  getPrivate(trackList).changeRequested = true;
  queueMicrotask(() => {
    delete getPrivate(trackList).changeRequested;
    trackList.dispatchEvent(new Event("change"));
  });
}
class AudioTrackList extends EventTarget {
  #addTrackCallback;
  #removeTrackCallback;
  #changeCallback;
  constructor() {
    super();
    getPrivate(this).trackSet = /* @__PURE__ */ new Set();
  }
  get #tracks() {
    return getPrivate(this).trackSet;
  }
  [Symbol.iterator]() {
    return this.#tracks.values();
  }
  get length() {
    return this.#tracks.size;
  }
  getTrackById(id) {
    return [...this.#tracks].find((track) => track.id === id) ?? null;
  }
  get onaddtrack() {
    return this.#addTrackCallback;
  }
  set onaddtrack(callback) {
    if (this.#addTrackCallback) {
      this.removeEventListener("addtrack", this.#addTrackCallback);
      this.#addTrackCallback = void 0;
    }
    if (typeof callback == "function") {
      this.#addTrackCallback = callback;
      this.addEventListener("addtrack", callback);
    }
  }
  get onremovetrack() {
    return this.#removeTrackCallback;
  }
  set onremovetrack(callback) {
    if (this.#removeTrackCallback) {
      this.removeEventListener("removetrack", this.#removeTrackCallback);
      this.#removeTrackCallback = void 0;
    }
    if (typeof callback == "function") {
      this.#removeTrackCallback = callback;
      this.addEventListener("removetrack", callback);
    }
  }
  get onchange() {
    return this.#changeCallback;
  }
  set onchange(callback) {
    if (this.#changeCallback) {
      this.removeEventListener("change", this.#changeCallback);
      this.#changeCallback = void 0;
    }
    if (typeof callback == "function") {
      this.#changeCallback = callback;
      this.addEventListener("change", callback);
    }
  }
}
export {
  AudioTrackList,
  addAudioTrack,
  enabledChanged,
  removeAudioTrack
};
