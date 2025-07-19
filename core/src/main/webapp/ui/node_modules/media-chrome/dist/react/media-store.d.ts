import type { Context, ReactNode } from 'react';
import React from 'react';
import { AvailabilityStates, StreamTypes, VolumeLevels } from '../constants.js';
import { type MediaState, type MediaStore } from '../media-store/media-store.js';
import type { FullScreenElementStateOwner, MediaStateOwner } from '../media-store/state-mediator.js';
export * as timeUtils from '../utils/time.js';
/**
 * @description A lookup object for all well-defined action types that can be dispatched
 * to the `MediaStore`. As each action type name suggests, these all take the form of
 * "state change requests," where e.g. a component will `dispatch()` a request to change
 * some bit of media state, typically due to some user interaction.
 *
 * @example
 * import { useDispatch, MediaActionTypes } from 'media-chrome/react/media-store';
 *
 * const MyComponent = () => {
 *   const dispatch = useDispatch();
 *   return (
 *     <button
 *       onClick={() => dispatch({
 *         type: MediaActionTypes.MEDIA_PLAYBACK_RATE_REQUEST,
 *         detail: 2.0
 *       })}
 *     >
 *       Faster!
 *     </button>
 *   );
 * };
 *
 * @see {@link useMediaDispatch}
 */
export { MediaState };
export { AvailabilityStates, StreamTypes, VolumeLevels };
export declare const MediaActionTypes: {
    readonly MEDIA_ELEMENT_CHANGE_REQUEST: "mediaelementchangerequest";
    readonly FULLSCREEN_ELEMENT_CHANGE_REQUEST: "fullscreenelementchangerequest";
    readonly MEDIA_PLAY_REQUEST: "mediaplayrequest";
    readonly MEDIA_PAUSE_REQUEST: "mediapauserequest";
    readonly MEDIA_MUTE_REQUEST: "mediamuterequest";
    readonly MEDIA_UNMUTE_REQUEST: "mediaunmuterequest";
    readonly MEDIA_VOLUME_REQUEST: "mediavolumerequest";
    readonly MEDIA_SEEK_REQUEST: "mediaseekrequest";
    readonly MEDIA_AIRPLAY_REQUEST: "mediaairplayrequest";
    readonly MEDIA_ENTER_FULLSCREEN_REQUEST: "mediaenterfullscreenrequest";
    readonly MEDIA_EXIT_FULLSCREEN_REQUEST: "mediaexitfullscreenrequest";
    readonly MEDIA_PREVIEW_REQUEST: "mediapreviewrequest";
    readonly MEDIA_ENTER_PIP_REQUEST: "mediaenterpiprequest";
    readonly MEDIA_EXIT_PIP_REQUEST: "mediaexitpiprequest";
    readonly MEDIA_ENTER_CAST_REQUEST: "mediaentercastrequest";
    readonly MEDIA_EXIT_CAST_REQUEST: "mediaexitcastrequest";
    readonly MEDIA_SHOW_SUBTITLES_REQUEST: "mediashowsubtitlesrequest";
    readonly MEDIA_DISABLE_SUBTITLES_REQUEST: "mediadisablesubtitlesrequest";
    readonly MEDIA_TOGGLE_SUBTITLES_REQUEST: "mediatogglesubtitlesrequest";
    readonly MEDIA_PLAYBACK_RATE_REQUEST: "mediaplaybackraterequest";
    readonly MEDIA_RENDITION_REQUEST: "mediarenditionrequest";
    readonly MEDIA_AUDIO_TRACK_REQUEST: "mediaaudiotrackrequest";
    readonly MEDIA_SEEK_TO_LIVE_REQUEST: "mediaseektoliverequest";
};
export declare const MediaStateNames: {
    readonly MEDIA_AIRPLAY_UNAVAILABLE: "mediaAirplayUnavailable";
    readonly MEDIA_AUDIO_TRACK_ENABLED: "mediaAudioTrackEnabled";
    readonly MEDIA_AUDIO_TRACK_LIST: "mediaAudioTrackList";
    readonly MEDIA_AUDIO_TRACK_UNAVAILABLE: "mediaAudioTrackUnavailable";
    readonly MEDIA_BUFFERED: "mediaBuffered";
    readonly MEDIA_CAST_UNAVAILABLE: "mediaCastUnavailable";
    readonly MEDIA_CHAPTERS_CUES: "mediaChaptersCues";
    readonly MEDIA_CURRENT_TIME: "mediaCurrentTime";
    readonly MEDIA_DURATION: "mediaDuration";
    readonly MEDIA_ENDED: "mediaEnded";
    readonly MEDIA_ERROR: "mediaError";
    readonly MEDIA_ERROR_CODE: "mediaErrorCode";
    readonly MEDIA_ERROR_MESSAGE: "mediaErrorMessage";
    readonly MEDIA_FULLSCREEN_UNAVAILABLE: "mediaFullscreenUnavailable";
    readonly MEDIA_HAS_PLAYED: "mediaHasPlayed";
    readonly MEDIA_HEIGHT: "mediaHeight";
    readonly MEDIA_IS_AIRPLAYING: "mediaIsAirplaying";
    readonly MEDIA_IS_CASTING: "mediaIsCasting";
    readonly MEDIA_IS_FULLSCREEN: "mediaIsFullscreen";
    readonly MEDIA_IS_PIP: "mediaIsPip";
    readonly MEDIA_LOADING: "mediaLoading";
    readonly MEDIA_MUTED: "mediaMuted";
    readonly MEDIA_PAUSED: "mediaPaused";
    readonly MEDIA_PIP_UNAVAILABLE: "mediaPipUnavailable";
    readonly MEDIA_PLAYBACK_RATE: "mediaPlaybackRate";
    readonly MEDIA_PREVIEW_CHAPTER: "mediaPreviewChapter";
    readonly MEDIA_PREVIEW_COORDS: "mediaPreviewCoords";
    readonly MEDIA_PREVIEW_IMAGE: "mediaPreviewImage";
    readonly MEDIA_PREVIEW_TIME: "mediaPreviewTime";
    readonly MEDIA_RENDITION_LIST: "mediaRenditionList";
    readonly MEDIA_RENDITION_SELECTED: "mediaRenditionSelected";
    readonly MEDIA_RENDITION_UNAVAILABLE: "mediaRenditionUnavailable";
    readonly MEDIA_SEEKABLE: "mediaSeekable";
    readonly MEDIA_STREAM_TYPE: "mediaStreamType";
    readonly MEDIA_SUBTITLES_LIST: "mediaSubtitlesList";
    readonly MEDIA_SUBTITLES_SHOWING: "mediaSubtitlesShowing";
    readonly MEDIA_TARGET_LIVE_WINDOW: "mediaTargetLiveWindow";
    readonly MEDIA_TIME_IS_LIVE: "mediaTimeIsLive";
    readonly MEDIA_VOLUME: "mediaVolume";
    readonly MEDIA_VOLUME_LEVEL: "mediaVolumeLevel";
    readonly MEDIA_VOLUME_UNAVAILABLE: "mediaVolumeUnavailable";
    readonly MEDIA_WIDTH: "mediaWidth";
};
/**
 * @description The {@link https://react.dev/learn/passing-data-deeply-with-context#context-an-alternative-to-passing-props|React Context}
 * used "under the hood" for media ui state updates, state change requests, and the hooks and providers that integrate with this context.
 * It is unlikely that you will/should be using `MediaContext` directly.
 *
 * @see {@link MediaProvider}
 * @see {@link useMediaDispatch}
 * @see {@link useMediaSelector}
 */
