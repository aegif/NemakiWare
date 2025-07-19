import { globalThis, document } from "./server-safe-globals.js";
import { delay } from "./utils.js";
import { isFullscreenEnabled } from "./fullscreen-api.js";
let testMediaEl;
const getTestMediaEl = () => {
  var _a, _b;
  if (testMediaEl)
    return testMediaEl;
  testMediaEl = (_b = (_a = document) == null ? void 0 : _a.createElement) == null ? void 0 : _b.call(_a, "video");
  return testMediaEl;
};
const hasVolumeSupportAsync = async (mediaEl = getTestMediaEl()) => {
  if (!mediaEl)
    return false;
  const prevVolume = mediaEl.volume;
  mediaEl.volume = prevVolume / 2 + 0.1;
  const abortController = new AbortController();
  const volumeSupported = await Promise.race([
    dispatchedVolumeChange(mediaEl, abortController.signal),
    volumeChanged(mediaEl, prevVolume)
  ]);
  abortController.abort();
  return volumeSupported;
};
const dispatchedVolumeChange = (mediaEl, signal) => {
  return new Promise((resolve) => {
    mediaEl.addEventListener("volumechange", () => resolve(true), { signal });
  });
};
const volumeChanged = async (mediaEl, prevVolume) => {
  for (let i = 0; i < 10; i++) {
    if (mediaEl.volume === prevVolume)
      return false;
    await delay(10);
  }
  return mediaEl.volume !== prevVolume;
};
const isSafari = /.*Version\/.*Safari\/.*/.test(
  globalThis.navigator.userAgent
);
const hasPipSupport = (mediaEl = getTestMediaEl()) => {
  if (globalThis.matchMedia("(display-mode: standalone)").matches && isSafari)
    return false;
  return typeof (mediaEl == null ? void 0 : mediaEl.requestPictureInPicture) === "function";
};
const hasFullscreenSupport = (mediaEl = getTestMediaEl()) => {
  return isFullscreenEnabled({ documentElement: document, media: mediaEl });
};
const fullscreenSupported = hasFullscreenSupport();
const pipSupported = hasPipSupport();
const airplaySupported = !!globalThis.WebKitPlaybackTargetAvailabilityEvent;
const castSupported = !!globalThis.chrome;
export {
  airplaySupported,
  castSupported,
  fullscreenSupported,
  getTestMediaEl,
  hasFullscreenSupport,
  hasPipSupport,
  hasVolumeSupportAsync,
  pipSupported
};
