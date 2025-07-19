import { selectedChanged } from "./audio-rendition-list.js";
class AudioRendition {
  src;
  id;
  bitrate;
  codec;
  #selected = false;
  get selected() {
    return this.#selected;
  }
  set selected(val) {
    if (this.#selected === val) return;
    this.#selected = val;
    selectedChanged(this);
  }
}
export {
  AudioRendition
};
