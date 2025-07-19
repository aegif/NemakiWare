import { AvailabilityStates, StreamTypes } from '../constants.js';
export type Rendition = {
    src?: string;
    id?: string;
    width?: number;
    height?: number;
    bitrate?: number;
    frameRate?: number;
    codec?: string;
    readonly selected?: boolean;
};
export type AudioTrack = {
    id?: string;
    kind?: string;
    label: string;
    language: string;
    enabled: boolean;
};
/**
 *
 * MediaStateOwner is in a sense both a subset and a superset of `HTMLVideoElement` and is used as the primary
 * "source of truth" for media state, as well as the primary target for state change requests.
 *
 * It is a subset insofar as only the `play()` method, the `paused` property, and the `addEventListener()`/`removeEventListener()` methods
 * are *required* and required to conform to their definition of `HTMLMediaElement` on the entity used. All other interfaces
 * (properties, methods, events, etc.) are optional, but, when present, *must* conform to `HTMLMediaElement`/`HTMLVideoElement`
 * to avoid unexpected state behavior. This includes, for example, ensuring state updates occur *before* related events are fired
 * that are used to monitor for potential state changes.
 *
 * It is a superset insofar as it supports an extended interface for media state that may be browser-specific (e.g. `webkit`-prefixed
 * properties/methods) or are not immediately derivable from primary media state or other state owners. These include things like
 * `videoRenditions` for e.g. HTTP Adaptive Streaming media (such as HLS or MPEG-DASH), `audioTracks`, or `streamType`, which identifies
 * whether the media ("stream") is "live" or "on demand". Several of these are specified and formalized on https://github.com/video-dev/media-ui-extensions.
 */
export type MediaStateOwner = Partial<HTMLVideoElement> & Pick<HTMLMediaElement, 'play' | 'paused' | 'addEventListener' | 'removeEventListener'> & {
    streamType?: StreamTypes;
    targetLiveWindow?: number;
    liveEdgeStart?: number;
    videoRenditions?: Rendition[] & EventTarget & {
        selectedIndex?: number;
    };
    audioTracks?: AudioTrack[] & EventTarget;
    requestCast?: () => any;
    webkitDisplayingFullscreen?: boolean;
    webkitPresentationMode?: 'fullscreen' | 'picture-in-picture';
    webkitEnterFullscreen?: () => any;
    webkitCurrentPlaybackTargetIsWireless?: boolean;
    webkitShowPlaybackTargetPicker?: () => any;
};
export type RootNodeStateOwner = Partial<Document | ShadowRoot>;
export type FullScreenElementStateOwner = Partial<HTMLElement> & EventTarget;
export type StateOption = {
    defaultSubtitles?: boolean;
    defaultStreamType?: StreamTypes;
    defaultDuration?: number;
    liveEdgeOffset?: number;
    seekToLiveOffset?: number;
    noAutoSeekToLive?: boolean;
    noVolumePref?: boolean;
    noMutedPref?: boolean;
    noSubtitlesLangPref?: boolean;
};
/**
 *
 * StateOwners are anything considered a source of truth or a target for updates for state. The media element (or "element") is a source of truth for the state of media playback,
 * but other things could also be a source of truth for information about the media. These include:
 *
 * - media - the media element
 * - fullscreenElement - the element that will be used when in full screen (e.g. for Media Chrome, this will typically be the MediaController)
 * - documentElement - top level node for DOM context (usually document and defaults to `document` in `createMediaStore()`)
 * - options - state behavior/user preferences (e.g. defaultSubtitles to enable subtitles by default as the relevant state or state owners change)
 */
