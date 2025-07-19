import { AudioRendition } from "./audio-rendition.js";
import { enabledChanged } from "./audio-track-list.js";
import { addRendition, removeRendition } from "./audio-rendition-list.js";
const AudioTrackKind = {
  alternative: "alternative",
  descriptions: "descriptions",
  main: "main",
  "main-desc": "main-desc",
  translation: "translation",
  commentary: "commentary"
};
class AudioTrack {
  id;
  kind;
  label = "";
  language = "";
  sourceBuffer;
  #enabled = false;
  addRendition(src, codec, bitrate) {
    const rendition = new AudioRendition();
    rendition.src = src;
    rendition.codec = codec;
    rendition.bitrate = bitrate;
    addRendition(this, rendition);
    return rendition;
  }
  removeRendition(rendition) {
    removeRendition(rendition);
  }
  get enabled() {
    return this.#enabled;
  }
  set enabled(val) {
    if (this.#enabled === val) return;
    this.#enabled = val;
    enabledChanged(this);
  }
}
export {
  AudioTrack,
  AudioTrackKind
};
