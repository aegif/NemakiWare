import { RenditionEvent } from "./rendition-event.js";
import { getPrivate } from "./utils.js";
function addRendition(track, rendition) {
  const renditionList = getPrivate(track).media.audioRenditions;
  getPrivate(rendition).media = getPrivate(track).media;
  getPrivate(rendition).track = track;
  const renditionSet = getPrivate(track).renditionSet;
  renditionSet.add(rendition);
  const index = renditionSet.size - 1;
  if (!(index in AudioRenditionList.prototype)) {
    Object.defineProperty(AudioRenditionList.prototype, index, {
      get() {
        return getCurrentRenditions(this)[index];
      }
    });
  }
  queueMicrotask(() => {
    if (!track.enabled) return;
    renditionList.dispatchEvent(new RenditionEvent("addrendition", { rendition }));
  });
}
function removeRendition(rendition) {
  const renditionList = getPrivate(rendition).media.audioRenditions;
  const track = getPrivate(rendition).track;
  const renditionSet = getPrivate(track).renditionSet;
  renditionSet.delete(rendition);
  queueMicrotask(() => {
    const track2 = getPrivate(rendition).track;
    if (!track2.enabled) return;
    renditionList.dispatchEvent(new RenditionEvent("removerendition", { rendition }));
  });
}
function selectedChanged(rendition) {
  const renditionList = getPrivate(rendition).media.audioRenditions;
  if (!renditionList || getPrivate(renditionList).changeRequested) return;
  getPrivate(renditionList).changeRequested = true;
  queueMicrotask(() => {
    delete getPrivate(renditionList).changeRequested;
    const track = getPrivate(rendition).track;
    if (!track.enabled) return;
    renditionList.dispatchEvent(new Event("change"));
  });
}
function getCurrentRenditions(renditionList) {
  const media = getPrivate(renditionList).media;
  return [...media.audioTracks].filter((track) => track.enabled).flatMap((track) => [...getPrivate(track).renditionSet]);
}
class AudioRenditionList extends EventTarget {
  #addRenditionCallback;
  #removeRenditionCallback;
  #changeCallback;
  [Symbol.iterator]() {
    return getCurrentRenditions(this).values();
  }
  get length() {
    return getCurrentRenditions(this).length;
  }
  getRenditionById(id) {
    return getCurrentRenditions(this).find((rendition) => `${rendition.id}` === `${id}`) ?? null;
  }
  get selectedIndex() {
    return getCurrentRenditions(this).findIndex((rendition) => rendition.selected);
  }
  set selectedIndex(index) {
    for (const [i, rendition] of getCurrentRenditions(this).entries()) {
      rendition.selected = i === index;
    }
  }
  get onaddrendition() {
    return this.#addRenditionCallback;
  }
  set onaddrendition(callback) {
    if (this.#addRenditionCallback) {
      this.removeEventListener("addrendition", this.#addRenditionCallback);
      this.#addRenditionCallback = void 0;
    }
    if (typeof callback == "function") {
      this.#addRenditionCallback = callback;
      this.addEventListener("addrendition", callback);
    }
  }
  get onremoverendition() {
    return this.#removeRenditionCallback;
  }
  set onremoverendition(callback) {
    if (this.#removeRenditionCallback) {
      this.removeEventListener("removerendition", this.#removeRenditionCallback);
      this.#removeRenditionCallback = void 0;
    }
    if (typeof callback == "function") {
      this.#removeRenditionCallback = callback;
      this.addEventListener("removerendition", callback);
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
  AudioRenditionList,
  addRendition,
  removeRendition,
  selectedChanged
};
