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
var menu_exports = {};
__export(menu_exports, {
  MediaAudioTrackMenu: () => import_media_audio_track_menu.MediaAudioTrackMenu,
  MediaAudioTrackMenuButton: () => import_media_audio_track_menu_button.MediaAudioTrackMenuButton,
  MediaCaptionsMenu: () => import_media_captions_menu.MediaCaptionsMenu,
  MediaCaptionsMenuButton: () => import_media_captions_menu_button.MediaCaptionsMenuButton,
  MediaChromeMenu: () => import_media_chrome_menu.MediaChromeMenu,
  MediaChromeMenuButton: () => import_media_chrome_menu_button.MediaChromeMenuButton,
  MediaChromeMenuItem: () => import_media_chrome_menu_item.MediaChromeMenuItem,
  MediaPlaybackRateMenu: () => import_media_playback_rate_menu.MediaPlaybackRateMenu,
  MediaPlaybackRateMenuButton: () => import_media_playback_rate_menu_button.MediaPlaybackRateMenuButton,
  MediaRenditionMenu: () => import_media_rendition_menu.MediaRenditionMenu,
  MediaRenditionMenuButton: () => import_media_rendition_menu_button.MediaRenditionMenuButton,
  MediaSettingsMenu: () => import_media_settings_menu.MediaSettingsMenu,
  MediaSettingsMenuButton: () => import_media_settings_menu_button.MediaSettingsMenuButton,
  MediaSettingsMenuItem: () => import_media_settings_menu_item.MediaSettingsMenuItem
});
module.exports = __toCommonJS(menu_exports);
var import_media_chrome_menu = require("./media-chrome-menu.js");
var import_media_chrome_menu_item = require("./media-chrome-menu-item.js");
var import_media_settings_menu = require("./media-settings-menu.js");
var import_media_settings_menu_item = require("./media-settings-menu-item.js");
var import_media_settings_menu_button = require("./media-settings-menu-button.js");
var import_media_audio_track_menu = require("./media-audio-track-menu.js");
var import_media_audio_track_menu_button = require("./media-audio-track-menu-button.js");
var import_media_captions_menu = require("./media-captions-menu.js");
var import_media_captions_menu_button = require("./media-captions-menu-button.js");
var import_media_playback_rate_menu = require("./media-playback-rate-menu.js");
var import_media_playback_rate_menu_button = require("./media-playback-rate-menu-button.js");
var import_media_rendition_menu = require("./media-rendition-menu.js");
var import_media_rendition_menu_button = require("./media-rendition-menu-button.js");
var import_media_chrome_menu_button = require("./media-chrome-menu-button.js");
