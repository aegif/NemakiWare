import {
  stateMediator as defaultStateMediator,
  prepareStateOwners
} from "./state-mediator.js";
import { areValuesEq } from "./util.js";
import { requestMap as defaultRequestMap } from "./request-map.js";
const createMediaStore = ({
  media,
  fullscreenElement,
  documentElement,
  stateMediator = defaultStateMediator,
  requestMap = defaultRequestMap,
  options = {},
  monitorStateOwnersOnlyWithSubscriptions = true
}) => {
  const callbacks = [];
  const stateOwners = {
    // Spreading options here since folks should not rely on holding onto references
    // for any app-level logic wrt options.
    options: { ...options }
  };
  let state = Object.freeze({
    mediaPreviewTime: void 0,
    mediaPreviewImage: void 0,
    mediaPreviewCoords: void 0,
    mediaPreviewChapter: void 0
  });
  const updateState = (nextStateDelta) => {
    if (nextStateDelta == void 0)
      return;
    if (areValuesEq(nextStateDelta, state)) {
      return;
    }
    state = Object.freeze({
      ...state,
      ...nextStateDelta
    });
    callbacks.forEach((cb) => cb(state));
  };
  const updateStateFromFacade = () => {
    const nextState = Object.entries(stateMediator).reduce(
      (nextState2, [stateName, { get }]) => {
        nextState2[stateName] = get(stateOwners);
        return nextState2;
      },
      {}
    );
    updateState(nextState);
  };
  const stateUpdateHandlers = {};
  let nextStateOwners = void 0;
  const updateStateOwners = async (nextStateOwnersDelta, nextSubscriberCount) => {
    var _a, _b, _c, _d, _e, _f, _g, _h, _i, _j, _k, _l, _m, _n, _o, _p;
    const pendingUpdate = !!nextStateOwners;
    nextStateOwners = {
      ...stateOwners,
      ...nextStateOwners != null ? nextStateOwners : {},
      ...nextStateOwnersDelta
    };
    if (pendingUpdate)
      return;
    await prepareStateOwners(...Object.values(nextStateOwnersDelta));
    const shouldTeardownFromSubscriberCount = callbacks.length > 0 && nextSubscriberCount === 0 && monitorStateOwnersOnlyWithSubscriptions;
    const mediaChanged = stateOwners.media !== nextStateOwners.media;
    const textTracksChanged = ((_a = stateOwners.media) == null ? void 0 : _a.textTracks) !== ((_b = nextStateOwners.media) == null ? void 0 : _b.textTracks);
    const videoRenditionsChanged = ((_c = stateOwners.media) == null ? void 0 : _c.videoRenditions) !== ((_d = nextStateOwners.media) == null ? void 0 : _d.videoRenditions);
    const audioTracksChanged = ((_e = stateOwners.media) == null ? void 0 : _e.audioTracks) !== ((_f = nextStateOwners.media) == null ? void 0 : _f.audioTracks);
    const remoteChanged = ((_g = stateOwners.media) == null ? void 0 : _g.remote) !== ((_h = nextStateOwners.media) == null ? void 0 : _h.remote);
    const rootNodeChanged = stateOwners.documentElement !== nextStateOwners.documentElement;
    const teardownMedia = !!stateOwners.media && (mediaChanged || shouldTeardownFromSubscriberCount);
    const teardownTextTracks = !!((_i = stateOwners.media) == null ? void 0 : _i.textTracks) && (textTracksChanged || shouldTeardownFromSubscriberCount);
    const teardownVideoRenditions = !!((_j = stateOwners.media) == null ? void 0 : _j.videoRenditions) && (videoRenditionsChanged || shouldTeardownFromSubscriberCount);
    const teardownAudioTracks = !!((_k = stateOwners.media) == null ? void 0 : _k.audioTracks) && (audioTracksChanged || shouldTeardownFromSubscriberCount);
    const teardownRemote = !!((_l = stateOwners.media) == null ? void 0 : _l.remote) && (remoteChanged || shouldTeardownFromSubscriberCount);
    const teardownRootNode = !!stateOwners.documentElement && (rootNodeChanged || shouldTeardownFromSubscriberCount);
    const teardownSomething = teardownMedia || teardownTextTracks || teardownVideoRenditions || teardownAudioTracks || teardownRemote || teardownRootNode;
    const shouldSetupFromSubscriberCount = callbacks.length === 0 && nextSubscriberCount === 1 && monitorStateOwnersOnlyWithSubscriptions;
    const setupMedia = !!nextStateOwners.media && (mediaChanged || shouldSetupFromSubscriberCount);
    const setupTextTracks = !!((_m = nextStateOwners.media) == null ? void 0 : _m.textTracks) && (textTracksChanged || shouldSetupFromSubscriberCount);
    const setupVideoRenditions = !!((_n = nextStateOwners.media) == null ? void 0 : _n.videoRenditions) && (videoRenditionsChanged || shouldSetupFromSubscriberCount);
    const setupAudioTracks = !!((_o = nextStateOwners.media) == null ? void 0 : _o.audioTracks) && (audioTracksChanged || shouldSetupFromSubscriberCount);
    const setupRemote = !!((_p = nextStateOwners.media) == null ? void 0 : _p.remote) && (remoteChanged || shouldSetupFromSubscriberCount);
    const setupRootNode = !!nextStateOwners.documentElement && (rootNodeChanged || shouldSetupFromSubscriberCount);
    const setupSomething = setupMedia || setupTextTracks || setupVideoRenditions || setupAudioTracks || setupRemote || setupRootNode;
    const somethingToDo = teardownSomething || setupSomething;
    if (!somethingToDo) {
      Object.entries(nextStateOwners).forEach(
        ([stateOwnerName, stateOwner]) => {
          stateOwners[stateOwnerName] = stateOwner;
        }
      );
      updateStateFromFacade();
      nextStateOwners = void 0;
      return;
    }
    Object.entries(stateMediator).forEach(
      ([
        stateName,
        {
          get,
          mediaEvents = [],
          textTracksEvents = [],
          videoRenditionsEvents = [],
          audioTracksEvents = [],
          remoteEvents = [],
          rootEvents = [],
          stateOwnersUpdateHandlers = []
        }
      ]) => {
        if (!stateUpdateHandlers[stateName]) {
          stateUpdateHandlers[stateName] = {};
        }
        const handler = (event) => {
          const nextValue = get(stateOwners, event);
          updateState({ [stateName]: nextValue });
        };
        let prevHandler;
        prevHandler = stateUpdateHandlers[stateName].mediaEvents;
        mediaEvents.forEach((eventType) => {
          if (prevHandler && teardownMedia) {
            stateOwners.media.removeEventListener(eventType, prevHandler);
            stateUpdateHandlers[stateName].mediaEvents = void 0;
          }
          if (setupMedia) {
            nextStateOwners.media.addEventListener(eventType, handler);
            stateUpdateHandlers[stateName].mediaEvents = handler;
          }
        });
        prevHandler = stateUpdateHandlers[stateName].textTracksEvents;
        textTracksEvents.forEach((eventType) => {
          var _a2, _b2;
          if (prevHandler && teardownTextTracks) {
            (_a2 = stateOwners.media.textTracks) == null ? void 0 : _a2.removeEventListener(
              eventType,
              prevHandler
            );
            stateUpdateHandlers[stateName].textTracksEvents = void 0;
          }
          if (setupTextTracks) {
            (_b2 = nextStateOwners.media.textTracks) == null ? void 0 : _b2.addEventListener(
              eventType,
              handler
            );
            stateUpdateHandlers[stateName].textTracksEvents = handler;
          }
        });
        prevHandler = stateUpdateHandlers[stateName].videoRenditionsEvents;
        videoRenditionsEvents.forEach((eventType) => {
          var _a2, _b2;
          if (prevHandler && teardownVideoRenditions) {
            (_a2 = stateOwners.media.videoRenditions) == null ? void 0 : _a2.removeEventListener(
              eventType,
              prevHandler
            );
            stateUpdateHandlers[stateName].videoRenditionsEvents = void 0;
          }
          if (setupVideoRenditions) {
            (_b2 = nextStateOwners.media.videoRenditions) == null ? void 0 : _b2.addEventListener(
              eventType,
              handler
            );
            stateUpdateHandlers[stateName].videoRenditionsEvents = handler;
          }
        });
        prevHandler = stateUpdateHandlers[stateName].audioTracksEvents;
        audioTracksEvents.forEach((eventType) => {
          var _a2, _b2;
          if (prevHandler && teardownAudioTracks) {
            (_a2 = stateOwners.media.audioTracks) == null ? void 0 : _a2.removeEventListener(
              eventType,
              prevHandler
            );
            stateUpdateHandlers[stateName].audioTracksEvents = void 0;
          }
          if (setupAudioTracks) {
            (_b2 = nextStateOwners.media.audioTracks) == null ? void 0 : _b2.addEventListener(
              eventType,
              handler
            );
            stateUpdateHandlers[stateName].audioTracksEvents = handler;
          }
        });
        prevHandler = stateUpdateHandlers[stateName].remoteEvents;
        remoteEvents.forEach((eventType) => {
          var _a2, _b2;
          if (prevHandler && teardownRemote) {
            (_a2 = stateOwners.media.remote) == null ? void 0 : _a2.removeEventListener(
              eventType,
              prevHandler
            );
            stateUpdateHandlers[stateName].remoteEvents = void 0;
          }
          if (setupRemote) {
            (_b2 = nextStateOwners.media.remote) == null ? void 0 : _b2.addEventListener(eventType, handler);
            stateUpdateHandlers[stateName].remoteEvents = handler;
          }
        });
        prevHandler = stateUpdateHandlers[stateName].rootEvents;
        rootEvents.forEach((eventType) => {
          if (prevHandler && teardownRootNode) {
            stateOwners.documentElement.removeEventListener(
              eventType,
              prevHandler
            );
            stateUpdateHandlers[stateName].rootEvents = void 0;
          }
          if (setupRootNode) {
            nextStateOwners.documentElement.addEventListener(
              eventType,
              handler
            );
            stateUpdateHandlers[stateName].rootEvents = handler;
          }
        });
        const prevHandlerTeardown = stateUpdateHandlers[stateName].stateOwnersUpdateHandlers;
        stateOwnersUpdateHandlers.forEach((fn) => {
          if (prevHandlerTeardown && teardownSomething) {
            prevHandlerTeardown();
          }
          if (setupSomething) {
            stateUpdateHandlers[stateName].stateOwnersUpdateHandlers = fn(
              handler,
              nextStateOwners
            );
          }
        });
      }
    );
    Object.entries(nextStateOwners).forEach(([stateOwnerName, stateOwner]) => {
      stateOwners[stateOwnerName] = stateOwner;
    });
    updateStateFromFacade();
    nextStateOwners = void 0;
  };
  updateStateOwners({ media, fullscreenElement, documentElement, options });
  return {
    // note that none of these cases directly interact with the media element, root node, full screen element, etc.
    // note these "actions" could just be the events if we wanted, especially if we normalize on "detail" for
    // any payload-relevant values
    // This is roughly equivalent to our used to be in our state requests dictionary object, though much of the
    // "heavy lifting" is now moved into the facade `set()`
    dispatch(action) {
      const { type, detail } = action;
      if (requestMap[type] && state.mediaErrorCode == null) {
        updateState(requestMap[type](stateMediator, stateOwners, action));
        return;
      }
      if (type === "mediaelementchangerequest") {
        updateStateOwners({ media: detail });
      } else if (type === "fullscreenelementchangerequest") {
        updateStateOwners({ fullscreenElement: detail });
      } else if (type === "documentelementchangerequest") {
        updateStateOwners({ documentElement: detail });
      } else if (type === "optionschangerequest") {
        Object.entries(detail != null ? detail : {}).forEach(([optionName, optionValue]) => {
          stateOwners.options[optionName] = optionValue;
        });
      }
    },
    getState() {
      return state;
    },
    subscribe(callback) {
      updateStateOwners({}, callbacks.length + 1);
      callbacks.push(callback);
      callback(state);
      return () => {
        const idx = callbacks.indexOf(callback);
        if (idx >= 0) {
          updateStateOwners({}, callbacks.length - 1);
          callbacks.splice(idx, 1);
        }
      };
    }
  };
};
var media_store_default = createMediaStore;
export {
  createMediaStore,
  media_store_default as default
};
