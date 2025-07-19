import { globalThis } from "../utils/server-safe-globals.js";
import {
  MediaUIEvents,
  StreamTypes,
  TextTrackKinds,
  TextTrackModes
} from "../constants.js";
import {
  getTextTracksList,
  parseTracks,
  updateTracksModeTo
} from "../utils/captions.js";
import { getSubtitleTracks, toggleSubtitleTracks } from "./util.js";
const requestMap = {
  /**
   * @TODO Consider adding state to `StateMediator` for e.g. `mediaThumbnailCues` and use that for derived state here (CJP)
   */
  [MediaUIEvents.MEDIA_PREVIEW_REQUEST](stateMediator, stateOwners, { detail }) {
    var _a, _b, _c;
    const { media } = stateOwners;
    const mediaPreviewTime = detail != null ? detail : void 0;
    let mediaPreviewImage = void 0;
    let mediaPreviewCoords = void 0;
    if (media && mediaPreviewTime != null) {
      const [track] = getTextTracksList(media, {
        kind: TextTrackKinds.METADATA,
        label: "thumbnails"
      });
      const cue = Array.prototype.find.call((_a = track == null ? void 0 : track.cues) != null ? _a : [], (c, i, cs) => {
        if (i === 0)
          return c.endTime > mediaPreviewTime;
        if (i === cs.length - 1)
          return c.startTime <= mediaPreviewTime;
        return c.startTime <= mediaPreviewTime && c.endTime > mediaPreviewTime;
      });
      if (cue) {
        const base = !/'^(?:[a-z]+:)?\/\//i.test(cue.text) ? (_b = media == null ? void 0 : media.querySelector(
          'track[label="thumbnails"]'
        )) == null ? void 0 : _b.src : void 0;
        const url = new URL(cue.text, base);
        const previewCoordsStr = new URLSearchParams(url.hash).get("#xywh");
        mediaPreviewCoords = previewCoordsStr.split(",").map((numStr) => +numStr);
        mediaPreviewImage = url.href;
      }
    }
    const mediaDuration = stateMediator.mediaDuration.get(stateOwners);
    const mediaChaptersCues = stateMediator.mediaChaptersCues.get(stateOwners);
    let mediaPreviewChapter = (_c = mediaChaptersCues.find((c, i, cs) => {
      if (i === cs.length - 1 && mediaDuration === c.endTime) {
        return c.startTime <= mediaPreviewTime && c.endTime >= mediaPreviewTime;
      }
      return c.startTime <= mediaPreviewTime && c.endTime > mediaPreviewTime;
    })) == null ? void 0 : _c.text;
    if (detail != null && mediaPreviewChapter == null) {
      mediaPreviewChapter = "";
    }
    return {
      mediaPreviewTime,
      mediaPreviewImage,
      mediaPreviewCoords,
      mediaPreviewChapter
    };
  },
  [MediaUIEvents.MEDIA_PAUSE_REQUEST](stateMediator, stateOwners) {
    const key = "mediaPaused";
    const value = true;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_PLAY_REQUEST](stateMediator, stateOwners) {
    var _a, _b, _c, _d;
    const key = "mediaPaused";
    const value = false;
    const isLive = stateMediator.mediaStreamType.get(stateOwners) === StreamTypes.LIVE;
    const canAutoSeekToLive = !((_a = stateOwners.options) == null ? void 0 : _a.noAutoSeekToLive);
    const isDVR = stateMediator.mediaTargetLiveWindow.get(stateOwners) > 0;
    if (isLive && canAutoSeekToLive && !isDVR) {
      const seekableEnd = (_b = stateMediator.mediaSeekable.get(stateOwners)) == null ? void 0 : _b[1];
      if (seekableEnd) {
        const seekToLiveOffset = (_d = (_c = stateOwners.options) == null ? void 0 : _c.seekToLiveOffset) != null ? _d : 0;
        const liveEdgeTime = seekableEnd - seekToLiveOffset;
        stateMediator.mediaCurrentTime.set(liveEdgeTime, stateOwners);
      }
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_PLAYBACK_RATE_REQUEST](stateMediator, stateOwners, { detail }) {
    const key = "mediaPlaybackRate";
    const value = detail;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_MUTE_REQUEST](stateMediator, stateOwners) {
    const key = "mediaMuted";
    const value = true;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_UNMUTE_REQUEST](stateMediator, stateOwners) {
    const key = "mediaMuted";
    const value = false;
    if (!stateMediator.mediaVolume.get(stateOwners)) {
      stateMediator.mediaVolume.set(0.25, stateOwners);
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_VOLUME_REQUEST](stateMediator, stateOwners, { detail }) {
    const key = "mediaVolume";
    const value = detail;
    if (value && stateMediator.mediaMuted.get(stateOwners)) {
      stateMediator.mediaMuted.set(false, stateOwners);
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_SEEK_REQUEST](stateMediator, stateOwners, { detail }) {
    const key = "mediaCurrentTime";
    const value = detail;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_SEEK_TO_LIVE_REQUEST](stateMediator, stateOwners) {
    var _a, _b, _c;
    const key = "mediaCurrentTime";
    const seekableEnd = (_a = stateMediator.mediaSeekable.get(stateOwners)) == null ? void 0 : _a[1];
    if (Number.isNaN(Number(seekableEnd)))
      return;
    const seekToLiveOffset = (_c = (_b = stateOwners.options) == null ? void 0 : _b.seekToLiveOffset) != null ? _c : 0;
    const value = seekableEnd - seekToLiveOffset;
    stateMediator[key].set(value, stateOwners);
  },
  // Text Tracks state change requests
  [MediaUIEvents.MEDIA_SHOW_SUBTITLES_REQUEST](_stateMediator, stateOwners, { detail }) {
    var _a;
    const { options } = stateOwners;
    const tracks = getSubtitleTracks(stateOwners);
    const tracksToUpdate = parseTracks(detail);
    const preferredLanguage = (_a = tracksToUpdate[0]) == null ? void 0 : _a.language;
    if (preferredLanguage && !options.noSubtitlesLangPref) {
      globalThis.localStorage.setItem(
        "media-chrome-pref-subtitles-lang",
        preferredLanguage
      );
    }
    updateTracksModeTo(TextTrackModes.SHOWING, tracks, tracksToUpdate);
  },
  [MediaUIEvents.MEDIA_DISABLE_SUBTITLES_REQUEST](_stateMediator, stateOwners, { detail }) {
    const tracks = getSubtitleTracks(stateOwners);
    const tracksToUpdate = detail != null ? detail : [];
    updateTracksModeTo(TextTrackModes.DISABLED, tracks, tracksToUpdate);
  },
  [MediaUIEvents.MEDIA_TOGGLE_SUBTITLES_REQUEST](_stateMediator, stateOwners, { detail }) {
    toggleSubtitleTracks(stateOwners, detail);
  },
  // Renditions/Tracks state change requests
  [MediaUIEvents.MEDIA_RENDITION_REQUEST](stateMediator, stateOwners, { detail }) {
    const key = "mediaRenditionSelected";
    const value = detail;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_AUDIO_TRACK_REQUEST](stateMediator, stateOwners, { detail }) {
    const key = "mediaAudioTrackEnabled";
    const value = detail;
    stateMediator[key].set(value, stateOwners);
  },
  // State change requests dependent on root node
  [MediaUIEvents.MEDIA_ENTER_PIP_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsPip";
    const value = true;
    if (stateMediator.mediaIsFullscreen.get(stateOwners)) {
      stateMediator.mediaIsFullscreen.set(false, stateOwners);
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_EXIT_PIP_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsPip";
    const value = false;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_ENTER_FULLSCREEN_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsFullscreen";
    const value = true;
    if (stateMediator.mediaIsPip.get(stateOwners)) {
      stateMediator.mediaIsPip.set(false, stateOwners);
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_EXIT_FULLSCREEN_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsFullscreen";
    const value = false;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_ENTER_CAST_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsCasting";
    const value = true;
    if (stateMediator.mediaIsFullscreen.get(stateOwners)) {
      stateMediator.mediaIsFullscreen.set(false, stateOwners);
    }
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_EXIT_CAST_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsCasting";
    const value = false;
    stateMediator[key].set(value, stateOwners);
  },
  [MediaUIEvents.MEDIA_AIRPLAY_REQUEST](stateMediator, stateOwners) {
    const key = "mediaIsAirplaying";
    const value = true;
    stateMediator[key].set(value, stateOwners);
  }
};
export {
  requestMap
};
