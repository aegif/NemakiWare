import { MediaUIEvents } from '../constants.js';
import { StateMediator, StateOwners } from './state-mediator.js';
import { MediaState } from './media-store.js';
export type MediaUIEventsType = typeof MediaUIEvents[keyof typeof MediaUIEvents];
export type MediaRequestTypes = Exclude<MediaUIEventsType, 'registermediastatereceiver' | 'unregistermediastatereceiver' | 'mediashowtexttracksrequest' | 'mediahidetexttracksrequest'>;
/** @TODO Make this definition more precise (CJP) */
/**
 *
 * RequestMap provides a stateless, well-defined API for translating state change requests to related side effects to attempt to fulfill said request and
 * any other appropriate state changes that should occur as a result. Most often (but not always), those will simply rely on the StateMediator's `set()`
 * method for the corresponding state to update the StateOwners state. RequestMap is designed to be used by a MediaStore, which owns all of the wiring up
 * and persistence of e.g. StateOwners, MediaState, StateMediator, and the RequestMap.
 *
 * For any modeled state change request, the RequestMap defines a key, K, which directly maps to the state change request type (e.g. `mediapauserequest`, `mediaseekrequest`, etc.),
 * whose value is a function that defines the appropriate side effects of the request that will, under normal circumstances, (eventually) result in actual state changes.
 */
export type RequestMap = {
    [K in MediaRequestTypes]: (stateMediator: StateMediator, stateOwners: StateOwners, action: Partial<Pick<CustomEvent<any>, 'type' | 'detail'>>) => Partial<MediaState> | undefined | void;
};
export declare const requestMap: RequestMap;