export type StateOwners = {
    media?: MediaStateOwner;
    documentElement?: RootNodeStateOwner;
    fullscreenElement?: FullScreenElementStateOwner;
    options?: StateOption;
};
export type EventOrAction<D = undefined> = {
    type: string;
    detail?: D;
    target?: EventTarget;
};
export type FacadeGetter<T, D = T> = (stateOwners: StateOwners, event?: EventOrAction<D>) => T;
export type FacadeSetter<T> = (value: T, stateOwners: StateOwners) => void;
export type StateOwnerUpdateHandler<T> = (handler: (value: T) => void, stateOwners: StateOwners) => void;
export type ReadonlyFacadeProp<T, D = T> = {
    get: FacadeGetter<T, D>;
    mediaEvents?: string[];
    textTracksEvents?: string[];
    videoRenditionsEvents?: string[];
    audioTracksEvents?: string[];
    remoteEvents?: string[];
    rootEvents?: string[];
    stateOwnersUpdateHandlers?: StateOwnerUpdateHandler<T>[];
};
export type FacadeProp<T, S = T, D = T> = ReadonlyFacadeProp<T, D> & {
    set: FacadeSetter<S>;
};
/**
 *
 * StateMediator provides a stateless, well-defined API for getting and setting/updating media-relevant state on a set of (stateful) StateOwners.
 * In addition, it identifies monitoring conditions for potential state changes for any given bit of state. StateMediator is designed to be used
 * by a MediaStore, which owns all of the wiring up and persistence of e.g. StateOwners, MediaState, and the StateMediator.
 *
 * For any modeled state, the StateMediator defines a key, K, which names the state (e.g. `mediaPaused`, `mediaSubtitlesShowing`, `mediaCastUnavailable`,
 * etc.), whose value defines the aforementioned using:
 *
 * - `get(stateOwners, event)` - Retrieves the current state of K from StateOwners, potentially using the (optional) event to help identify the state.
 * - `set(value, stateOwners)` (Optional, not available for `Readonly` state) - Interact with StateOwners via their interfaces to (directly or indirectly) update the state of K, using the value to determine the intended state change side effects.
 * - `mediaEvents[]` (Optional) - An array of event types to monitor on `stateOwners.media` for potential changes in the state of K.
 * - `textTracksEvents[]` (Optional) - An array of event types to monitor on `stateOwners.media.textTracks` for potential changes in the state of K.
 * - `videoRenditionsEvents[]` (Optional) - An array of event types to monitor on `stateOwners.media.videoRenditions` for potential changes in the state of K.
 * - `audioTracksEvents[]` (Optional) - An array of event types to monitor on `stateOwners.media.audioTracks` for potential changes in the state of K.
 * - `remoteEvents[]` (Optional) - An array of event types to monitor on `stateOwners.media.remote` for potential changes in the state of K.
 * - `rootEvents[]` (Optional) - An array of event types to monitor on `stateOwners.documentElement` for potential changes in the state of K.
 * - `stateOwnersUpdateHandlers[]` (Optional) - An array of functions that define arbitrary code for monitoring or causing state changes, optionally returning a "teardown" function for cleanup.
 *
 * @example &lt;caption>Basic Example (NOTE: This is for informative use only. StateMediator is not intended to be used directly).&lt;/caption>
 *
 * // Simple stateOwners example
 * const stateOwners = {
 *   media: myVideoElement,
 *   fullscreenElement: myMediaUIContainerElement,
 *   documentElement: document,
 * };
 *
 * // Current mediaPaused state
 * let mediaPaused = stateMediator.mediaPaused.get(stateOwners);
 *
 * // Event handler to update mediaPaused to its latest state;
 * const updateMediaPausedEventHandler = (event) => {
 *   mediaPaused = stateMediator.mediaPaused.get(stateOwners, event);
 * };
 *
 * // Monitor for potential changes to mediaPaused state.
 * stateMediator.mediaPaused.mediaEvents.forEach(eventType => {
 *   stateOwners.media.addEventListener(eventType, updateMediaPausedEventHandler);
 * });
 *
 * // Function to toggle between mediaPaused and !mediaPaused (media "unpaused", or "playing" under normal conditions)
 * const toggleMediaPaused = () => {
 *   const nextMediaPaused = !mediaPaused;
 *   stateMediator.mediaPaused.set(nextMediaPaused, stateOwners);
 * };
 *
 *
 * // ... Eventual teardown, when relevant. This is especially relevant for potential garbage collection/memory management considerations.
 * stateMediator.mediaPaused.mediaEvents.forEach(eventType => {
 *   stateOwners.media.removeEventListener(eventType, updateMediaPausedEventHandler);
 * });
 *
 */
export type StateMediator = {
    mediaErrorCode: ReadonlyFacadeProp<MediaError['code']>;
    mediaErrorMessage: ReadonlyFacadeProp<MediaError['message']>;
    mediaError: ReadonlyFacadeProp<MediaError>;
    mediaWidth: ReadonlyFacadeProp<number>;
    mediaHeight: ReadonlyFacadeProp<number>;
    mediaPaused: FacadeProp<HTMLMediaElement['paused']>;
    mediaHasPlayed: ReadonlyFacadeProp<boolean>;
    mediaEnded: ReadonlyFacadeProp<HTMLMediaElement['ended']>;
    mediaPlaybackRate: FacadeProp<HTMLMediaElement['playbackRate']>;
    mediaMuted: FacadeProp<HTMLMediaElement['muted']>;
    mediaVolume: FacadeProp<HTMLMediaElement['volume']>;
    mediaVolumeLevel: ReadonlyFacadeProp<'high' | 'medium' | 'low' | 'off'>;
    mediaCurrentTime: FacadeProp<HTMLMediaElement['currentTime']>;
    mediaDuration: ReadonlyFacadeProp<HTMLMediaElement['duration']>;
    mediaLoading: ReadonlyFacadeProp<boolean>;
    mediaSeekable: ReadonlyFacadeProp<[number, number] | undefined>;
    mediaBuffered: ReadonlyFacadeProp<[number, number][]>;
    mediaStreamType: ReadonlyFacadeProp<StreamTypes>;
    mediaTargetLiveWindow: ReadonlyFacadeProp<number>;
    mediaTimeIsLive: ReadonlyFacadeProp<boolean>;
    mediaSubtitlesList: ReadonlyFacadeProp<Pick<TextTrack, 'kind' | 'label' | 'language'>[]>;
    mediaSubtitlesShowing: ReadonlyFacadeProp<Pick<TextTrack, 'kind' | 'label' | 'language'>[]>;
    mediaChaptersCues: ReadonlyFacadeProp<Pick<VTTCue, 'text' | 'startTime' | 'endTime'>[]>;
    mediaIsPip: FacadeProp<boolean>;
    mediaRenditionList: ReadonlyFacadeProp<Rendition[]>;
    mediaRenditionSelected: FacadeProp<string, string>;
    mediaAudioTrackList: ReadonlyFacadeProp<{
        id?: string;
    }[]>;
    mediaAudioTrackEnabled: FacadeProp<string, string>;
    mediaIsFullscreen: FacadeProp<boolean>;
    mediaIsCasting: FacadeProp<boolean, boolean, 'NO_DEVICES_AVAILABLE' | 'NOT_CONNECTED' | 'CONNECTING' | 'CONNECTED'>;
    mediaIsAirplaying: FacadeProp<boolean>;
    mediaFullscreenUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaPipUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaVolumeUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaCastUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaAirplayUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaRenditionUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
    mediaAudioTrackUnavailable: ReadonlyFacadeProp<AvailabilityStates | undefined>;
};
export declare const volumeSupportPromise: Promise<boolean>;
export declare const prepareStateOwners: (
/** @type {(StateOwners[keyof StateOwners])[]} */ ...stateOwners: any[]) => Promise<void>;
export declare const stateMediator: StateMediator;
