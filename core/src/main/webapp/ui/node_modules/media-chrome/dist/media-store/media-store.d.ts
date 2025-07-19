/**
 *
 * MediaStore is a way to model media state (and changes to it) in a framework- and DOM-agnostic way. Like the difference between Redux
 * (the core state manager) and the Redux react wrapper, MediaStore provides the primitive for aggregating media state together in one place.
 *
 * It receives events as media state change requests (like `mediaplayrequest`) and keeps an internal representation of the complete media
 * state after they change, as opposed to querying the media state sources directly every time it needs to check what state something is in.
 *
 * It doesn't "know" how to update or query the StateOwners itself (like the media element). Rather, it relies on the StateMediator as an interface
 * for getting and setting state and relies on the RequestMap as an interface for translating state change requests to state updates (typically also
 * deferring to the StateMediator for setting state on the relevant StateOwners).
 *
 * Additionally, MediaStore state is not optimistically stored when a state change request is dispatched to it. It instead defers to the StateMediator,
 * waiting for events from the StateOwners before checking if the state actually changed and only then committing it to its internal representation of MediaState.
 *
 * @module media-store
 */
import { StateMediator, EventOrAction } from './state-mediator.js';
import { RequestMap } from './request-map.js';
/**
 * MediaState is a full representation of all media-related state modeled by the MediaStore and its StateMediator.
 * Instead of checking the StateOwners' state directly or on the fly, MediaStore keeps a "snapshot" of the latest
 * state, which will be provided to any MediaStore subscribers whenever the state changes, and is arbitrarily retrievable
 * from the MediaStore using `getState()`.
 */
export type MediaState = Readonly<{
    [K in keyof StateMediator]: ReturnType<StateMediator[K]['get']>;
}> & {
    mediaPreviewTime: number;
    mediaPreviewImage: string;
    mediaPreviewCoords: [string, string, string, string];
};
/**
 * MediaStore is the primary abstraction for managing and monitoring media state and other state relevant to the media UI
 * (for example, fullscreen behavior or the availability of media-related functionality for a particular browser or runtime, such as volume control or Airplay). This includes:
 * - Keeping track of any state changes (examples: Is the media muted? Is the currently playing media live or on demand? What audio tracks are available for the current media?)
 * - Sharing the latest state with any MediaStore subscribers whenever it changes
 * - Receiving and responding to requests to change the media or related state (examples: I would like the media to be unmuted. I want to start casting now. I want to switch from English subtitles to Japanese.)
 * - Wiring up and managing the relationships between media state, media state change requests, and the stateful entities that “own” the majority of this state (examples: the current media element being used, the current root node, the current fullscreen element)
 * - Respecting and monitoring changes in certain optional behaviors that impact state or state change requests (examples: I want subtitles/closed captions to be on by default whenever media with them are loaded. I want to disable keeping track of the user’s preferred volume level.)
 *
 * @example &lt;caption>Basic Usage.&lt;/caption>
 * const mediaStore = createStore({
 *   media: myVideoElement,
 *   fullscreenElement: myMediaUIContainerElement,
 *   // documentElement: advancedRootNodeCase // Will default to `document`
 *   options: {
 *     defaultSubtitles: true // enable subtitles/captions by default
 *   },
 * });
 *
 * // NOTE: In a more realistic example, `myToggleMutedButton` and `mySeekForwardButton` would likely keep track of/"own" its current state. See, e.g. the `<mute-button>` Media Chrome Web Component.
 * const unsubscribe = mediaStore.subscribe(state => {
 *   myToggleMutedButton.textContent = state.muted ? 'Unmute' : 'Mute';
 * });
 *
 * myToggleMutedButton.addEventListener('click', () => {
 *   const type = mediaStore.getState().muted ? 'mediaunmuterequest' : 'mediamuterequest'
 *   mediaStore.dispatch({ type });
 * });
 *
 * mySeekForwardButton.addEventListener('click', () => {
 *   mediaStore.dispatch({
 *     type: 'mediaseekrequest',
 *     // NOTE: For all of our state change requests that require additional information, we rely on the `detail` property so we can conform to `CustomEvent`, making interop easier.
 *     detail: mediaStore.getState().mediaCurrentTime + 15,
 *   });
 * });
 *
 * // If your code has cases where it swaps out the media element being used
 * mediaStore.dispatch({
 *   type: 'mediaelementchangerequest',
 *   detail: myAudioElement,
 * });
 *
 * // ... Eventual teardown, when relevant. This is especially relevant for potential garbage collection/memory management considerations.
 * unsubscribe();
 *
 */
export type MediaStore = {
    /**
     * A method that expects an "Action" or "Event".Primarily used to make state change requests.
     */
    dispatch(eventOrAction: EventOrAction<any>): void;
    /**
     *  A method to get the current state of the MediaStore
     */
    getState(): Partial<MediaState>;
    /**
     * A method to "subscribe" to the MediaStore. A subscriber is just a callback function that is invoked with the current state whenever the MediaStore's state changes. The method returns an "unsubscribe" function, which should be used to tell the MediaStore to remove the corresponding subscriber.
     */
    subscribe(handler: (state: Partial<MediaState>) => void): () => void;
};
type MediaStoreConfig = {
    media?: any;
    fullscreenElement?: any;
    documentElement?: any;
    stateMediator?: StateMediator;
    requestMap?: RequestMap;
    options?: any;
    monitorStateOwnersOnlyWithSubscriptions?: boolean;
};
/**
 * A factory for creating a `MediaStore` instance.
 * @param mediaStoreConfig - Configuration object for the `MediaStore`.
 */
export declare const createMediaStore: ({ media, fullscreenElement, documentElement, stateMediator, requestMap, options, monitorStateOwnersOnlyWithSubscriptions, }: MediaStoreConfig) => MediaStore;
export default createMediaStore;
