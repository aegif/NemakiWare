import { TrackEvent } from "./track-event.js";
import { getPrivate } from "./utils.js";
function addVideoTrack(media, track) {
  const trackList = media.videoTracks;
  getPrivate(track).media = media;
  if (!getPrivate(track).renditionSet) {
    getPrivate(track).renditionSet = /* @__PURE__ */ new Set();
  }
  const trackSet = getPrivate(trackList).trackSet;
  trackSet.add(track);
  const index = trackSet.size - 1;
  if (!(index in VideoTrackList.prototype)) {
    Object.defineProperty(VideoTrackList.prototype, index, {
      get() {
        return [...getPrivate(this).trackSet][index];
      }
    });
  }
  queueMicrotask(() => {
    trackList.dispatchEvent(new TrackEvent("addtrack", { track }));
  });
}
function removeVideoTrack(track) {
  const trackList = getPrivate(track).media?.videoTracks;
  if (!trackList) return;
  const trackSet = getPrivate(trackList).trackSet;
  trackSet.delete(track);
  queueMicrotask(() => {
    trackList.dispatchEvent(new TrackEvent("removetrack", { track }));
  });
}
function selectedChanged(selected) {
  const trackList = getPrivate(selected).media.videoTracks ?? [];
  let hasUnselected = false;
  for (const track of trackList) {
    if (track === selected) continue;
    track.selected = false;
    hasUnselected = true;
  }
  if (hasUnselected) {
    if (getPrivate(trackList).changeRequested) return;
    getPrivate(trackList).changeRequested = true;
    queueMicrotask(() => {
      delete getPrivate(trackList).changeRequested;
      trackList.dispatchEvent(new Event("change"));
    });
  }
}
class VideoTrackList extends EventTarget {
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
  get selectedIndex() {
    return [...this.#tracks].findIndex((track) => track.selected);
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
  VideoTrackList,
  addVideoTrack,
  removeVideoTrack,
  selectedChanged
};
