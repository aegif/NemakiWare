var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var state_mediator_exports = {};
__export(state_mediator_exports, {
  prepareStateOwners: () => prepareStateOwners,
  stateMediator: () => stateMediator,
  volumeSupportPromise: () => volumeSupportPromise
});
module.exports = __toCommonJS(state_mediator_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_constants = require("../constants.js");
var import_element_utils = require("../utils/element-utils.js");
var import_fullscreen_api = require("../utils/fullscreen-api.js");
var import_platform_tests = require("../utils/platform-tests.js");
var import_util = require("./util.js");
var import_captions = require("../utils/captions.js");
var import_utils = require("../utils/utils.js");
const StreamTypeValues = Object.values(import_constants.StreamTypes);
let volumeSupported;
const volumeSupportPromise = (0, import_platform_tests.hasVolumeSupportAsync)().then((supported) => {
  volumeSupported = supported;
  return volumeSupported;
});
const prepareStateOwners = async (...stateOwners) => {
  await Promise.all(
    stateOwners.filter((x) => x).map(async (stateOwner) => {
      if (!("localName" in stateOwner && stateOwner instanceof import_server_safe_globals.globalThis.HTMLElement)) {
        return;
      }
      const name = stateOwner.localName;
      if (!name.includes("-"))
        return;
      const classDef = import_server_safe_globals.globalThis.customElements.get(name);
      if (classDef && stateOwner instanceof classDef)
        return;
      await import_server_safe_globals.globalThis.customElements.whenDefined(name);
      import_server_safe_globals.globalThis.customElements.upgrade(stateOwner);
    })
  );
};
const domParser = new import_server_safe_globals.globalThis.DOMParser();
const parseHtmlToText = (text) => text ? domParser.parseFromString(text, "text/html").body.textContent || text : text;
const stateMediator = {
  mediaError: {
    get(stateOwners, event) {
      const { media } = stateOwners;
      if ((event == null ? void 0 : event.type) === "playing")
        return;
      return media == null ? void 0 : media.error;
    },
    mediaEvents: ["emptied", "error", "playing"]
  },
  mediaErrorCode: {
    get(stateOwners, event) {
      var _a;
      const { media } = stateOwners;
      if ((event == null ? void 0 : event.type) === "playing")
        return;
      return (_a = media == null ? void 0 : media.error) == null ? void 0 : _a.code;
    },
    mediaEvents: ["emptied", "error", "playing"]
  },
  mediaErrorMessage: {
    get(stateOwners, event) {
      var _a, _b;
      const { media } = stateOwners;
      if ((event == null ? void 0 : event.type) === "playing")
        return;
      return (_b = (_a = media == null ? void 0 : media.error) == null ? void 0 : _a.message) != null ? _b : "";
    },
    mediaEvents: ["emptied", "error", "playing"]
  },
  mediaWidth: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.videoWidth) != null ? _a : 0;
    },
    mediaEvents: ["resize"]
  },
  mediaHeight: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.videoHeight) != null ? _a : 0;
    },
    mediaEvents: ["resize"]
  },
  mediaPaused: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.paused) != null ? _a : true;
    },
    set(value, stateOwners) {
      var _a;
      const { media } = stateOwners;
      if (!media)
        return;
      if (value) {
        media.pause();
      } else {
        (_a = media.play()) == null ? void 0 : _a.catch(() => {
        });
      }
    },
    mediaEvents: ["play", "playing", "pause", "emptied"]
  },
  mediaHasPlayed: {
    // We want to let the user know that the media started playing at any point (`media-has-played`).
    // Since these propagators are all called when boostrapping state, let's verify this is
    // a real playing event by checking that 1) there's media and 2) it isn't currently paused.
    get(stateOwners, event) {
      const { media } = stateOwners;
      if (!media)
        return false;
      if (!event)
        return !media.paused;
      return event.type === "playing";
    },
    mediaEvents: ["playing", "emptied"]
  },
  mediaEnded: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.ended) != null ? _a : false;
    },
    mediaEvents: ["seeked", "ended", "emptied"]
  },
  mediaPlaybackRate: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.playbackRate) != null ? _a : 1;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return;
      if (!Number.isFinite(+value))
        return;
      media.playbackRate = +value;
    },
    mediaEvents: ["ratechange", "loadstart"]
  },
  mediaMuted: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.muted) != null ? _a : false;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return;
      try {
        import_server_safe_globals.globalThis.localStorage.setItem(
          "media-chrome-pref-muted",
          value ? "true" : "false"
        );
      } catch (e) {
        console.debug("Error setting muted pref", e);
      }
      media.muted = value;
    },
    mediaEvents: ["volumechange"],
    stateOwnersUpdateHandlers: [
      (handler, stateOwners) => {
        const {
          options: { noMutedPref }
        } = stateOwners;
        const { media } = stateOwners;
        if (!media || media.muted || noMutedPref)
          return;
        try {
          const mutedPref = import_server_safe_globals.globalThis.localStorage.getItem("media-chrome-pref-muted") === "true";
          stateMediator.mediaMuted.set(mutedPref, stateOwners);
          handler(mutedPref);
        } catch (e) {
          console.debug("Error getting muted pref", e);
        }
      }
    ]
  },
  mediaVolume: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.volume) != null ? _a : 1;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return;
      try {
        if (value == null) {
          import_server_safe_globals.globalThis.localStorage.removeItem("media-chrome-pref-volume");
        } else {
          import_server_safe_globals.globalThis.localStorage.setItem(
            "media-chrome-pref-volume",
            value.toString()
          );
        }
      } catch (e) {
        console.debug("Error setting volume pref", e);
      }
      if (!Number.isFinite(+value))
        return;
      media.volume = +value;
    },
    mediaEvents: ["volumechange"],
    stateOwnersUpdateHandlers: [
      (handler, stateOwners) => {
        const {
          options: { noVolumePref }
        } = stateOwners;
        if (noVolumePref)
          return;
        try {
          const { media } = stateOwners;
          if (!media)
            return;
          const volumePref = import_server_safe_globals.globalThis.localStorage.getItem(
            "media-chrome-pref-volume"
          );
          if (volumePref == null)
            return;
          stateMediator.mediaVolume.set(+volumePref, stateOwners);
          handler(+volumePref);
        } catch (e) {
          console.debug("Error getting volume pref", e);
        }
      }
    ]
  },
  // NOTE: Keeping this roughly equivalent to prior impl to reduce number of changes,
  // however we may want to model "derived" state differently from "primary" state
  // (in this case, derived === mediaVolumeLevel, primary === mediaMuted, mediaVolume) (CJP)
  mediaVolumeLevel: {
    get(stateOwners) {
      const { media } = stateOwners;
      if (typeof (media == null ? void 0 : media.volume) == "undefined")
        return "high";
      if (media.muted || media.volume === 0)
        return "off";
      if (media.volume < 0.5)
        return "low";
      if (media.volume < 0.75)
        return "medium";
      return "high";
    },
    mediaEvents: ["volumechange"]
  },
  mediaCurrentTime: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return (_a = media == null ? void 0 : media.currentTime) != null ? _a : 0;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!media || !(0, import_utils.isValidNumber)(value))
        return;
      media.currentTime = value;
    },
    mediaEvents: ["timeupdate", "loadedmetadata"]
  },
  mediaDuration: {
    get(stateOwners) {
      const { media, options: { defaultDuration } = {} } = stateOwners;
      if (defaultDuration && (!media || !media.duration || Number.isNaN(media.duration) || !Number.isFinite(media.duration))) {
        return defaultDuration;
      }
      return Number.isFinite(media == null ? void 0 : media.duration) ? media.duration : Number.NaN;
    },
    mediaEvents: ["durationchange", "loadedmetadata", "emptied"]
  },
  mediaLoading: {
    get(stateOwners) {
      const { media } = stateOwners;
      return (media == null ? void 0 : media.readyState) < 3;
    },
    mediaEvents: ["waiting", "playing", "emptied"]
  },
  mediaSeekable: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      if (!((_a = media == null ? void 0 : media.seekable) == null ? void 0 : _a.length))
        return void 0;
      const start = media.seekable.start(0);
      const end = media.seekable.end(media.seekable.length - 1);
      if (!start && !end)
        return void 0;
      return [Number(start.toFixed(3)), Number(end.toFixed(3))];
    },
    mediaEvents: ["loadedmetadata", "emptied", "progress", "seekablechange"]
  },
  mediaBuffered: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      const timeRanges = (_a = media == null ? void 0 : media.buffered) != null ? _a : [];
      return Array.from(timeRanges).map((_, i) => [
        Number(timeRanges.start(i).toFixed(3)),
        Number(timeRanges.end(i).toFixed(3))
      ]);
    },
    mediaEvents: ["progress", "emptied"]
  },
  mediaStreamType: {
    get(stateOwners) {
      const { media, options: { defaultStreamType } = {} } = stateOwners;
      const usedDefaultStreamType = [
        import_constants.StreamTypes.LIVE,
        import_constants.StreamTypes.ON_DEMAND
      ].includes(defaultStreamType) ? defaultStreamType : void 0;
      if (!media)
        return usedDefaultStreamType;
      const { streamType } = media;
      if (StreamTypeValues.includes(streamType)) {
        if (streamType === import_constants.StreamTypes.UNKNOWN) {
          return usedDefaultStreamType;
        }
        return streamType;
      }
      const duration = media.duration;
      if (duration === Infinity) {
        return import_constants.StreamTypes.LIVE;
      } else if (Number.isFinite(duration)) {
        return import_constants.StreamTypes.ON_DEMAND;
      }
      return usedDefaultStreamType;
    },
    mediaEvents: [
      "emptied",
      "durationchange",
      "loadedmetadata",
      "streamtypechange"
    ]
  },
  mediaTargetLiveWindow: {
    get(stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return Number.NaN;
      const { targetLiveWindow } = media;
      const streamType = stateMediator.mediaStreamType.get(stateOwners);
      if ((targetLiveWindow == null || Number.isNaN(targetLiveWindow)) && streamType === import_constants.StreamTypes.LIVE) {
        return 0;
      }
      return targetLiveWindow;
    },
    mediaEvents: [
      "emptied",
      "durationchange",
      "loadedmetadata",
      "streamtypechange",
      "targetlivewindowchange"
    ]
  },
  mediaTimeIsLive: {
    get(stateOwners) {
      const {
        media,
        // Default to 10 seconds
        options: { liveEdgeOffset = 10 } = {}
      } = stateOwners;
      if (!media)
        return false;
      if (typeof media.liveEdgeStart === "number") {
        if (Number.isNaN(media.liveEdgeStart))
          return false;
        return media.currentTime >= media.liveEdgeStart;
      }
      const live = stateMediator.mediaStreamType.get(stateOwners) === import_constants.StreamTypes.LIVE;
      if (!live)
        return false;
      const seekable = media.seekable;
      if (!seekable)
        return true;
      if (!seekable.length)
        return false;
      const liveEdgeStart = seekable.end(seekable.length - 1) - liveEdgeOffset;
      return media.currentTime >= liveEdgeStart;
    },
    mediaEvents: ["playing", "timeupdate", "progress", "waiting", "emptied"]
  },
  // Text Tracks modeling
  mediaSubtitlesList: {
    get(stateOwners) {
      return (0, import_util.getSubtitleTracks)(stateOwners).map(
        ({ kind, label, language }) => ({ kind, label, language })
      );
    },
    mediaEvents: ["loadstart"],
    textTracksEvents: ["addtrack", "removetrack"]
  },
  mediaSubtitlesShowing: {
    get(stateOwners) {
      return (0, import_util.getShowingSubtitleTracks)(stateOwners).map(
        ({ kind, label, language }) => ({ kind, label, language })
      );
    },
    mediaEvents: ["loadstart"],
    textTracksEvents: ["addtrack", "removetrack", "change"],
    stateOwnersUpdateHandlers: [
      (_handler, stateOwners) => {
        var _a, _b;
        const { media, options } = stateOwners;
        if (!media)
          return;
        const updateDefaultSubtitlesCallback = (event) => {
          var _a2;
          if (!options.defaultSubtitles)
            return;
          const nonSubsEvent = event && ![import_constants.TextTrackKinds.CAPTIONS, import_constants.TextTrackKinds.SUBTITLES].includes(
            // @ts-ignore
            (_a2 = event == null ? void 0 : event.track) == null ? void 0 : _a2.kind
          );
          if (nonSubsEvent)
            return;
          (0, import_util.toggleSubtitleTracks)(stateOwners, true);
        };
        media.addEventListener(
          "loadstart",
          updateDefaultSubtitlesCallback
        );
        (_a = media.textTracks) == null ? void 0 : _a.addEventListener(
          "addtrack",
          updateDefaultSubtitlesCallback
        );
        (_b = media.textTracks) == null ? void 0 : _b.addEventListener(
          "removetrack",
          updateDefaultSubtitlesCallback
        );
        return () => {
          var _a2, _b2;
          media.removeEventListener(
            "loadstart",
            updateDefaultSubtitlesCallback
          );
          (_a2 = media.textTracks) == null ? void 0 : _a2.removeEventListener(
            "addtrack",
            updateDefaultSubtitlesCallback
          );
          (_b2 = media.textTracks) == null ? void 0 : _b2.removeEventListener(
            "removetrack",
            updateDefaultSubtitlesCallback
          );
        };
      }
    ]
  },
  mediaChaptersCues: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      if (!media)
        return [];
      const [chaptersTrack] = (0, import_captions.getTextTracksList)(media, {
        kind: import_constants.TextTrackKinds.CHAPTERS
      });
      return Array.from((_a = chaptersTrack == null ? void 0 : chaptersTrack.cues) != null ? _a : []).map(
        ({ text, startTime, endTime }) => ({
          text: parseHtmlToText(text),
          startTime,
          endTime
        })
      );
    },
    mediaEvents: ["loadstart", "loadedmetadata"],
    textTracksEvents: ["addtrack", "removetrack", "change"],
    stateOwnersUpdateHandlers: [
      (handler, stateOwners) => {
        var _a;
        const { media } = stateOwners;
        if (!media)
          return;
        const chaptersTrack = media.querySelector(
          'track[kind="chapters"][default][src]'
        );
        const shadowChaptersTrack = (_a = media.shadowRoot) == null ? void 0 : _a.querySelector(
          ':is(video,audio) > track[kind="chapters"][default][src]'
        );
        chaptersTrack == null ? void 0 : chaptersTrack.addEventListener("load", handler);
        shadowChaptersTrack == null ? void 0 : shadowChaptersTrack.addEventListener("load", handler);
        return () => {
          chaptersTrack == null ? void 0 : chaptersTrack.removeEventListener("load", handler);
          shadowChaptersTrack == null ? void 0 : shadowChaptersTrack.removeEventListener("load", handler);
        };
      }
    ]
  },
  // Modeling state tied to root node
  mediaIsPip: {
    get(stateOwners) {
      var _a, _b;
      const { media, documentElement } = stateOwners;
      if (!media || !documentElement)
        return false;
      if (!documentElement.pictureInPictureElement)
        return false;
      if (documentElement.pictureInPictureElement === media)
        return true;
      if (documentElement.pictureInPictureElement instanceof HTMLMediaElement) {
        if (!((_a = media.localName) == null ? void 0 : _a.includes("-")))
          return false;
        return (0, import_element_utils.containsComposedNode)(
          media,
          documentElement.pictureInPictureElement
        );
      }
      if (documentElement.pictureInPictureElement.localName.includes("-")) {
        let currentRoot = documentElement.pictureInPictureElement.shadowRoot;
        while (currentRoot == null ? void 0 : currentRoot.pictureInPictureElement) {
          if (currentRoot.pictureInPictureElement === media)
            return true;
          currentRoot = (_b = currentRoot.pictureInPictureElement) == null ? void 0 : _b.shadowRoot;
        }
      }
      return false;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return;
      if (value) {
        if (!import_server_safe_globals.document.pictureInPictureEnabled) {
          console.warn("MediaChrome: Picture-in-picture is not enabled");
          return;
        }
        if (!media.requestPictureInPicture) {
          console.warn(
            "MediaChrome: The current media does not support picture-in-picture"
          );
          return;
        }
        const warnNotReady = () => {
          console.warn(
            "MediaChrome: The media is not ready for picture-in-picture. It must have a readyState > 0."
          );
        };
        media.requestPictureInPicture().catch((err) => {
          if (err.code === 11) {
            if (!media.src) {
              console.warn(
                "MediaChrome: The media is not ready for picture-in-picture. It must have a src set."
              );
              return;
            }
            if (media.readyState === 0 && media.preload === "none") {
              const cleanup = () => {
                media.removeEventListener("loadedmetadata", tryPip);
                media.preload = "none";
              };
              const tryPip = () => {
                media.requestPictureInPicture().catch(warnNotReady);
                cleanup();
              };
              media.addEventListener("loadedmetadata", tryPip);
              media.preload = "metadata";
              setTimeout(() => {
                if (media.readyState === 0)
                  warnNotReady();
                cleanup();
              }, 1e3);
            } else {
              throw err;
            }
          } else {
            throw err;
          }
        });
      } else if (import_server_safe_globals.document.pictureInPictureElement) {
        import_server_safe_globals.document.exitPictureInPicture();
      }
    },
    mediaEvents: ["enterpictureinpicture", "leavepictureinpicture"]
  },
  mediaRenditionList: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return [...(_a = media == null ? void 0 : media.videoRenditions) != null ? _a : []].map((videoRendition) => ({
        ...videoRendition
      }));
    },
    mediaEvents: ["emptied", "loadstart"],
    videoRenditionsEvents: ["addrendition", "removerendition"]
  },
  /** @TODO Model this as a derived value? (CJP) */
  mediaRenditionSelected: {
    get(stateOwners) {
      var _a, _b, _c;
      const { media } = stateOwners;
      return (_c = (_b = media == null ? void 0 : media.videoRenditions) == null ? void 0 : _b[(_a = media.videoRenditions) == null ? void 0 : _a.selectedIndex]) == null ? void 0 : _c.id;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!(media == null ? void 0 : media.videoRenditions)) {
        console.warn(
          "MediaController: Rendition selection not supported by this media."
        );
        return;
      }
      const renditionId = value;
      const index = Array.prototype.findIndex.call(
        media.videoRenditions,
        (r) => r.id == renditionId
      );
      if (media.videoRenditions.selectedIndex != index) {
        media.videoRenditions.selectedIndex = index;
      }
    },
    mediaEvents: ["emptied"],
    videoRenditionsEvents: ["addrendition", "removerendition", "change"]
  },
  mediaAudioTrackList: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      return [...(_a = media == null ? void 0 : media.audioTracks) != null ? _a : []];
    },
    mediaEvents: ["emptied", "loadstart"],
    audioTracksEvents: ["addtrack", "removetrack"]
  },
  mediaAudioTrackEnabled: {
    get(stateOwners) {
      var _a, _b;
      const { media } = stateOwners;
      return (_b = [...(_a = media == null ? void 0 : media.audioTracks) != null ? _a : []].find(
        (audioTrack) => audioTrack.enabled
      )) == null ? void 0 : _b.id;
    },
    set(value, stateOwners) {
      const { media } = stateOwners;
      if (!(media == null ? void 0 : media.audioTracks)) {
        console.warn(
          "MediaChrome: Audio track selection not supported by this media."
        );
        return;
      }
      const audioTrackId = value;
      for (const track of media.audioTracks) {
        track.enabled = audioTrackId == track.id;
      }
    },
    mediaEvents: ["emptied"],
    audioTracksEvents: ["addtrack", "removetrack", "change"]
  },
  mediaIsFullscreen: {
    get(stateOwners) {
      return (0, import_fullscreen_api.isFullscreen)(stateOwners);
    },
    set(value, stateOwners) {
      if (!value) {
        (0, import_fullscreen_api.exitFullscreen)(stateOwners);
      } else {
        (0, import_fullscreen_api.enterFullscreen)(stateOwners);
      }
    },
    // older Safari version may require webkit-specific events
    rootEvents: ["fullscreenchange", "webkitfullscreenchange"],
    // iOS requires webkit-specific events on the video.
    mediaEvents: [
      "webkitbeginfullscreen",
      "webkitendfullscreen",
      "webkitpresentationmodechanged"
    ]
  },
  mediaIsCasting: {
    // Note this relies on a customized castable-video element.
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      if (!(media == null ? void 0 : media.remote) || ((_a = media.remote) == null ? void 0 : _a.state) === "disconnected")
        return false;
      return !!media.remote.state;
    },
    set(value, stateOwners) {
      var _a, _b;
      const { media } = stateOwners;
      if (!media)
        return;
      if (value && ((_a = media.remote) == null ? void 0 : _a.state) !== "disconnected")
        return;
      if (!value && ((_b = media.remote) == null ? void 0 : _b.state) !== "connected")
        return;
      if (typeof media.remote.prompt !== "function") {
        console.warn(
          "MediaChrome: Casting is not supported in this environment"
        );
        return;
      }
      media.remote.prompt().catch(() => {
      });
    },
    remoteEvents: ["connect", "connecting", "disconnect"]
  },
  // NOTE: Newly added state for tracking airplaying
  mediaIsAirplaying: {
    // NOTE: Cannot know if airplaying since Safari doesn't fully support HTMLMediaElement::remote yet (e.g. remote::state) (CJP)
    get() {
      return false;
    },
    set(_value, stateOwners) {
      const { media } = stateOwners;
      if (!media)
        return;
      if (!(media.webkitShowPlaybackTargetPicker && import_server_safe_globals.globalThis.WebKitPlaybackTargetAvailabilityEvent)) {
        console.error(
          "MediaChrome: received a request to select AirPlay but AirPlay is not supported in this environment"
        );
        return;
      }
      media.webkitShowPlaybackTargetPicker();
    },
    mediaEvents: ["webkitcurrentplaybacktargetiswirelesschanged"]
  },
  mediaFullscreenUnavailable: {
    get(stateOwners) {
      const { media } = stateOwners;
      if (!import_platform_tests.fullscreenSupported || !(0, import_platform_tests.hasFullscreenSupport)(media))
        return import_constants.AvailabilityStates.UNSUPPORTED;
      return void 0;
    }
  },
  mediaPipUnavailable: {
    get(stateOwners) {
      const { media } = stateOwners;
      if (!import_platform_tests.pipSupported || !(0, import_platform_tests.hasPipSupport)(media))
        return import_constants.AvailabilityStates.UNSUPPORTED;
    }
  },
  mediaVolumeUnavailable: {
    get(stateOwners) {
      const { media } = stateOwners;
      if (volumeSupported === false || (media == null ? void 0 : media.volume) == void 0) {
        return import_constants.AvailabilityStates.UNSUPPORTED;
      }
      return void 0;
    },
    // NOTE: Slightly different impl here. Added generic support for
    // "stateOwnersUpdateHandlers" since the original impl had to hack around
    // race conditions. (CJP)
    stateOwnersUpdateHandlers: [
      (handler) => {
        if (volumeSupported == null) {
          volumeSupportPromise.then(
            (supported) => handler(supported ? void 0 : import_constants.AvailabilityStates.UNSUPPORTED)
          );
        }
      }
    ]
  },
  mediaCastUnavailable: {
    // @ts-ignore
    get(stateOwners, { availability = "not-available" } = {}) {
      var _a;
      const { media } = stateOwners;
      if (!import_platform_tests.castSupported || !((_a = media == null ? void 0 : media.remote) == null ? void 0 : _a.state)) {
        return import_constants.AvailabilityStates.UNSUPPORTED;
      }
      if (availability == null || availability === "available")
        return void 0;
      return import_constants.AvailabilityStates.UNAVAILABLE;
    },
    stateOwnersUpdateHandlers: [
      (handler, stateOwners) => {
        var _a;
        const { media } = stateOwners;
        if (!media)
          return;
        const remotePlaybackDisabled = media.disableRemotePlayback || media.hasAttribute("disableremoteplayback");
        if (!remotePlaybackDisabled) {
          (_a = media == null ? void 0 : media.remote) == null ? void 0 : _a.watchAvailability((availabilityBool) => {
            const availability = availabilityBool ? "available" : "not-available";
            handler({ availability });
          }).catch((error) => {
            if (error.name === "NotSupportedError") {
              handler({ availability: null });
            } else {
              handler({ availability: "not-available" });
            }
          });
        }
        return () => {
          var _a2;
          (_a2 = media == null ? void 0 : media.remote) == null ? void 0 : _a2.cancelWatchAvailability().catch(() => {
          });
        };
      }
    ]
  },
  mediaAirplayUnavailable: {
    get(_stateOwners, event) {
      if (!import_platform_tests.airplaySupported)
        return import_constants.AvailabilityStates.UNSUPPORTED;
      if ((event == null ? void 0 : event.availability) === "not-available") {
        return import_constants.AvailabilityStates.UNAVAILABLE;
      }
      return void 0;
    },
    // NOTE: Keeping this event, as it's still the documented way of monitoring
    // for AirPlay availability from Apple.
    // See: https://developer.apple.com/documentation/webkitjs/adding_an_airplay_button_to_your_safari_media_controls#2940021 (CJP)
    mediaEvents: ["webkitplaybacktargetavailabilitychanged"],
    stateOwnersUpdateHandlers: [
      (handler, stateOwners) => {
        var _a;
        const { media } = stateOwners;
        if (!media)
          return;
        const remotePlaybackDisabled = media.disableRemotePlayback || media.hasAttribute("disableremoteplayback");
        if (!remotePlaybackDisabled) {
          (_a = media == null ? void 0 : media.remote) == null ? void 0 : _a.watchAvailability((availabilityBool) => {
            const availability = availabilityBool ? "available" : "not-available";
            handler({ availability });
          }).catch((error) => {
            if (error.name === "NotSupportedError") {
              handler({ availability: null });
            } else {
              handler({ availability: "not-available" });
            }
          });
        }
        return () => {
          var _a2;
          (_a2 = media == null ? void 0 : media.remote) == null ? void 0 : _a2.cancelWatchAvailability().catch(() => {
          });
        };
      }
    ]
  },
  mediaRenditionUnavailable: {
    get(stateOwners) {
      var _a;
      const { media } = stateOwners;
      if (!(media == null ? void 0 : media.videoRenditions)) {
        return import_constants.AvailabilityStates.UNSUPPORTED;
      }
      if (!((_a = media.videoRenditions) == null ? void 0 : _a.length)) {
        return import_constants.AvailabilityStates.UNAVAILABLE;
      }
      return void 0;
    },
    mediaEvents: ["emptied", "loadstart"],
    videoRenditionsEvents: ["addrendition", "removerendition"]
  },
  mediaAudioTrackUnavailable: {
    get(stateOwners) {
      var _a, _b;
      const { media } = stateOwners;
      if (!(media == null ? void 0 : media.audioTracks)) {
        return import_constants.AvailabilityStates.UNSUPPORTED;
      }
      if (((_b = (_a = media.audioTracks) == null ? void 0 : _a.length) != null ? _b : 0) <= 1) {
        return import_constants.AvailabilityStates.UNAVAILABLE;
      }
      return void 0;
    },
    mediaEvents: ["emptied", "loadstart"],
    audioTracksEvents: ["addtrack", "removetrack"]
  }
};
