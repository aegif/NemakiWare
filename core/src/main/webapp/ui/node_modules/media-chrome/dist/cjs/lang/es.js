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
var es_exports = {};
__export(es_exports, {
  Es: () => Es
});
module.exports = __toCommonJS(es_exports);
var import_i18n = require("../utils/i18n.js");
const Es = {
  "Start airplay": "Iniciar AirPlay",
  "Stop airplay": "Detener AirPlay",
  Audio: "Audio",
  Captions: "Subt\xEDtulos",
  "Enable captions": "Activar subt\xEDtulos",
  "Disable captions": "Desactivar subt\xEDtulos",
  "Start casting": "Iniciar transmisi\xF3n",
  "Stop casting": "Detener transmisi\xF3n",
  "Enter fullscreen mode": "Entrar en modo pantalla completa",
  "Exit fullscreen mode": "Salir del modo pantalla completa",
  Mute: "Silenciar",
  Unmute: "Reactivar sonido",
  "Enter picture in picture mode": "Entrar en modo imagen en imagen",
  "Exit picture in picture mode": "Salir del modo imagen en imagen",
  Play: "Reproducir",
  Pause: "Pausar",
  "Playback rate": "Velocidad de reproducci\xF3n",
  "Playback rate {playbackRate}": "Velocidad de reproducci\xF3n {playbackRate}",
  Quality: "Calidad",
  "Seek backward": "Retroceder",
  "Seek forward": "Avanzar",
  Settings: "Configuraci\xF3n",
  Auto: "Auto",
  "audio player": "reproductor de audio",
  "video player": "reproductor de video",
  volume: "volumen",
  seek: "b\xFAsqueda",
  "closed captions": "subt\xEDtulos",
  "current playback rate": "velocidad de reproducci\xF3n actual",
  "playback time": "tiempo de reproducci\xF3n",
  "media loading": "cargando medios",
  settings: "configuraci\xF3n",
  "audio tracks": "pistas de audio",
  quality: "calidad",
  play: "reproducir",
  pause: "pausar",
  mute: "silenciar",
  unmute: "reactivar sonido",
  live: "en vivo",
  Off: "Apagado",
  "start airplay": "iniciar AirPlay",
  "stop airplay": "detener AirPlay",
  "start casting": "iniciar transmisi\xF3n",
  "stop casting": "detener transmisi\xF3n",
  "enter fullscreen mode": "entrar en modo pantalla completa",
  "exit fullscreen mode": "salir del modo pantalla completa",
  "enter picture in picture mode": "entrar en modo imagen en imagen",
  "exit picture in picture mode": "salir del modo imagen en imagen",
  "seek to live": "ir a la transmisi\xF3n en vivo",
  "playing live": "reproduciendo en vivo",
  "seek back {seekOffset} seconds": "retroceder {seekOffset} segundos",
  "seek forward {seekOffset} seconds": "avanzar {seekOffset} segundos",
  "Network Error": "Error de red",
  "Decode Error": "Error de decodificaci\xF3n",
  "Source Not Supported": "Fuente no compatible",
  "Encryption Error": "Error de cifrado",
  "A network error caused the media download to fail.": "Un error de red caus\xF3 la falla en la descarga del contenido.",
  "A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.": "Un error de medios caus\xF3 la interrupci\xF3n de la reproducci\xF3n. El contenido podr\xEDa estar da\xF1ado o tu navegador no admite este formato.",
  "An unsupported error occurred. The server or network failed, or your browser does not support this format.": "Ocurri\xF3 un error de incompatibilidad. El servidor o la red fallaron, o tu navegador no admite este formato.",
  "The media is encrypted and there are no keys to decrypt it.": "El contenido est\xE1 cifrado y no hay claves disponibles para descifrarlo."
};
(0, import_i18n.addTranslation)("es", Es);
