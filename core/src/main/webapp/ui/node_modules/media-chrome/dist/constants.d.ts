export declare const MediaUIEvents: {
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
    readonly MEDIA_SHOW_TEXT_TRACKS_REQUEST: "mediashowtexttracksrequest";
    readonly MEDIA_HIDE_TEXT_TRACKS_REQUEST: "mediahidetexttracksrequest";
    readonly MEDIA_SHOW_SUBTITLES_REQUEST: "mediashowsubtitlesrequest";
    readonly MEDIA_DISABLE_SUBTITLES_REQUEST: "mediadisablesubtitlesrequest";
    readonly MEDIA_TOGGLE_SUBTITLES_REQUEST: "mediatogglesubtitlesrequest";
    readonly MEDIA_PLAYBACK_RATE_REQUEST: "mediaplaybackraterequest";
    readonly MEDIA_RENDITION_REQUEST: "mediarenditionrequest";
    readonly MEDIA_AUDIO_TRACK_REQUEST: "mediaaudiotrackrequest";
    readonly MEDIA_SEEK_TO_LIVE_REQUEST: "mediaseektoliverequest";
    readonly REGISTER_MEDIA_STATE_RECEIVER: "registermediastatereceiver";
    readonly UNREGISTER_MEDIA_STATE_RECEIVER: "unregistermediastatereceiver";
};
export type MediaUIEvents = typeof MediaUIEvents;
export declare const MediaStateReceiverAttributes: {
    readonly MEDIA_CHROME_ATTRIBUTES: "mediachromeattributes";
    readonly MEDIA_CONTROLLER: "mediacontroller";
};
export type MediaStateReceiverAttributes = typeof MediaStateReceiverAttributes;
export declare const MediaUIProps: {
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
export type MediaUIProps = typeof MediaUIProps;
type LowercaseValues<T extends Record<any, string>> = {
    [k in keyof T]: Lowercase<T[k]>;
};
export type MediaUIAttributes = LowercaseValues<MediaUIProps>;
export declare const MediaUIAttributes: MediaUIAttributes;
declare const AdditionalStateChangeEvents: {
    readonly USER_INACTIVE_CHANGE: "userinactivechange";
    readonly BREAKPOINTS_CHANGE: "breakpointchange";
    readonly BREAKPOINTS_COMPUTED: "breakpointscomputed";
};
export type MediaStateChangeEvents = {
    [k in keyof MediaUIProps]: Lowercase<MediaUIProps[k]>;
} & typeof AdditionalStateChangeEvents;
/** @TODO In a prior migration, we dropped the 'change' from our state change event types. Although a breaking change, we should consider re-adding (CJP) */
export declare const MediaStateChangeEvents: MediaStateChangeEvents;
/** @TODO Make types more precise derivations, at least after updates to event type names mentioned above (CJP) */
export type StateChangeEventToAttributeMap = {
    [k in MediaStateChangeEvents[keyof MediaStateChangeEvents & keyof MediaUIAttributes]]: MediaUIAttributes[keyof MediaUIAttributes];
} & {
    userinactivechange: 'userinactive';
};
export declare const StateChangeEventToAttributeMap: StateChangeEventToAttributeMap;
/** @TODO Make types more precise derivations, at least after updates to event type names mentioned above (CJP) */
export type AttributeToStateChangeEventMap = {
    [k in MediaUIAttributes[keyof MediaUIAttributes & keyof MediaStateChangeEvents]]: MediaStateChangeEvents[keyof MediaStateChangeEvents];
} & {
    userinactive: 'userinactivechange';
};
export declare const AttributeToStateChangeEventMap: AttributeToStateChangeEventMap;
export declare const TextTrackKinds: {
    readonly SUBTITLES: "subtitles";
    readonly CAPTIONS: "captions";
    readonly DESCRIPTIONS: "descriptions";
    readonly CHAPTERS: "chapters";
    readonly METADATA: "metadata";
};
export type TextTrackKinds = (typeof TextTrackKinds)[keyof typeof TextTrackKinds];
export declare const TextTrackModes: {
    readonly DISABLED: "disabled";
    readonly HIDDEN: "hidden";
    readonly SHOWING: "showing";
};
export type TextTrackModes = typeof TextTrackModes;
export declare const ReadyStates: {
    readonly HAVE_NOTHING: 0;
    readonly HAVE_METADATA: 1;
    readonly HAVE_CURRENT_DATA: 2;
    readonly HAVE_FUTURE_DATA: 3;
    readonly HAVE_ENOUGH_DATA: 4;
};
export type ReadyStates = typeof ReadyStates;
export declare const PointerTypes: {
    readonly MOUSE: "mouse";
    readonly PEN: "pen";
    readonly TOUCH: "touch";
};
export type PointerTypes = typeof PointerTypes;
export declare const AvailabilityStates: {
    readonly UNAVAILABLE: "unavailable";
    readonly UNSUPPORTED: "unsupported";
};
export type AvailabilityStates = (typeof AvailabilityStates)[keyof typeof AvailabilityStates];
export declare const StreamTypes: {
    readonly LIVE: "live";
    readonly ON_DEMAND: "on-demand";
    readonly UNKNOWN: "unknown";
};
export type StreamTypes = (typeof StreamTypes)[keyof typeof StreamTypes];
export declare const VolumeLevels: {
    readonly HIGH: "high";
    readonly MEDIUM: "medium";
    readonly LOW: "low";
    readonly OFF: "off";
};
export type VolumeLevels = typeof VolumeLevels;
export declare const WebkitPresentationModes: {
    readonly INLINE: "inline";
    readonly FULLSCREEN: "fullscreen";
    readonly PICTURE_IN_PICTURE: "picture-in-picture";
};
export type WebkitPresentationModes = typeof WebkitPresentationModes;
export {};
