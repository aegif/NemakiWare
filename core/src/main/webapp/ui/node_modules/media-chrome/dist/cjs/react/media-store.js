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
var media_store_exports = {};
__export(media_store_exports, {
  AvailabilityStates: () => import_constants.AvailabilityStates,
  MediaActionTypes: () => MediaActionTypes,
  MediaContext: () => MediaContext,
  MediaProvider: () => MediaProvider,
  MediaStateNames: () => MediaStateNames,
  StreamTypes: () => import_constants.StreamTypes,
  VolumeLevels: () => import_constants.VolumeLevels,
  timeUtils: () => timeUtils,
  useMediaDispatch: () => useMediaDispatch,
  useMediaFullscreenRef: () => useMediaFullscreenRef,
  useMediaRef: () => useMediaRef,
  useMediaSelector: () => useMediaSelector,
  useMediaStore: () => useMediaStore
});
module.exports = __toCommonJS(media_store_exports);
var import_react = __toESM(require("react"), 1);
var import_constants = require("../constants.js");
var import_media_store = require("../media-store/media-store.js");
var import_useSyncExternalStoreWithSelector = require("./useSyncExternalStoreWithSelector.js");
var timeUtils = __toESM(require("../utils/time.js"), 1);
const {
  REGISTER_MEDIA_STATE_RECEIVER,
  // eslint-disable-line
  UNREGISTER_MEDIA_STATE_RECEIVER,
  // eslint-disable-line
  // NOTE: These generic state change requests are not currently supported (CJP)
  MEDIA_SHOW_TEXT_TRACKS_REQUEST,
  // eslint-disable-line
  MEDIA_HIDE_TEXT_TRACKS_REQUEST,
  // eslint-disable-line
  ...StateChangeRequests
} = import_constants.MediaUIEvents;
const MediaActionTypes = {
  ...StateChangeRequests,
  MEDIA_ELEMENT_CHANGE_REQUEST: "mediaelementchangerequest",
  FULLSCREEN_ELEMENT_CHANGE_REQUEST: "fullscreenelementchangerequest"
};
const MediaStateNames = { ...import_constants.MediaUIProps };
const identity = (x) => x;
const MediaContext = (0, import_react.createContext)(null);
const MediaProvider = ({
  children,
  mediaStore
}) => {
  const value = (0, import_react.useMemo)(
    () => mediaStore != null ? mediaStore : (0, import_media_store.createMediaStore)({ documentElement: globalThis.document }),
    [mediaStore]
  );
  (0, import_react.useEffect)(() => {
    value == null ? void 0 : value.dispatch({
      type: "documentelementchangerequest",
      detail: globalThis.document
    });
    return () => {
      value == null ? void 0 : value.dispatch({
        type: "documentelementchangerequest",
        detail: void 0
      });
    };
  }, []);
  return /* @__PURE__ */ import_react.default.createElement(MediaContext.Provider, { value }, children);
};
const useMediaStore = () => {
  const store = (0, import_react.useContext)(MediaContext);
  return store;
};
const useMediaDispatch = () => {
  var _a;
  const store = (0, import_react.useContext)(MediaContext);
  const dispatch = (_a = store == null ? void 0 : store.dispatch) != null ? _a : identity;
  return (value) => {
    return dispatch(value);
  };
};
const useMediaRef = () => {
  const dispatch = useMediaDispatch();
  return (mediaEl) => {
    dispatch({
      type: MediaActionTypes.MEDIA_ELEMENT_CHANGE_REQUEST,
      detail: mediaEl
    });
  };
};
const useMediaFullscreenRef = () => {
  const dispatch = useMediaDispatch();
  return (fullscreenEl) => {
    dispatch({
      type: MediaActionTypes.FULLSCREEN_ELEMENT_CHANGE_REQUEST,
      detail: fullscreenEl
    });
  };
};
const refEquality = (a, b) => a === b;
const useMediaSelector = (selector, equalityFn = refEquality) => {
  var _a, _b, _c;
  const store = (0, import_react.useContext)(MediaContext);
  const selectedState = (0, import_useSyncExternalStoreWithSelector.useSyncExternalStoreWithSelector)(
    (_a = store == null ? void 0 : store.subscribe) != null ? _a : identity,
    (_b = store == null ? void 0 : store.getState) != null ? _b : identity,
    (_c = store == null ? void 0 : store.getState) != null ? _c : identity,
    selector,
    equalityFn
  );
  return selectedState;
};
