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
var fr_exports = {};
__export(fr_exports, {
  Fr: () => Fr
});
module.exports = __toCommonJS(fr_exports);
var import_i18n = require("../utils/i18n.js");
const Fr = {
  "Start airplay": "D\xE9marrer la diffusion AirPlay",
  "Stop airplay": "Arr\xEAter la diffusion AirPlay",
  Audio: "Audio",
  Captions: "Sous-titres",
  "Enable captions": "Activer les sous-titres",
  "Disable captions": "D\xE9sactiver les sous-titres",
  "Start casting": "D\xE9marrer la diffusion (cast)",
  "Stop casting": "Arr\xEAter la diffusion (cast)",
  "Enter fullscreen mode": "Mettre en mode plein \xE9cran",
  "Exit fullscreen mode": "Quitter le mode plein \xE9cran",
  Mute: "D\xE9sactiver le son",
  Unmute: "Activer le son",
  "Enter picture in picture mode": "Mettre en mode image-en-image (PiP)",
  "Exit picture in picture mode": "Quitter le mode image-en-image (PiP)",
  Play: "Lire",
  Pause: "Pause",
  "Playback rate": "Taux de lecture",
  "Playback rate {playbackRate}": "Taux de lecture {playbackRate}",
  Quality: "Qualit\xE9",
  "Seek backward": "Reculer",
  "Seek forward": "Avancer",
  Settings: "Param\xE8tres",
  Auto: "Auto",
  "audio player": "lecteur audio",
  "video player": "lecteur vid\xE9o",
  volume: "volume",
  seek: "se d\xE9placer",
  "closed captions": "sous-titres cod\xE9s",
  "current playback rate": "taux de lecture actuel",
  "playback time": "dur\xE9e de lecture",
  "media loading": "chargement des m\xE9dias",
  settings: "param\xE8tres",
  "audio tracks": "pistes audio",
  quality: "qualit\xE9",
  play: "lire",
  pause: "pause",
  mute: "d\xE9sactiver le son",
  unmute: "activer le son",
  live: "en direct",
  Off: "D\xE9sactiv\xE9",
  "start airplay": "d\xE9marrer la diffusion AirPlay",
  "stop airplay": "arr\xEAter la diffusion AirPlay",
  "start casting": "d\xE9marrer la diffusion (cast)",
  "stop casting": "arr\xEAter la diffusion (cast)",
  "enter fullscreen mode": "mettre en mode plein \xE9cran",
  "exit fullscreen mode": "quitter le mode plein \xE9cran",
  "enter picture in picture mode": "mettre en mode image-en-image (PiP)",
  "exit picture in picture mode": "quitter le mode image-en-image (PiP)",
  "seek to live": "aller au direct",
  "playing live": "lecture en direct",
  "seek back {seekOffset} seconds": "reculer {seekOffset} secondes",
  "seek forward {seekOffset} seconds": "avancer {seekOffset} secondes",
  "Network Error": "Erreur r\xE9seau",
  "Decode Error": "Erreur de d\xE9codage",
  "Source Not Supported": "Source non support\xE9e",
  "Encryption Error": "Erreur de chiffrement",
  "A network error caused the media download to fail.": "Une erreur r\xE9seau a caus\xE9 l\u2019\xE9chec du t\xE9l\xE9chargement du m\xE9dia.",
  "A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.": "Une erreur de m\xE9dia a provoqu\xE9 l\u2019interruption de la lecture. Le m\xE9dia peut \xEAtre corrompu ou votre navigateur ne prend pas en charge ce format.",
  "An unsupported error occurred. The server or network failed, or your browser does not support this format.": "Une erreur non support\xE9e s\u2019est produite. Le serveur ou le r\xE9seau a \xE9chou\xE9, ou votre navigateur ne prend pas en charge ce format.",
  "The media is encrypted and there are no keys to decrypt it.": "Le m\xE9dia est chiffr\xE9 et il n\u2019y a pas de cl\xE9s pour le d\xE9chiffrer."
};
(0, import_i18n.addTranslation)("fr", Fr);
