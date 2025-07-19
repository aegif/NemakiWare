import { VideoTrack } from "./video-track.js";
import { VideoTrackList, addVideoTrack, removeVideoTrack } from "./video-track-list.js";
import { AudioTrack } from "./audio-track.js";
import { AudioTrackList, addAudioTrack, removeAudioTrack } from "./audio-track-list.js";
import { VideoRenditionList } from "./video-rendition-list.js";
import { AudioRenditionList } from "./audio-rendition-list.js";
import { getPrivate } from "./utils.js";
const nativeVideoTracksFn = getBaseMediaTracksFn(globalThis.HTMLMediaElement, "video");
const nativeAudioTracksFn = getBaseMediaTracksFn(globalThis.HTMLMediaElement, "audio");
function MediaTracksMixin(MediaElementClass) {
  if (!MediaElementClass?.prototype) return MediaElementClass;
  const videoTracksFn = getBaseMediaTracksFn(MediaElementClass, "video");
  if (!videoTracksFn || `${videoTracksFn}`.includes("[native code]")) {
    Object.defineProperty(MediaElementClass.prototype, "videoTracks", {
      get() {
        return getVideoTracks(this);
      }
    });
  }
  const audioTracksFn = getBaseMediaTracksFn(MediaElementClass, "audio");
  if (!audioTracksFn || `${audioTracksFn}`.includes("[native code]")) {
    Object.defineProperty(MediaElementClass.prototype, "audioTracks", {
      get() {
        return getAudioTracks(this);
      }
    });
  }
  if (!("addVideoTrack" in MediaElementClass.prototype)) {
    MediaElementClass.prototype.addVideoTrack = function(kind, label = "", language = "") {
      const track = new VideoTrack();
      track.kind = kind;
      track.label = label;
      track.language = language;
      addVideoTrack(this, track);
      return track;
    };
  }
  if (!("removeVideoTrack" in MediaElementClass.prototype)) {
    MediaElementClass.prototype.removeVideoTrack = removeVideoTrack;
  }
  if (!("addAudioTrack" in MediaElementClass.prototype)) {
    MediaElementClass.prototype.addAudioTrack = function(kind, label = "", language = "") {
      const track = new AudioTrack();
      track.kind = kind;
      track.label = label;
      track.language = language;
      addAudioTrack(this, track);
      return track;
    };
  }
  if (!("removeAudioTrack" in MediaElementClass.prototype)) {
    MediaElementClass.prototype.removeAudioTrack = removeAudioTrack;
  }
  if (!("videoRenditions" in MediaElementClass.prototype)) {
    Object.defineProperty(MediaElementClass.prototype, "videoRenditions", {
      get() {
        return initVideoRenditions(this);
      }
    });
  }
  const initVideoRenditions = (media) => {
    let renditions = getPrivate(media).videoRenditions;
    if (!renditions) {
      renditions = new VideoRenditionList();
      getPrivate(renditions).media = media;
      getPrivate(media).videoRenditions = renditions;
    }
    return renditions;
  };
  if (!("audioRenditions" in MediaElementClass.prototype)) {
    Object.defineProperty(MediaElementClass.prototype, "audioRenditions", {
      get() {
        return initAudioRenditions(this);
      }
    });
  }
  const initAudioRenditions = (media) => {
    let renditions = getPrivate(media).audioRenditions;
    if (!renditions) {
      renditions = new AudioRenditionList();
      getPrivate(renditions).media = media;
      getPrivate(media).audioRenditions = renditions;
    }
    return renditions;
  };
  return MediaElementClass;
}
function getBaseMediaTracksFn(MediaElementClass, type) {
  if (MediaElementClass?.prototype) {
    return Object.getOwnPropertyDescriptor(MediaElementClass.prototype, `${type}Tracks`)?.get;
  }
}
function getVideoTracks(media) {
  let tracks = getPrivate(media).videoTracks;
  if (!tracks) {
    tracks = new VideoTrackList();
    getPrivate(media).videoTracks = tracks;
    if (nativeVideoTracksFn) {
      const nativeTracks = nativeVideoTracksFn.call(media.nativeEl ?? media);
      for (const nativeTrack of nativeTracks) {
        addVideoTrack(media, nativeTrack);
      }
      nativeTracks.addEventListener("change", () => {
        tracks.dispatchEvent(new Event("change"));
      });
      nativeTracks.addEventListener("addtrack", (event) => {
        if ([...tracks].some((t) => t instanceof VideoTrack)) {
          for (const nativeTrack of nativeTracks) {
            removeVideoTrack(nativeTrack);
          }
          return;
        }
        addVideoTrack(media, event.track);
      });
      nativeTracks.addEventListener("removetrack", (event) => {
        removeVideoTrack(event.track);
      });
    }
  }
  return tracks;
}
function getAudioTracks(media) {
  let tracks = getPrivate(media).audioTracks;
  if (!tracks) {
    tracks = new AudioTrackList();
    getPrivate(media).audioTracks = tracks;
    if (nativeAudioTracksFn) {
      const nativeTracks = nativeAudioTracksFn.call(media.nativeEl ?? media);
      for (const nativeTrack of nativeTracks) {
        addAudioTrack(media, nativeTrack);
      }
      nativeTracks.addEventListener("change", () => {
        tracks.dispatchEvent(new Event("change"));
      });
      nativeTracks.addEventListener("addtrack", (event) => {
        if ([...tracks].some((t) => t instanceof AudioTrack)) {
          for (const nativeTrack of nativeTracks) {
            removeAudioTrack(nativeTrack);
          }
          return;
        }
        addAudioTrack(media, event.track);
      });
      nativeTracks.addEventListener("removetrack", (event) => {
        removeAudioTrack(event.track);
      });
    }
  }
  return tracks;
}
export {
  MediaTracksMixin
};
