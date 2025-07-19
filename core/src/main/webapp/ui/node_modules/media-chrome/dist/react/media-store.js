import React, { createContext, useContext, useEffect, useMemo } from "react";
import {
  AvailabilityStates,
  MediaUIEvents,
  MediaUIProps,
  StreamTypes,
  VolumeLevels
} from "../constants.js";
import {
  createMediaStore
} from "../media-store/media-store.js";
import { useSyncExternalStoreWithSelector } from "./useSyncExternalStoreWithSelector.js";
import * as timeUtils from "../utils/time.js";
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
} = MediaUIEvents;
const MediaActionTypes = {
  ...StateChangeRequests,
  MEDIA_ELEMENT_CHANGE_REQUEST: "mediaelementchangerequest",
  FULLSCREEN_ELEMENT_CHANGE_REQUEST: "fullscreenelementchangerequest"
};
const MediaStateNames = { ...MediaUIProps };
const identity = (x) => x;
const MediaContext = createContext(null);
const MediaProvider = ({
  children,
  mediaStore
}) => {
  const value = useMemo(
    () => mediaStore != null ? mediaStore : createMediaStore({ documentElement: globalThis.document }),
    [mediaStore]
  );
  useEffect(() => {
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
  return /* @__PURE__ */ React.createElement(MediaContext.Provider, { value }, children);
};
const useMediaStore = () => {
  const store = useContext(MediaContext);
  return store;
};
const useMediaDispatch = () => {
  var _a;
  const store = useContext(MediaContext);
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
  const store = useContext(MediaContext);
  const selectedState = useSyncExternalStoreWithSelector(
    (_a = store == null ? void 0 : store.subscribe) != null ? _a : identity,
    (_b = store == null ? void 0 : store.getState) != null ? _b : identity,
    (_c = store == null ? void 0 : store.getState) != null ? _c : identity,
    selector,
    equalityFn
  );
  return selectedState;
};
export {
  AvailabilityStates,
  MediaActionTypes,
  MediaContext,
  MediaProvider,
  MediaStateNames,
  StreamTypes,
  VolumeLevels,
  timeUtils,
  useMediaDispatch,
  useMediaFullscreenRef,
  useMediaRef,
  useMediaSelector,
  useMediaStore
};