export declare const MediaContext: Context<MediaStore | null>;
/**
 * @description A {@link https://react.dev/reference/react/createContext#provider|React Context.Provider} for having access
 * to media state and state updates. While many other react libraries that rely on `<Provider/>` and its corresponding context/hooks
 * are expected to have the context close to the top of the
 * {@link https://react.dev/learn/understanding-your-ui-as-a-tree#the-render-tree|React render tree}, `<MediaProvider/>` should
 * typically be declared closer to the component (e.g. `<MyFancyVideoPlayer/>`) level, as it manages the media state for a particular
 * playback experience (visual and otherwise), typically tightly tied to e.g. an `<audio/>` or `<video/>` component (or similar).
 * This state is tied together and managed by using {@link https://react.dev/learn/manipulating-the-dom-with-refs|DOM element Refs} to
 * e.g. the corresponding `<video/>` element, which is made easy by our specialized hooks such as {@link useMediaRef}.
 *
 * @example
 * import {
 *   MediaProvider,
 *   useMediaFullscreenRef,
 *   useMediaRef
 * } from 'media-chrome/react/media-store';
 * import MyFancyPlayButton from './MyFancyPlayButton';
 *
 * const MyFancyVideoPlayerContainer = ({ src }: { src: string }) => {
 *   const mediaFullscreenRef = useMediaFullscreenRef();
 *   const mediaRef = useMediaRef();
 *   return (
 *     <div ref={mediaFullscreenRef}>
 *       <video ref={mediaRef} src={src}/>
 *       <div><MyFancyPlayButton/><div>
 *     </div>
 *   );
 * };
 *
 * const MyFancyVideoPlayer = ({ src }) => {
 *   return (
 *     <MediaProvider><MyFancyVideoPlayerContainer src={src}/></MediaProvider>
 *   );
 * };
 *
 * export default MyFancyVideoPlayer;
 *
 * @see {@link useMediaRef}
 * @see {@link useMediaFullscreenRef}
 * @see {@link useMediaDispatch}
 * @see {@link useMediaSelector}
 */
