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
var labels_exports = {};
__export(labels_exports, {
  formatError: () => formatError
});
module.exports = __toCommonJS(labels_exports);
var import_i18n = require("../utils/i18n.js");
const defaultErrorTitles = {
  2: (0, import_i18n.t)("Network Error"),
  3: (0, import_i18n.t)("Decode Error"),
  4: (0, import_i18n.t)("Source Not Supported"),
  5: (0, import_i18n.t)("Encryption Error")
};
const defaultErrorMessages = {
  2: (0, import_i18n.t)("A network error caused the media download to fail."),
  3: (0, import_i18n.t)(
    "A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format."
  ),
  4: (0, import_i18n.t)(
    "An unsupported error occurred. The server or network failed, or your browser does not support this format."
  ),
  5: (0, import_i18n.t)("The media is encrypted and there are no keys to decrypt it.")
};
const formatError = (error) => {
  var _a, _b;
  if (error.code === 1)
    return null;
  return {
    title: (_a = defaultErrorTitles[error.code]) != null ? _a : `Error ${error.code}`,
    message: (_b = defaultErrorMessages[error.code]) != null ? _b : error.message
  };
};
