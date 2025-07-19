import { WebkitPresentationModes } from "../constants.js";
import { containsComposedNode } from "./element-utils.js";
import { document } from "./server-safe-globals.js";
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
const exitFullscreenKey = "exitFullscreen" in document ? "exitFullscreen" : "webkitExitFullscreen" in document ? "webkitExitFullscreen" : "webkitCancelFullScreen" in document ? "webkitCancelFullScreen" : void 0;
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
const fullscreenElementKey = "fullscreenElement" in document ? "fullscreenElement" : "webkitFullscreenElement" in document ? "webkitFullscreenElement" : void 0;
const getFullscreenElement = (stateOwners) => {
  const { documentElement, media } = stateOwners;
  const docFullscreenElement = documentElement == null ? void 0 : documentElement[fullscreenElementKey];
  if (!docFullscreenElement && "webkitDisplayingFullscreen" in media && "webkitPresentationMode" in media && media.webkitDisplayingFullscreen && media.webkitPresentationMode === WebkitPresentationModes.FULLSCREEN) {
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
      return containsComposedNode(
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
const fullscreenEnabledKey = "fullscreenEnabled" in document ? "fullscreenEnabled" : "webkitFullscreenEnabled" in document ? "webkitFullscreenEnabled" : void 0;
const isFullscreenEnabled = (stateOwners) => {
  const { documentElement, media } = stateOwners;
  return !!(documentElement == null ? void 0 : documentElement[fullscreenEnabledKey]) || media && "webkitSupportsFullscreen" in media;
};
export {
  enterFullscreen,
  exitFullscreen,
  getFullscreenElement,
  isFullscreen,
  isFullscreenEnabled
};
