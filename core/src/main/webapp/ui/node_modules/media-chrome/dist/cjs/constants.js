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
var constants_exports = {};
__export(constants_exports, {
  AttributeToStateChangeEventMap: () => AttributeToStateChangeEventMap,
  AvailabilityStates: () => AvailabilityStates,
  MediaStateChangeEvents: () => MediaStateChangeEvents,
  MediaStateReceiverAttributes: () => MediaStateReceiverAttributes,
  MediaUIAttributes: () => MediaUIAttributes,
  MediaUIEvents: () => MediaUIEvents,
  MediaUIProps: () => MediaUIProps,
  PointerTypes: () => PointerTypes,
  ReadyStates: () => ReadyStates,
  StateChangeEventToAttributeMap: () => StateChangeEventToAttributeMap,
  StreamTypes: () => StreamTypes,
  TextTrackKinds: () => TextTrackKinds,
  TextTrackModes: () => TextTrackModes,
  VolumeLevels: () => VolumeLevels,
  WebkitPresentationModes: () => WebkitPresentationModes
});
module.exports = __toCommonJS(constants_exports);
const MediaUIEvents = {
  MEDIA_PLAY_REQUEST: "mediaplayrequest",
  MEDIA_PAUSE_REQUEST: "mediapauserequest",
  MEDIA_MUTE_REQUEST: "mediamuterequest",
  MEDIA_UNMUTE_REQUEST: "mediaunmuterequest",
  MEDIA_VOLUME_REQUEST: "mediavolumerequest",
  MEDIA_SEEK_REQUEST: "mediaseekrequest",
  MEDIA_AIRPLAY_REQUEST: "mediaairplayrequest",
  MEDIA_ENTER_FULLSCREEN_REQUEST: "mediaenterfullscreenrequest",
  MEDIA_EXIT_FULLSCREEN_REQUEST: "mediaexitfullscreenrequest",
  MEDIA_PREVIEW_REQUEST: "mediapreviewrequest",
  MEDIA_ENTER_PIP_REQUEST: "mediaenterpiprequest",
  MEDIA_EXIT_PIP_REQUEST: "mediaexitpiprequest",
  MEDIA_ENTER_CAST_REQUEST: "mediaentercastrequest",
  MEDIA_EXIT_CAST_REQUEST: "mediaexitcastrequest",
  MEDIA_SHOW_TEXT_TRACKS_REQUEST: "mediashowtexttracksrequest",
  MEDIA_HIDE_TEXT_TRACKS_REQUEST: "mediahidetexttracksrequest",
  MEDIA_SHOW_SUBTITLES_REQUEST: "mediashowsubtitlesrequest",
  MEDIA_DISABLE_SUBTITLES_REQUEST: "mediadisablesubtitlesrequest",
  MEDIA_TOGGLE_SUBTITLES_REQUEST: "mediatogglesubtitlesrequest",
  MEDIA_PLAYBACK_RATE_REQUEST: "mediaplaybackraterequest",
  MEDIA_RENDITION_REQUEST: "mediarenditionrequest",
  MEDIA_AUDIO_TRACK_REQUEST: "mediaaudiotrackrequest",
  MEDIA_SEEK_TO_LIVE_REQUEST: "mediaseektoliverequest",
  REGISTER_MEDIA_STATE_RECEIVER: "registermediastatereceiver",
  UNREGISTER_MEDIA_STATE_RECEIVER: "unregistermediastatereceiver"
};
const MediaStateReceiverAttributes = {
  MEDIA_CHROME_ATTRIBUTES: "mediachromeattributes",
  MEDIA_CONTROLLER: "mediacontroller"
};
const MediaUIProps = {
  MEDIA_AIRPLAY_UNAVAILABLE: "mediaAirplayUnavailable",
  MEDIA_AUDIO_TRACK_ENABLED: "mediaAudioTrackEnabled",
  MEDIA_AUDIO_TRACK_LIST: "mediaAudioTrackList",
  MEDIA_AUDIO_TRACK_UNAVAILABLE: "mediaAudioTrackUnavailable",
  MEDIA_BUFFERED: "mediaBuffered",
  MEDIA_CAST_UNAVAILABLE: "mediaCastUnavailable",
  MEDIA_CHAPTERS_CUES: "mediaChaptersCues",
  MEDIA_CURRENT_TIME: "mediaCurrentTime",
  MEDIA_DURATION: "mediaDuration",
  MEDIA_ENDED: "mediaEnded",
  MEDIA_ERROR: "mediaError",
  MEDIA_ERROR_CODE: "mediaErrorCode",
  MEDIA_ERROR_MESSAGE: "mediaErrorMessage",
  MEDIA_FULLSCREEN_UNAVAILABLE: "mediaFullscreenUnavailable",
  MEDIA_HAS_PLAYED: "mediaHasPlayed",
  MEDIA_HEIGHT: "mediaHeight",
  MEDIA_IS_AIRPLAYING: "mediaIsAirplaying",
  MEDIA_IS_CASTING: "mediaIsCasting",
  MEDIA_IS_FULLSCREEN: "mediaIsFullscreen",
  MEDIA_IS_PIP: "mediaIsPip",
  MEDIA_LOADING: "mediaLoading",
  MEDIA_MUTED: "mediaMuted",
  MEDIA_PAUSED: "mediaPaused",
  MEDIA_PIP_UNAVAILABLE: "mediaPipUnavailable",
  MEDIA_PLAYBACK_RATE: "mediaPlaybackRate",
  MEDIA_PREVIEW_CHAPTER: "mediaPreviewChapter",
  MEDIA_PREVIEW_COORDS: "mediaPreviewCoords",
  MEDIA_PREVIEW_IMAGE: "mediaPreviewImage",
  MEDIA_PREVIEW_TIME: "mediaPreviewTime",
  MEDIA_RENDITION_LIST: "mediaRenditionList",
  MEDIA_RENDITION_SELECTED: "mediaRenditionSelected",
  MEDIA_RENDITION_UNAVAILABLE: "mediaRenditionUnavailable",
  MEDIA_SEEKABLE: "mediaSeekable",
  MEDIA_STREAM_TYPE: "mediaStreamType",
  MEDIA_SUBTITLES_LIST: "mediaSubtitlesList",
  MEDIA_SUBTITLES_SHOWING: "mediaSubtitlesShowing",
  MEDIA_TARGET_LIVE_WINDOW: "mediaTargetLiveWindow",
  MEDIA_TIME_IS_LIVE: "mediaTimeIsLive",
  MEDIA_VOLUME: "mediaVolume",
  MEDIA_VOLUME_LEVEL: "mediaVolumeLevel",
  MEDIA_VOLUME_UNAVAILABLE: "mediaVolumeUnavailable",
  MEDIA_WIDTH: "mediaWidth"
};
const MediaUIPropsEntries = Object.entries(
  MediaUIProps
);
const MediaUIAttributes = MediaUIPropsEntries.reduce(
  (dictObj, [key, propName]) => {
    dictObj[key] = propName.toLowerCase();
    return dictObj;
  },
  {}
);
const AdditionalStateChangeEvents = {
  USER_INACTIVE_CHANGE: "userinactivechange",
  BREAKPOINTS_CHANGE: "breakpointchange",
  BREAKPOINTS_COMPUTED: "breakpointscomputed"
};
const MediaStateChangeEvents = MediaUIPropsEntries.reduce(
  (dictObj, [key, propName]) => {
    dictObj[key] = propName.toLowerCase();
    return dictObj;
  },
  { ...AdditionalStateChangeEvents }
);
const StateChangeEventToAttributeMap = Object.entries(
  MediaStateChangeEvents
).reduce(
  (mapObj, [key, eventType]) => {
    const attrName = MediaUIAttributes[key];
    if (attrName) {
      mapObj[eventType] = attrName;
    }
    return mapObj;
  },
  { userinactivechange: "userinactive" }
);
const AttributeToStateChangeEventMap = Object.entries(
  MediaUIAttributes
).reduce(
  (mapObj, [key, attrName]) => {
    const evtType = MediaStateChangeEvents[key];
    if (evtType) {
      mapObj[attrName] = evtType;
    }
    return mapObj;
  },
  { userinactive: "userinactivechange" }
);
const TextTrackKinds = {
  SUBTITLES: "subtitles",
  CAPTIONS: "captions",
  DESCRIPTIONS: "descriptions",
  CHAPTERS: "chapters",
  METADATA: "metadata"
};
const TextTrackModes = {
  DISABLED: "disabled",
  HIDDEN: "hidden",
  SHOWING: "showing"
};
const ReadyStates = {
  HAVE_NOTHING: 0,
  HAVE_METADATA: 1,
  HAVE_CURRENT_DATA: 2,
  HAVE_FUTURE_DATA: 3,
  HAVE_ENOUGH_DATA: 4
};
const PointerTypes = {
  MOUSE: "mouse",
  PEN: "pen",
  TOUCH: "touch"
};
const AvailabilityStates = {
  UNAVAILABLE: "unavailable",
  UNSUPPORTED: "unsupported"
};
const StreamTypes = {
  LIVE: "live",
  ON_DEMAND: "on-demand",
  UNKNOWN: "unknown"
};
const VolumeLevels = {
  HIGH: "high",
  MEDIUM: "medium",
  LOW: "low",
  OFF: "off"
};
const WebkitPresentationModes = {
  INLINE: "inline",
  FULLSCREEN: "fullscreen",
  PICTURE_IN_PICTURE: "picture-in-picture"
};
