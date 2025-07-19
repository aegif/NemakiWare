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
var fullscreen_api_exports = {};
__export(fullscreen_api_exports, {
  enterFullscreen: () => enterFullscreen,
  exitFullscreen: () => exitFullscreen,
  getFullscreenElement: () => getFullscreenElement,
  isFullscreen: () => isFullscreen,
  isFullscreenEnabled: () => isFullscreenEnabled
});
module.exports = __toCommonJS(fullscreen_api_exports);
var import_constants = require("../constants.js");
var import_element_utils = require("./element-utils.js");
var import_server_safe_globals = require("./server-safe-globals.js");
const enterFullscreen = (stateOwners) => {
  var _a;
  const { media, fullscreenElement } = stateOwners;
  try {
    const enterFullscreenKey = fullscreenElement && "requestFullscreen" in fullscreenElement ? "requestFullscreen" : fullscreenElement && "webkitRequestFullScreen" in fullscreenElement ? "webkitRequestFullScreen" : void 0;
    if (enterFullscreenKey) {
      const maybePromise = (_a = fullscreenElement[enterFullscreenKey]) == null ? void 0 : _a.call(fullscreenElement);
      if (maybePromise instanceof Promise) {
        return maybePromise.catch(() => {
        });
      }
    } else if (media == null ? void 0 : media.webkitEnterFullscreen) {
      media.webkitEnterFullscreen();
    } else if (media == null ? void 0 : media.requestFullscreen) {
      media.requestFullscreen();
    }
  } catch (e) {
    console.error(e);
  }
};
const exitFullscreenKey = "exitFullscreen" in import_server_safe_globals.document ? "exitFullscreen" : "webkitExitFullscreen" in import_server_safe_globals.document ? "webkitExitFullscreen" : "webkitCancelFullScreen" in import_server_safe_globals.document ? "webkitCancelFullScreen" : void 0;
const exitFullscreen = (stateOwners) => {
  var _a;
  const { documentElement } = stateOwners;
  if (exitFullscreenKey) {
    const maybePromise = (_a = documentElement == null ? void 0 : documentElement[exitFullscreenKey]) == null ? void 0 : _a.call(documentElement);
    if (maybePromise instanceof Promise) {
      return maybePromise.catch(() => {
      });
    }
  }
};
const fullscreenElementKey = "fullscreenElement" in import_server_safe_globals.document ? "fullscreenElement" : "webkitFullscreenElement" in import_server_safe_globals.document ? "webkitFullscreenElement" : void 0;
const getFullscreenElement = (stateOwners) => {
  const { documentElement, media } = stateOwners;
  const docFullscreenElement = documentElement == null ? void 0 : documentElement[fullscreenElementKey];
  if (!docFullscreenElement && "webkitDisplayingFullscreen" in media && "webkitPresentationMode" in media && media.webkitDisplayingFullscreen && media.webkitPresentationMode === import_constants.WebkitPresentationModes.FULLSCREEN) {
    return media;
  }
  return docFullscreenElement;
};
const isFullscreen = (stateOwners) => {
  var _a;
  const { media, documentElement, fullscreenElement = media } = stateOwners;
  if (!media || !documentElement)
    return false;
  const currentFullscreenElement = getFullscreenElement(stateOwners);
  if (!currentFullscreenElement)
    return false;
  if (currentFullscreenElement === fullscreenElement || currentFullscreenElement === media) {
    return true;
  }
  if (currentFullscreenElement.localName.includes("-")) {
    let currentRoot = currentFullscreenElement.shadowRoot;
    if (!(fullscreenElementKey in currentRoot)) {
      return (0, import_element_utils.containsComposedNode)(
        currentFullscreenElement,
        /** @TODO clean up type assumptions (e.g. Node) (CJP) */
        // @ts-ignore
        fullscreenElement
      );
    }
    while (currentRoot == null ? void 0 : currentRoot[fullscreenElementKey]) {
      if (currentRoot[fullscreenElementKey] === fullscreenElement)
        return true;
      currentRoot = (_a = currentRoot[fullscreenElementKey]) == null ? void 0 : _a.shadowRoot;
    }
  }
  return false;
};
const fullscreenEnabledKey = "fullscreenEnabled" in import_server_safe_globals.document ? "fullscreenEnabled" : "webkitFullscreenEnabled" in import_server_safe_globals.document ? "webkitFullscreenEnabled" : void 0;
const isFullscreenEnabled = (stateOwners) => {
  const { documentElement, media } = stateOwners;
  return !!(documentElement == null ? void 0 : documentElement[fullscreenEnabledKey]) || media && "webkitSupportsFullscreen" in media;
};
