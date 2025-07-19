var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
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
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var react_exports = {};
__export(react_exports, {
  MediaAirplayButton: () => MediaAirplayButton,
  MediaCaptionsButton: () => MediaCaptionsButton,
  MediaCastButton: () => MediaCastButton,
  MediaChromeButton: () => MediaChromeButton,
  MediaChromeDialog: () => MediaChromeDialog,
  MediaChromeRange: () => MediaChromeRange,
  MediaContainer: () => MediaContainer,
  MediaControlBar: () => MediaControlBar,
  MediaController: () => MediaController,
  MediaDurationDisplay: () => MediaDurationDisplay,
  MediaErrorDialog: () => MediaErrorDialog,
  MediaFullscreenButton: () => MediaFullscreenButton,
  MediaGestureReceiver: () => MediaGestureReceiver,
  MediaLiveButton: () => MediaLiveButton,
  MediaLoadingIndicator: () => MediaLoadingIndicator,
  MediaMuteButton: () => MediaMuteButton,
  MediaPipButton: () => MediaPipButton,
  MediaPlayButton: () => MediaPlayButton,
  MediaPlaybackRateButton: () => MediaPlaybackRateButton,
  MediaPosterImage: () => MediaPosterImage,
  MediaPreviewChapterDisplay: () => MediaPreviewChapterDisplay,
  MediaPreviewThumbnail: () => MediaPreviewThumbnail,
  MediaPreviewTimeDisplay: () => MediaPreviewTimeDisplay,
  MediaSeekBackwardButton: () => MediaSeekBackwardButton,
  MediaSeekForwardButton: () => MediaSeekForwardButton,
  MediaTextDisplay: () => MediaTextDisplay,
  MediaTimeDisplay: () => MediaTimeDisplay,
  MediaTimeRange: () => MediaTimeRange,
  MediaTooltip: () => MediaTooltip,
  MediaVolumeRange: () => MediaVolumeRange
});
module.exports = __toCommonJS(react_exports);
var import_react = __toESM(require("react"), 1);
var import_ce_la_react = require("ce-la-react");
var Modules = __toESM(require("../index.js"), 1);
function toAttributeValue(propValue) {
  if (typeof propValue === "boolean")
    return propValue ? "" : void 0;
  if (typeof propValue === "function")
    return void 0;
  const isPrimitive = (v) => typeof v === "string" || typeof v === "number" || typeof v === "boolean";
  if (Array.isArray(propValue) && propValue.every(isPrimitive))
    return propValue.join(" ");
  if (typeof propValue === "object" && propValue !== null)
    return void 0;
  return propValue;
}
const MediaGestureReceiver = (0, import_ce_la_react.createComponent)({
  tagName: "media-gesture-receiver",
  elementClass: Modules.MediaGestureReceiver,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaContainer = (0, import_ce_la_react.createComponent)({
  tagName: "media-container",
  elementClass: Modules.MediaContainer,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaController = (0, import_ce_la_react.createComponent)({
  tagName: "media-controller",
  elementClass: Modules.MediaController,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaTooltip = (0, import_ce_la_react.createComponent)({
  tagName: "media-tooltip",
  elementClass: Modules.MediaTooltip,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaChromeButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-button",
  elementClass: Modules.MediaChromeButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaAirplayButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-airplay-button",
  elementClass: Modules.MediaAirplayButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaCaptionsButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-captions-button",
  elementClass: Modules.MediaCaptionsButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaCastButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-cast-button",
  elementClass: Modules.MediaCastButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaChromeDialog = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-dialog",
  elementClass: Modules.MediaChromeDialog,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaChromeRange = (0, import_ce_la_react.createComponent)({
  tagName: "media-chrome-range",
  elementClass: Modules.MediaChromeRange,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaControlBar = (0, import_ce_la_react.createComponent)({
  tagName: "media-control-bar",
  elementClass: Modules.MediaControlBar,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaTextDisplay = (0, import_ce_la_react.createComponent)({
  tagName: "media-text-display",
  elementClass: Modules.MediaTextDisplay,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaDurationDisplay = (0, import_ce_la_react.createComponent)({
  tagName: "media-duration-display",
  elementClass: Modules.MediaDurationDisplay,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaErrorDialog = (0, import_ce_la_react.createComponent)({
  tagName: "media-error-dialog",
  elementClass: Modules.MediaErrorDialog,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaFullscreenButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-fullscreen-button",
  elementClass: Modules.MediaFullscreenButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaLiveButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-live-button",
  elementClass: Modules.MediaLiveButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaLoadingIndicator = (0, import_ce_la_react.createComponent)({
  tagName: "media-loading-indicator",
  elementClass: Modules.MediaLoadingIndicator,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaMuteButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-mute-button",
  elementClass: Modules.MediaMuteButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPipButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-pip-button",
  elementClass: Modules.MediaPipButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPlaybackRateButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-playback-rate-button",
  elementClass: Modules.MediaPlaybackRateButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPlayButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-play-button",
  elementClass: Modules.MediaPlayButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPosterImage = (0, import_ce_la_react.createComponent)({
  tagName: "media-poster-image",
  elementClass: Modules.MediaPosterImage,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPreviewChapterDisplay = (0, import_ce_la_react.createComponent)({
  tagName: "media-preview-chapter-display",
  elementClass: Modules.MediaPreviewChapterDisplay,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPreviewThumbnail = (0, import_ce_la_react.createComponent)({
  tagName: "media-preview-thumbnail",
  elementClass: Modules.MediaPreviewThumbnail,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaPreviewTimeDisplay = (0, import_ce_la_react.createComponent)({
  tagName: "media-preview-time-display",
  elementClass: Modules.MediaPreviewTimeDisplay,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaSeekBackwardButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-seek-backward-button",
  elementClass: Modules.MediaSeekBackwardButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaSeekForwardButton = (0, import_ce_la_react.createComponent)({
  tagName: "media-seek-forward-button",
  elementClass: Modules.MediaSeekForwardButton,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaTimeDisplay = (0, import_ce_la_react.createComponent)({
  tagName: "media-time-display",
  elementClass: Modules.MediaTimeDisplay,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaTimeRange = (0, import_ce_la_react.createComponent)({
  tagName: "media-time-range",
  elementClass: Modules.MediaTimeRange,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
const MediaVolumeRange = (0, import_ce_la_react.createComponent)({
  tagName: "media-volume-range",
  elementClass: Modules.MediaVolumeRange,
  react: import_react.default,
  toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true
  }
});
