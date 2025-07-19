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
var js_exports = {};
__export(js_exports, {
  MediaAirplayButton: () => import_media_airplay_button.default,
  MediaCaptionsButton: () => import_media_captions_button.default,
  MediaCastButton: () => import_media_cast_button.default,
  MediaChromeButton: () => import_media_chrome_button.default,
  MediaChromeDialog: () => import_media_chrome_dialog.default,
  MediaChromeRange: () => import_media_chrome_range.default,
  MediaContainer: () => import_media_container.default,
  MediaControlBar: () => import_media_control_bar.default,
  MediaController: () => import_media_controller.default,
  MediaDurationDisplay: () => import_media_duration_display.default,
  MediaErrorDialog: () => import_media_error_dialog.default,
  MediaFullscreenButton: () => import_media_fullscreen_button.default,
  MediaGestureReceiver: () => import_media_gesture_receiver.default,
  MediaLiveButton: () => import_media_live_button.default,
  MediaLoadingIndicator: () => import_media_loading_indicator.default,
  MediaMuteButton: () => import_media_mute_button.default,
  MediaPipButton: () => import_media_pip_button.default,
  MediaPlayButton: () => import_media_play_button.default,
  MediaPlaybackRateButton: () => import_media_playback_rate_button.default,
  MediaPosterImage: () => import_media_poster_image.default,
  MediaPreviewChapterDisplay: () => import_media_preview_chapter_display.default,
  MediaPreviewThumbnail: () => import_media_preview_thumbnail.default,
  MediaPreviewTimeDisplay: () => import_media_preview_time_display.default,
  MediaSeekBackwardButton: () => import_media_seek_backward_button.default,
  MediaSeekForwardButton: () => import_media_seek_forward_button.default,
  MediaTextDisplay: () => import_media_text_display.default,
  MediaTimeDisplay: () => import_media_time_display.default,
  MediaTimeRange: () => import_media_time_range.default,
  MediaTooltip: () => import_media_tooltip.default,
  MediaVolumeRange: () => import_media_volume_range.default,
  constants: () => constants,
  t: () => import_i18n.t,
  timeUtils: () => timeUtils
});
module.exports = __toCommonJS(js_exports);
var constants = __toESM(require("./constants.js"), 1);
var timeUtils = __toESM(require("./utils/time.js"), 1);
var import_i18n = require("./utils/i18n.js");
var import_media_controller = __toESM(require("./media-controller.js"), 1);
var import_media_airplay_button = __toESM(require("./media-airplay-button.js"), 1);
var import_media_captions_button = __toESM(require("./media-captions-button.js"), 1);
var import_media_cast_button = __toESM(require("./media-cast-button.js"), 1);
var import_media_chrome_button = __toESM(require("./media-chrome-button.js"), 1);
var import_media_chrome_dialog = __toESM(require("./media-chrome-dialog.js"), 1);
var import_media_chrome_range = __toESM(require("./media-chrome-range.js"), 1);
var import_media_control_bar = __toESM(require("./media-control-bar.js"), 1);
var import_media_duration_display = __toESM(require("./media-duration-display.js"), 1);
var import_media_error_dialog = __toESM(require("./media-error-dialog.js"), 1);
var import_media_fullscreen_button = __toESM(require("./media-fullscreen-button.js"), 1);
var import_media_gesture_receiver = __toESM(require("./media-gesture-receiver.js"), 1);
var import_media_live_button = __toESM(require("./media-live-button.js"), 1);
var import_media_loading_indicator = __toESM(require("./media-loading-indicator.js"), 1);
var import_media_mute_button = __toESM(require("./media-mute-button.js"), 1);
var import_media_pip_button = __toESM(require("./media-pip-button.js"), 1);
var import_media_playback_rate_button = __toESM(require("./media-playback-rate-button.js"), 1);
var import_media_play_button = __toESM(require("./media-play-button.js"), 1);
var import_media_poster_image = __toESM(require("./media-poster-image.js"), 1);
var import_media_preview_chapter_display = __toESM(require("./media-preview-chapter-display.js"), 1);
var import_media_preview_thumbnail = __toESM(require("./media-preview-thumbnail.js"), 1);
var import_media_preview_time_display = __toESM(require("./media-preview-time-display.js"), 1);
var import_media_seek_backward_button = __toESM(require("./media-seek-backward-button.js"), 1);
var import_media_seek_forward_button = __toESM(require("./media-seek-forward-button.js"), 1);
var import_media_time_display = __toESM(require("./media-time-display.js"), 1);
var import_media_time_range = __toESM(require("./media-time-range.js"), 1);
var import_media_tooltip = __toESM(require("./media-tooltip.js"), 1);
var import_media_volume_range = __toESM(require("./media-volume-range.js"), 1);
var import_media_container = __toESM(require("./media-container.js"), 1);
var import_media_text_display = __toESM(require("./media-text-display.js"), 1);
