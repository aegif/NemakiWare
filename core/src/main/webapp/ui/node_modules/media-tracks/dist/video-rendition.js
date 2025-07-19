import { selectedChanged } from "./video-rendition-list.js";
class VideoRendition {
  src;
  id;
  width;
  height;
  bitrate;
  frameRate;
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
  VideoRendition
};
