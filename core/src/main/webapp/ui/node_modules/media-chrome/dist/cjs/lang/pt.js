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
var pt_exports = {};
__export(pt_exports, {
  Pt: () => Pt
});
module.exports = __toCommonJS(pt_exports);
var import_i18n = require("../utils/i18n.js");
const Pt = {
  "Start airplay": "Iniciar AirPlay",
  "Stop airplay": "Parar AirPlay",
  Audio: "\xC1udio",
  Captions: "Legendas",
  "Enable captions": "Ativar legendas",
  "Disable captions": "Desativar legendas",
  "Start casting": "Iniciar transmiss\xE3o",
  "Stop casting": "Parar transmiss\xE3o",
  "Enter fullscreen mode": "Entrar no modo de tela cheia",
  "Exit fullscreen mode": "Sair do modo de tela cheia",
  Mute: "Silenciar",
  Unmute: "Ativar som",
  "Enter picture in picture mode": "Entrar no modo PiP (Imagem na tela)",
  "Exit picture in picture mode": "Sair do modo PiP",
  Play: "Reproduzir",
  Pause: "Pausar",
  "Playback rate": "Taxa de reprodu\xE7\xE3o",
  "Playback rate {playbackRate}": "Taxa de reprodu\xE7\xE3o {playbackRate}",
  Quality: "Qualidade",
  "Seek backward": "Retroceder",
  "Seek forward": "Avan\xE7ar",
  Settings: "Configura\xE7\xF5es",
  Auto: "Auto",
  "audio player": "reprodutor de \xE1udio",
  "video player": "reprodutor de v\xEDdeo",
  volume: "volume",
  seek: "buscar",
  "closed captions": "legendas ocultas",
  "current playback rate": "taxa de reprodu\xE7\xE3o atual",
  "playback time": "tempo de reprodu\xE7\xE3o",
  "media loading": "carregando m\xEDdia",
  settings: "configura\xE7\xF5es",
  "audio tracks": "faixas de \xE1udio",
  quality: "qualidade",
  play: "reproduzir",
  pause: "pausar",
  mute: "silenciar",
  unmute: "ativar som",
  live: "ao vivo",
  Off: "Desativado",
  "start airplay": "iniciar AirPlay",
  "stop airplay": "parar AirPlay",
  "start casting": "iniciar transmiss\xE3o",
  "stop casting": "parar transmiss\xE3o",
  "enter fullscreen mode": "entrar no modo de tela cheia",
  "exit fullscreen mode": "sair do modo de tela cheia",
  "enter picture in picture mode": "entrar no modo PiP",
  "exit picture in picture mode": "sair do modo PiP",
  "seek to live": "buscar ao vivo",
  "playing live": "reproduzindo ao vivo",
  "seek back {seekOffset} seconds": "voltar {seekOffset} segundos",
  "seek forward {seekOffset} seconds": "avan\xE7ar {seekOffset} segundos",
  "Network Error": "Erro de rede",
  "Decode Error": "Erro de decodifica\xE7\xE3o",
  "Source Not Supported": "Fonte n\xE3o suportada",
  "Encryption Error": "Erro de criptografia",
  "A network error caused the media download to fail.": "Um erro de rede causou a falha no download do conte\xFAdo.",
  "A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.": "Um erro de m\xEDdia fez com que a reprodu\xE7\xE3o fosse interrompida. O conte\xFAdo pode estar corrompido ou seu navegador n\xE3o suporta este formato.",
  "An unsupported error occurred. The server or network failed, or your browser does not support this format.": "Ocorreu um erro de incompatibilidade. O servidor ou a rede falharam, ou seu navegador n\xE3o suporta este formato.",
  "The media is encrypted and there are no keys to decrypt it.": "O conte\xFAdo est\xE1 criptografado e n\xE3o h\xE1 chaves dispon\xEDveis para descriptograf\xE1-lo."
};
(0, import_i18n.addTranslation)("pt", Pt);
