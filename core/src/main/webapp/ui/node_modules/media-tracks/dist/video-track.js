import { selectedChanged } from "./video-track-list.js";
import { VideoRendition } from "./video-rendition.js";
import { addRendition, removeRendition } from "./video-rendition-list.js";
const VideoTrackKind = {
  alternative: "alternative",
  captions: "captions",
  main: "main",
  sign: "sign",
  subtitles: "subtitles",
  commentary: "commentary"
};
class VideoTrack {
  id;
  kind;
  label = "";
  language = "";
  sourceBuffer;
  #selected = false;
  addRendition(src, width, height, codec, bitrate, frameRate) {
    const rendition = new VideoRendition();
    rendition.src = src;
    rendition.width = width;
    rendition.height = height;
    rendition.frameRate = frameRate;
    rendition.bitrate = bitrate;
    rendition.codec = codec;
    addRendition(this, rendition);
    return rendition;
  }
  removeRendition(rendition) {
    removeRendition(rendition);
  }
  get selected() {
    return this.#selected;
  }
  set selected(val) {
    if (this.#selected === val) return;
    this.#selected = val;
    if (val !== true) return;
    selectedChanged(this);
  }
}
export {
  VideoTrack,
  VideoTrackKind
};