export declare const MediaProvider: ({ children, mediaStore, }: {
    children: ReactNode;
    mediaStore?: MediaStore;
}) => React.JSX.Element;
export declare const useMediaStore: () => MediaStore;
/**
 * @description This is a hook to get access to the `MediaStore`'s `dispatch()` method, which allows
 * a component to make media state change requests. All player/application level state changes
 * should use `dispatch()` to change media state (e.g. playing/pausing, enabling/disabling/selecting subtitles,
 * changing playback rate, seeking, etc.). All well-defined state change request action types are defined in
 * `MediaActionTypes`.
 *
 * @example
 * import { useDispatch, MediaActionTypes } from 'media-chrome/react/media-store';
 *
 * // Assumes this is a descendant of `<MediaProvider/>`.
 * const MyComponent = () => {
 *   const dispatch = useDispatch();
 *   return (
 *     <button
 *       onClick={() => dispatch({
 *         type: MediaActionTypes.MEDIA_PLAYBACK_RATE_REQUEST,
 *         detail: 2.0
 *       })}
 *     >
 *       Faster!
 *     </button>
 *   );
 * };
 *
 * @see {@link MediaActionTypes}
 */
export declare const useMediaDispatch: () => MediaStore["dispatch"];
/**
 * @description This is the primary way to associate a media component with the `MediaStore` provided
 * by {@link MediaProvider|`<MediaProvider/>`}. To associate the media component, use `useMediaRef` just
 * like you would {@link https://react.dev/reference/react/useRef#manipulating-the-dom-with-a-ref|useRef}.
 * Unlike `useRef`, however, "under the hood" `useMediaRef` is actually a
 * {@link https://react.dev/reference/react-dom/components/common#ref-callback|ref callback} function.
 *
 * @example
 * import type { VideoHTMLAttributes } from 'react';
 * import { useMediaRef } from 'media-chrome/react/media-store';
 *
 * // Assumes this is a descendant of `<MediaProvider/>`.
 * const VideoWrapper = (props: VideoHTMLAttributes<HTMLVideoElement>) => {
 *   const mediaRef = useMediaRef();
 *   return <video ref={mediaRef} {...props}/>;
 * };
 *
 * @see {@link MediaProvider}
 */
export declare const useMediaRef: () => (mediaEl: MediaStateOwner | null | undefined) => void;
/**
 * @description This is the primary way to associate a component with the `MediaStore` provided
 * by {@link MediaProvider|`<MediaProvider/>`} to be used as the target for entering fullscreen.
 * To associate the media component, use `useMediaFullscreenRef` just
 * like you would {@link https://react.dev/reference/react/useRef#manipulating-the-dom-with-a-ref|useRef}.
 * Unlike `useRef`, however, "under the hood" `useMediaFullscreenRef` is actually a
 * {@link https://react.dev/reference/react-dom/components/common#ref-callback|ref callback} function.
 *
 * @example
 * import { useMediaFullscreenRef } from 'media-chrome/react/media-store';
 * import PlayerUI from './PlayerUI';
 *
 * // Assumes this is a descendant of `<MediaProvider/>`.
 * const PlayerContainer = () => {
 *   const fullscreenRef = useMediaFullscreenRef();
 *   return <div ref={fullscreenRef}><PlayerUI/></div>;
 * };
 *
 * @see {@link MediaProvider}
 */
export declare const useMediaFullscreenRef: () => (fullscreenEl: FullScreenElementStateOwner | null | undefined) => void;
/**
 * @description This is the primary way to get access to the media state. It accepts a function that let's you grab
 * only the bit of state you care about to avoid unnecessary re-renders in react. It also allows you to pass in a more
 * complex equality check (since you can transform the state or might only care about a subset of state changes, say only
 * caring about second precision for time updates). Modeled after a simplified version of
 * {@link https://redux.js.org/usage/deriving-data-selectors#encapsulating-state-shape-with-selectors|React Redux selectors}.
 * @param selector - a function that gets invoked with the latest state and returns whatever computed state you want to use
 * @param [equalityFn] - (optional) a function for checking if the previous computed state is "equal to" the next. Used to
 * avoid unnecessary re-renders. Checks strict identity (===) by default.
 * @returns the latest computed state
 *
 * @example
 * import { useMediaSelector } from 'media-chrome/react/media-store';
 *
 * // Assumes this is a descendant of `<MediaProvider/>`.
 * const LoadingIndicator = () => {
 *   const showLoading = useMediaSelector(state => state.mediaLoading && !state.mediaPaused);
 *   return showLoading && <div>Watch it, I'm loading, here! (...or don't, bc I'm loading, here!)</div>;
 * };
 *
 * @see {@link MediaProvider}
 */
export declare const useMediaSelector: <S = any>(selector: (state: Partial<MediaState>) => S, equalityFn?: (a: any, b: any) => boolean) => S;
