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
var util_exports = {};
__export(util_exports, {
  areArraysEq: () => areArraysEq,
  areValuesEq: () => areValuesEq,
  getShowingSubtitleTracks: () => getShowingSubtitleTracks,
  getSubtitleTracks: () => getSubtitleTracks,
  toggleSubtitleTracks: () => toggleSubtitleTracks
});
module.exports = __toCommonJS(util_exports);
var import_constants = require("../constants.js");
var import_captions = require("../utils/captions.js");
const getSubtitleTracks = (stateOwners) => {
  return (0, import_captions.getTextTracksList)(stateOwners.media, (textTrack) => {
    return [import_constants.TextTrackKinds.SUBTITLES, import_constants.TextTrackKinds.CAPTIONS].includes(
      textTrack.kind
    );
  }).sort((a, b) => a.kind >= b.kind ? 1 : -1);
};
const getShowingSubtitleTracks = (stateOwners) => {
  return (0, import_captions.getTextTracksList)(stateOwners.media, (textTrack) => {
    return textTrack.mode === import_constants.TextTrackModes.SHOWING && [import_constants.TextTrackKinds.SUBTITLES, import_constants.TextTrackKinds.CAPTIONS].includes(
      textTrack.kind
    );
  });
};
const toggleSubtitleTracks = (stateOwners, force) => {
  const tracks = getSubtitleTracks(stateOwners);
  const showingSubitleTracks = getShowingSubtitleTracks(stateOwners);
  const subtitlesShowing = !!showingSubitleTracks.length;
  if (!tracks.length)
    return;
  if (force === false || subtitlesShowing && force !== true) {
    (0, import_captions.updateTracksModeTo)(import_constants.TextTrackModes.DISABLED, tracks, showingSubitleTracks);
  } else if (force === true || !subtitlesShowing && force !== false) {
    let subTrack = tracks[0];
    const { options } = stateOwners;
    if (!(options == null ? void 0 : options.noSubtitlesLangPref)) {
      const subtitlesPref = globalThis.localStorage.getItem(
        "media-chrome-pref-subtitles-lang"
      );
      const userLangPrefs = subtitlesPref ? [subtitlesPref, ...globalThis.navigator.languages] : globalThis.navigator.languages;
      const preferredAvailableSubs = tracks.filter((textTrack) => {
        return userLangPrefs.some(
          (lang) => textTrack.language.toLowerCase().startsWith(lang.split("-")[0])
        );
      }).sort((textTrackA, textTrackB) => {
        const idxA = userLangPrefs.findIndex(
          (lang) => textTrackA.language.toLowerCase().startsWith(lang.split("-")[0])
        );
        const idxB = userLangPrefs.findIndex(
          (lang) => textTrackB.language.toLowerCase().startsWith(lang.split("-")[0])
        );
        return idxA - idxB;
      });
      if (preferredAvailableSubs[0]) {
        subTrack = preferredAvailableSubs[0];
      }
    }
    const { language, label, kind } = subTrack;
    (0, import_captions.updateTracksModeTo)(import_constants.TextTrackModes.DISABLED, tracks, showingSubitleTracks);
    (0, import_captions.updateTracksModeTo)(import_constants.TextTrackModes.SHOWING, tracks, [
      { language, label, kind }
    ]);
  }
};
const areValuesEq = (x, y) => {
  if (x === y)
    return true;
  if (x == null || y == null)
    return false;
  if (typeof x !== typeof y)
    return false;
  if (typeof x === "number" && Number.isNaN(x) && Number.isNaN(y))
    return true;
  if (typeof x !== "object")
    return false;
  if (Array.isArray(x))
    return areArraysEq(x, y);
  return Object.entries(x).every(
    // NOTE: Checking key in y to disambiguate between between missing keys and keys whose value are undefined (CJP)
    ([key, value]) => key in y && areValuesEq(value, y[key])
  );
};
const areArraysEq = (xs, ys) => {
  const xIsArray = Array.isArray(xs);
  const yIsArray = Array.isArray(ys);
  if (xIsArray !== yIsArray)
    return false;
  if (!(xIsArray || yIsArray))
    return true;
  if (xs.length !== ys.length)
    return false;
  return xs.every((x, i) => areValuesEq(x, ys[i]));
};
